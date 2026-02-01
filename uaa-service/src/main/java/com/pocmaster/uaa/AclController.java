package com.pocmaster.uaa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/acl")
public class AclController {

    private static final Logger log = LoggerFactory.getLogger(AclController.class);

    @PostMapping(value = "/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> apply(@RequestBody ApplyRequest request) {
        log.info("POST /api/acl/apply: siteId={}", request.siteId());
        return Mono.empty();
    }

    @PostMapping(value = "/compensate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> compensate(@RequestBody CompensateRequest request) {
        log.info("POST /api/acl/compensate: siteId={}", request.siteId());
        return Mono.empty();
    }

    public record ApplyRequest(String siteId) {}
    public record CompensateRequest(String siteId) {}
}
