package com.pocmaster.site.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Validation of upstream referentials (company, partners).
 * Part of site-service, not a separate service.
 */
@Component
public class ValidationComponent {

    private static final Logger log = LoggerFactory.getLogger(ValidationComponent.class);

    public Mono<ValidationResult> validate(String companyId, String siteName) {
        log.info("validate: companyId={}, siteName={}", companyId, siteName);
        return Mono.just(new ValidationResult(
                UUID.randomUUID().toString(),
                companyId,
                siteName
        )).doOnNext(r -> log.info("validate result: validationId={}", r.validationId()));
    }

    public Mono<Void> compensate(String validationId) {
        log.info("compensate validation: validationId={}", validationId);
        return Mono.empty();
    }

    public record ValidationResult(String validationId, String companyId, String siteName) {}
}
