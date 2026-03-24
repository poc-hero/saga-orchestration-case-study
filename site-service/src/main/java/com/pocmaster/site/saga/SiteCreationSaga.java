package com.pocmaster.site.saga;

import com.lib.pocmaster.saga.SagaExecutor;
import com.lib.pocmaster.saga.SagaStep;
import com.pocmaster.site.creation.SiteCreationComponent;
import com.pocmaster.site.indexation.IndexationComponent;
import com.pocmaster.site.validation.ValidationComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class SiteCreationSaga {

    private static final Logger log = LoggerFactory.getLogger(SiteCreationSaga.class);

    private final ValidationComponent validationComponent;
    private final SiteCreationComponent siteCreationComponent;
    private final WebClient uaaClient;
    private final IndexationComponent indexationComponent;

    public SiteCreationSaga(
            ValidationComponent validationComponent,
            SiteCreationComponent siteCreationComponent,
            @Qualifier("uaaClient") WebClient uaaClient,
            IndexationComponent indexationComponent) {
        this.validationComponent = validationComponent;
        this.siteCreationComponent = siteCreationComponent;
        this.uaaClient = uaaClient;
        this.indexationComponent = indexationComponent;
    }

    public Mono<Void> run(CreateSiteRequest request) {
        log.info("Saga run started: companyId={}, siteName={}, failIndexation={}", request.companyId(), request.siteName(), request.failIndexation());

        // Step 1: validation + creation (merged)
        SagaStep<CreateSiteRequest, SiteCreatedWithFlag> step1 =
                new SagaStep<>(
                        req -> validationComponent.validate(req.companyId(), req.siteName())
                                .flatMap(validation -> siteCreationComponent.create(
                                        validation.validationId(),
                                        validation.companyId(),
                                        validation.siteName())
                                        .map(site -> new SiteCreatedWithFlag(site, Boolean.TRUE.equals(req.failIndexation())))),
                        flag -> siteCreationComponent.compensate(flag.site.siteId())
                                .then(validationComponent.compensate(flag.site.validationId())),
                        true,
                        true,
                        "validation-and-creation"
                );

        // Step 2: ACL
        SagaStep<SiteCreatedWithFlag, SiteCreatedWithFlag> step2 =
                new SagaStep<>(
                        flag -> uaaClient.post()
                                .uri("/api/acl/apply")
                                .bodyValue(new ApplyAclBody(flag.site.siteId()))
                                .retrieve()
                                .toBodilessEntity()
                                .then(Mono.just(flag)),
                        flag -> uaaClient.post()
                                .uri("/api/acl/compensate")
                                .bodyValue(new CompensateAclBody(flag.site.siteId()))
                                .retrieve()
                                .toBodilessEntity()
                                .then(),
                        true,
                        true,
                        "acl"
                );

        // Step 3: indexation (composante interne) — succeed=true → OK, succeed=false → erreur → déclenche compensations
        SagaStep<SiteCreatedWithFlag, SiteCreatedWithFlag> step3 =
                new SagaStep<>(
                        flag -> indexationComponent.publish(flag.site.siteId(), !flag.failIndexation())
                                .then(Mono.just(flag)),
                        flag -> indexationComponent.compensate(flag.site.siteId()),
                        true,
                        true,
                        "indexation"
                );

        return new SagaExecutor()
                .newExecutor(request)
                .addStep(step1)
                .addStep(step2)
                .addStep(step3)
                .execute()
                .doOnSuccess(v -> log.info("Saga run completed: companyId={}, siteName={}", request.companyId(), request.siteName()))
                .doOnError(e -> log.info("Saga run failed: companyId={}, siteName={}, error={}", request.companyId(), request.siteName(), e.getMessage()));
    }

    /** failIndexation=true → step 3 (indexation) échoue volontairement pour illustrer les compensations. */
    public record CreateSiteRequest(String companyId, String siteName, Boolean failIndexation) {
        public CreateSiteRequest(String companyId, String siteName) {
            this(companyId, siteName, null);
        }
    }

    private record SiteCreatedWithFlag(SiteCreationComponent.SiteCreated site, boolean failIndexation) {}

    private record ApplyAclBody(String siteId) {}
    private record CompensateAclBody(String siteId) {}
}
