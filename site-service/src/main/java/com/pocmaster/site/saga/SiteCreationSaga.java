package com.pocmaster.site.saga;

import com.lib.pocmaster.saga.SagaExecutor;
import com.lib.pocmaster.saga.SagaStep;
import com.pocmaster.site.site.SiteCreationComponent;
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

    public SiteCreationSaga(
            ValidationComponent validationComponent,
            SiteCreationComponent siteCreationComponent,
            @Qualifier("uaaClient") WebClient uaaClient) {
        this.validationComponent = validationComponent;
        this.siteCreationComponent = siteCreationComponent;
        this.uaaClient = uaaClient;
    }

    public Mono<Void> run(CreateSiteRequest request) {
        log.info("Saga run started: companyId={}, siteName={}", request.companyId(), request.siteName());
        SagaStep<CreateSiteRequest, ValidationComponent.ValidationResult> step1 =
                new SagaStep<>(
                        req -> validationComponent.validate(req.companyId(), req.siteName()),
                        result -> validationComponent.compensate(result.validationId()),
                        true,
                        true,
                        "validation"
                );

        SagaStep<ValidationComponent.ValidationResult, SiteCreationComponent.SiteCreated> step2 =
                new SagaStep<>(
                        validation -> siteCreationComponent.create(
                                validation.validationId(),
                                validation.companyId(),
                                validation.siteName()),
                        site -> siteCreationComponent.compensate(site.siteId()),
                        true,
                        true,
                        "create-site"
                );

        SagaStep<SiteCreationComponent.SiteCreated, SiteCreationComponent.SiteCreated> step3 =
                new SagaStep<>(
                        site -> uaaClient.post()
                                .uri("/api/acl/apply")
                                .bodyValue(new ApplyAclBody(site.siteId()))
                                .retrieve()
                                .toBodilessEntity()
                                .then(Mono.just(site)),
                        site -> uaaClient.post()
                                .uri("/api/acl/compensate")
                                .bodyValue(new CompensateAclBody(site.siteId()))
                                .retrieve()
                                .toBodilessEntity()
                                .then(),
                        true,
                        true,
                        "acl"
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

    public record CreateSiteRequest(String companyId, String siteName) {}
    private record ApplyAclBody(String siteId) {}
    private record CompensateAclBody(String siteId) {}
}
