package com.lib.pocmaster.saga;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * One step of a Saga: an action and its compensation.
 *
 * @param name optional step name for logging (e.g. "validation", "create-site"); if null or blank, executor uses "step-N"
 */
public record SagaStep<I, O>(
        Function<I, Mono<O>> action,
        Function<O, Mono<Void>> compensation,
        boolean transactionalAction,
        boolean transactionalCompensation,
        String name
) {
    /**
     * Creates a step with no name (executor will log "step-N").
     */
    public SagaStep(Function<I, Mono<O>> action, Function<O, Mono<Void>> compensation,
                    boolean transactionalAction, boolean transactionalCompensation) {
        this(action, compensation, transactionalAction, transactionalCompensation, null);
    }
}
