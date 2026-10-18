package com.supriya.accreditation.platform.web;

import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each audience gets its own published contract. A controller added without an {@code @OpenAPIGroup} lands in
 * every document, so these tests list exactly what each one may contain.
 */
@MicronautTest(transactional = false)
@Property(name = "micronaut.security.enabled", value = "true")
class OpenApiContractTest extends ApiIntegrationTest {

    private static final String ADMIN = "/swagger/accreditation-admin-api-1.0-admin.yml";
    private static final String CLIENT = "/swagger/accreditation-client-api-1.0-client.yml";

    @Test
    void theAdminContractHasOnlyTheAdminEndpoints() {
        assertThat(paths(ADMIN)).containsOnlyKeys(
                "/user/accreditation",
                "/user/accreditation/{accreditationId}",
                "/user/accreditation/{accreditationId}/history");
    }

    @Test
    void theClientContractHasOnlyTheClientEndpoint() {
        assertThat(paths(CLIENT)).containsOnlyKeys("/user/{userId}/accreditation");
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN, CLIENT})
    @SuppressWarnings("unchecked")
    void everyOperationRequiresABearerToken(final String document) {
        assertThat(paths(document).values()).allSatisfy(path -> assertThat(path.values()).allSatisfy(operation ->
                assertThat(((Map<String, Object>) operation).get("security"))
                        .isEqualTo(List.of(Map.of("bearer", List.of())))));
    }

    /** Fetched over HTTP without a token: the documents are public even when the API is secured. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> paths(final String document) {
        final String yaml = client.toBlocking().retrieve(HttpRequest.GET(document), String.class);
        final Map<String, Object> spec = new Yaml().load(yaml);
        return (Map<String, Map<String, Object>>) spec.get("paths");
    }
}
