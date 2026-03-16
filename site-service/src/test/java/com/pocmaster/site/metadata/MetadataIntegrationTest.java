package com.pocmaster.site.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Integration test for metadata endpoints (GET, GET by id, POST).
 * Uses Testcontainers MongoDB. Profile "mongo" activates MongoDB config.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("mongo")
class MetadataIntegrationTest {

    @Container
    static GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:7"))
            .withExposedPorts(27017);

    @Autowired
    WebTestClient webTestClient;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        String uri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/saga";
        registry.add("mongodb-uri", () -> uri);
    }

    @Test
    void listMetadata_emptyInitially() {
        webTestClient
                .get()
                .uri("/api/site/metadata")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Metadata.class).hasSize(0);
    }

    @Test
    void postMetadata_thenGetAndGetById() {
        Metadata created = webTestClient
                .post()
                .uri("/api/site/metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "editor", "ACME Corp",
                        "app_version", "1.0.0",
                        "manager", "John Doe",
                        "description", "Sample metadata for integration test"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Metadata.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotEmpty();
        assertThat(created.getEditor()).isEqualTo("ACME Corp");
        assertThat(created.getAppVersion()).isEqualTo("1.0.0");
        assertThat(created.getManager()).isEqualTo("John Doe");
        assertThat(created.getDescription()).isEqualTo("Sample metadata for integration test");

        webTestClient
                .get()
                .uri("/api/site/metadata")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Metadata.class).hasSize(1);

        webTestClient
                .get()
                .uri("/api/site/metadata/" + created.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Metadata.class)
                .value(m -> assertThat(m.getId()).isEqualTo(created.getId()));
    }
}
