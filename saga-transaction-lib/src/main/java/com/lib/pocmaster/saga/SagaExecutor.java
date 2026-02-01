package com.lib.pocmaster.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reactive Saga executor: runs steps in order and triggers compensations in reverse order on failure.
 */
public class SagaExecutor {

    private static final Logger log = LoggerFactory.getLogger(SagaExecutor.class);

    private final List<SagaStep<?, ?>> steps = new ArrayList<>();
    private Object input;

    public <I> SagaExecutor newExecutor(I input) {
        log.info("newExecutor: input={}", input);
        this.input = input;
        return this;
    }

    public <I, O> SagaExecutor addStep(SagaStep<I, O> step) {
        log.info("addStep: stepIndex={}", steps.size());
        steps.add(step);
        return this;
    }

    /**
     * Runs the saga. One transaction id per request is set at the root so it propagates to every step and compensation.
     */
    public Mono<Void> execute() {
        String transactionId = TransactionContext.newTransactionId();
        log.info("execute: stepsCount={}, transactionId={}", steps.size(), transactionId);
        return executeStep(0, input, new ArrayList<>())
                .contextWrite(TransactionContext.withId(transactionId));
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> executeStep(int index, Object currentInput, List<Object> results) {
        return Mono.deferContextual(ctx -> {
            String transactionId = TransactionContext.getTransactionId(ctx);
            if (index >= steps.size()) {
                log.info("executeStep: completed, index={}, transactionId={}", index, transactionId);
                return Mono.empty();
            }

            SagaStep<Object, Object> step = (SagaStep<Object, Object>) steps.get(index);
            String stepName = stepName(step, index);
            log.info("executeStep: index={}/{}, step={}, transactionId={}", index + 1, steps.size(), stepName, transactionId);

            Mono<Object> actionMono = step.transactionalAction()
                    ? runInTransaction(() -> step.action().apply(currentInput))
                    : step.action().apply(currentInput);

            return actionMono
                    .flatMap(output -> {
                        // Keep each step's output so we can pass it to the compensation on rollback:
                        // each compensation receives the result of the action it must undo (e.g. siteId to delete).
                        results.add(output);
                        return executeStep(index + 1, output, results);
                    })
                    .onErrorResume(ex -> {
                        log.info("executeStep failed: index={}, step={}, error={}, transactionId={}", index + 1, stepName, ex.getMessage(), transactionId);
                        return rollback(index - 1, results, ex);
                    });
        });
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> rollback(int index, List<Object> results, Throwable originalError) {
        if (index < 0) {
            log.info("rollback: finished, propagating original error");
            return Mono.error(originalError);
        }

        SagaStep<Object, Object> step = (SagaStep<Object, Object>) steps.get(index);
        String stepName = stepName(step, index);
        log.info("rollback: compensating step index={}, step={}", index + 1, stepName);
        // Output produced by this step when it ran; the compensation needs it to undo the action (e.g. siteId to delete the created site).
        Object result = results.get(index);

        Mono<Void> compensationMono = step.transactionalCompensation()
                ? runInTransaction(() -> step.compensation().apply(result))
                : step.compensation().apply(result);

        return compensationMono
                .onErrorResume(e -> {
                    log.error("Compensation step {} failed: {}", stepName, e.getMessage(), e);
                    return Mono.empty();
                })
                .then(rollback(index - 1, results, originalError));
    }

    private static String stepName(SagaStep<?, ?> step, int index) {
        return (step.name() != null && !step.name().isBlank()) ? step.name() : "step-" + (index + 1);
    }

    /**
     * Runs the action; the saga-transaction-id is already in the context from execute() and propagates here.
     */
    private <T> Mono<T> runInTransaction(Supplier<Mono<T>> action) {
        return Mono.deferContextual(ctx -> {
            log.info("runInTransaction: transactionId={}", TransactionContext.getTransactionId(ctx));
            return action.get();
        });
    }
}
