package com.pocmaster.site.site;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Site creation component (transient state).
 */
@Component
public class SiteCreationComponent {

    private static final Logger log = LoggerFactory.getLogger(SiteCreationComponent.class);

    public Mono<SiteCreated> create(String validationId, String companyId, String siteName) {
        log.info("create site: validationId={}, companyId={}, siteName={}", validationId, companyId, siteName);
        return Mono.just(new SiteCreated(
                UUID.randomUUID().toString(),
                validationId,
                companyId,
                siteName
        )).doOnNext(s -> log.info("create site result: siteId={}", s.siteId()));
    }

    public Mono<Void> compensate(String siteId) {
        log.info("compensate site: siteId={}", siteId);
        return Mono.empty();
    }

    public record SiteCreated(String siteId, String validationId, String companyId, String siteName) {}
}
