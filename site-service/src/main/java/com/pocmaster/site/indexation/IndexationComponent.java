package com.pocmaster.site.indexation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Composante d'indexation (publication) dans site-service.
 * succeed=true → OK, succeed=false → erreur pour déclencher les compensations de la Saga.
 */
@Component
public class IndexationComponent {

    private static final Logger log = LoggerFactory.getLogger(IndexationComponent.class);

    public Mono<Void> publish(String siteId, boolean succeed) {
        log.info("indexation publish: siteId={}, succeed={}", siteId, succeed);
        if (succeed) {
            return Mono.empty();
        }
        log.info("indexation forced to fail (succeed=false), triggering compensation");
        return Mono.error(new IndexationFailedException("succeed=false"));
    }

    public Mono<Void> compensate(String siteId) {
        log.info("indexation compensate: siteId={}", siteId);
        return Mono.empty();
    }

    public static class IndexationFailedException extends RuntimeException {
        public IndexationFailedException(String message) {
            super(message);
        }
    }
}
