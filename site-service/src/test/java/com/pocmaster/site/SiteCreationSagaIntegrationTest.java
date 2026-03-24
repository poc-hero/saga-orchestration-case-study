package com.pocmaster.site;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Integration test: full saga with 3 steps (validation+creation, acl, indexation) and compensation.
 * WireMock mocks UAA ; indexation est dans site-service. Run: mvn -pl site-service test
 * Console shows logs of each step; with failIndexation=true you see compensation logs.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class SiteCreationSagaIntegrationTest {

    private static final int WIREMOCK_HTTP_PORT = 8080;
    private static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.3.1";

    @Container
    static GenericContainer<?> uaaMock = new GenericContainer<>(DockerImageName.parse(WIREMOCK_IMAGE))
            .withExposedPorts(WIREMOCK_HTTP_PORT);

    @Autowired
    WebTestClient webTestClient;

    private static String baseUrl() {
        return "http://" + uaaMock.getHost() + ":" + uaaMock.getMappedPort(WIREMOCK_HTTP_PORT);
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("site.services.uaa.url", () -> baseUrl());
    }

    @BeforeEach
    void stubUaaEndpoints() {
        String adminUrl = baseUrl();
        postStub(adminUrl, Map.of(
                "request", Map.of("method", "POST", "urlPath", "/api/acl/apply"),
                "response", Map.of("status", 200)
        ));
        postStub(adminUrl, Map.of(
                "request", Map.of("method", "POST", "urlPath", "/api/acl/compensate"),
                "response", Map.of("status", 200)
        ));
    }

    private void postStub(String adminUrl, Object stub) {
        WebTestClient.bindToServer()
                .baseUrl(adminUrl)
                .build()
                .post()
                .uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(stub)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void createSite_success_logs_each_step() {
        webTestClient
                .post()
                .uri("/api/site/create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "companyId", "company-1",
                        "siteName", "Test Site"
                ))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void createSite_failIndexation_triggers_compensation() {
        webTestClient
                .post()
                .uri("/api/site/create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "companyId", "company-1",
                        "siteName", "Test Site",
                        "failIndexation", true
                ))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void secretCheck_returns_ok_and_masked_value() {
        webTestClient
                .get()
                .uri("/api/site/secret-check")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.secretLength").isEqualTo("17")
                .jsonPath("$.masked").isEqualTo("te***ue");
    }
}
