package com.lib.pocmaster.saga.config;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables Saga autoconfiguration: imports Saga components (SagaExecutor, SagaStep, TransactionContext).
 * Place on the main class or a @Configuration class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SagaAutoConfiguration.class)
public @interface EnableSagaExecutor {
}
