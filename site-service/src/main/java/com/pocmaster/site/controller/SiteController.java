package com.pocmaster.site.controller;

import com.pocmaster.site.saga.SiteCreationSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/site")
@RefreshScope
public class SiteController {

    private static final Logger log = LoggerFactory.getLogger(SiteController.class);
    private final SiteCreationSaga siteCreationSaga;
    private final String mySecret;
    private final boolean isProd;

    public SiteController(SiteCreationSaga siteCreationSaga,
                          @Value("${app.secrets.my-secret:}") String mySecret,
                          Environment env) {
        this.siteCreationSaga = siteCreationSaga;
        this.mySecret = mySecret != null ? mySecret : "";
        this.isProd = java.util.Arrays.stream(env.getActiveProfiles()).anyMatch("prod"::equals);
    }

    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> createSite(@RequestBody SiteCreationSaga.CreateSiteRequest request) {
        log.info("POST /api/site/create: companyId={}, siteName={}", request.companyId(), request.siteName());
        return siteCreationSaga.run(request);
    }

    /**
     * Endpoint de test pour vérifier la valeur du secret Vault (Phase 4 - Option A).
     * Log en INFO : valeur complète en dev, masquée en prod.
     */
    @GetMapping(value = "/secret-check", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> secretCheck() {
        String toLog = isProd ? maskSecret(mySecret) : mySecret;
        log.info("GET /api/site/secret-check: valeur du secret (log) = {}", toLog);
        return Mono.just(Map.of(
                "status", "ok",
                "secretLength", String.valueOf(mySecret != null ? mySecret.length() : 0),
                "masked", maskSecret(mySecret != null ? mySecret : "")
        ));
    }

    private static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return "(vide)";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
