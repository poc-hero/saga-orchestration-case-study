package com.lib.pocmaster.saga;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Function;

public final class TransactionContext {

    private static final String KEY = "saga-transaction-id";

    private TransactionContext() {}

    /**
     * Generates a new transaction id (one per saga request). Call once at the start of execute().
     */
    public static String newTransactionId() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Returns a function to put the given id into the Reactor context. Use at the root of the chain
     * (e.g. in execute()) so the same id propagates to every step and compensation.
     */
    public static Function<Context, Context> withId(String transactionId) {
        return ctx -> ctx.put(KEY, transactionId);
    }

    public static String getTransactionId(ContextView ctx) {
        return ctx.getOrDefault(KEY, "UNKNOWN");
    }
}
