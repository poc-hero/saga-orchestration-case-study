package com.pocmaster.site.controller;

import com.pocmaster.site.metadata.Metadata;
import com.pocmaster.site.metadata.MetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/site")
@Profile("mongo")
public class MetadataController {

    private static final Logger log = LoggerFactory.getLogger(MetadataController.class);
    private final MetadataRepository metadataRepository;

    public MetadataController(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    @GetMapping(value = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Metadata> listMetadata() {
        log.info("GET /api/site/metadata");
        return metadataRepository.findAll();
    }

    @GetMapping(value = "/metadata/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Metadata> getMetadataById(@PathVariable String id) {
        log.info("GET /api/site/metadata/{}", id);
        return metadataRepository.findById(id);
    }

    @PostMapping(value = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Metadata> createMetadata(@RequestBody Metadata metadata) {
        log.info("POST /api/site/metadata: editor={}, app_version={}", metadata.getEditor(), metadata.getAppVersion());
        return metadataRepository.save(metadata);
    }
}
