package com.pocmaster.site;

import com.pocmaster.site.saga.SiteCreationSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/site")
public class SiteController {

    private static final Logger log = LoggerFactory.getLogger(SiteController.class);
    private final SiteCreationSaga siteCreationSaga;

    public SiteController(SiteCreationSaga siteCreationSaga) {
        this.siteCreationSaga = siteCreationSaga;
    }

    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> createSite(@RequestBody SiteCreationSaga.CreateSiteRequest request) {
        log.info("POST /api/site/create: companyId={}, siteName={}", request.companyId(), request.siteName());
        return siteCreationSaga.run(request);
    }
}
