package com.supriya.accreditation.support;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP helpers for tests that drive the service through its API. */
public abstract class ApiIntegrationTest extends IntegrationTest {

    protected static final String USER = "g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V";
    // The brief's request body, byte for byte
    protected static final String BRIEF_POST = """
            {
              "user_id": "g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V",
              "accreditation_type": "BY_INCOME",
              "document": {
                "name": "2018.pdf",
                "mime_type": "application/pdf",
                "content": "ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg=="
              }
            }""";

    @Inject @Client("/") protected HttpClient client;
    @Inject protected JsonMapper json;

    protected record Reply(
            int status,
            Map<String, Object> body,
            HttpResponse<?> raw) {
        public String header(final String name) {
            return raw.getHeaders().get(name);
        }
    }

    protected static MutableHttpRequest<String> put(
            final String id,
            final String outcome) {
        return HttpRequest.PUT("/user/accreditation/" + id, "{\"outcome\": \"" + outcome + "\"}");
    }

    protected Reply send(final MutableHttpRequest<?> request) {
        request.contentType(MediaType.APPLICATION_JSON_TYPE);
        HttpResponse<String> response;
        try {
            response = client.toBlocking().exchange(request, Argument.of(String.class), Argument.of(String.class));
        } catch (HttpClientResponseException e) {
            response = e.getResponse().getBody(String.class)
                    .map(body -> (HttpResponse<String>) e.getResponse().toMutableResponse().body(body))
                    .orElse((HttpResponse<String>) e.getResponse());
        }
        final String body = response.getBody(String.class).orElse("");
        return new Reply(response.getStatus().getCode(), body.isBlank() ? Map.of() : parse(body), response);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parse(final String body) {
        try {
            return json.readValue(body, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Not JSON: " + body, e);
        }
    }

    protected static String idOf(final Reply reply) {
        assertThat(reply.status()).as("body %s", reply.body()).isEqualTo(201);
        return (String) reply.body().get("accreditation_id");
    }

    @SuppressWarnings("unchecked")
    protected static List<String> invalidFields(final Reply reply) {
        return ((List<Map<String, Object>>) reply.body().get("invalid_fields")).stream()
                .map(f -> f.get("field_name") + ":" + f.get("error"))
                .toList();
    }
}
