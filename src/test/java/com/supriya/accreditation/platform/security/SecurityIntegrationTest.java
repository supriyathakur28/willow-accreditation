package com.supriya.accreditation.platform.security;

import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@Property(name = "micronaut.security.enabled", value = "true")
class SecurityIntegrationTest extends ApiIntegrationTest {

    private static final String OTHER_USER = "kq3rj7kY14mADZsVQ0qD7WjsvbS2h26C";

    @BeforeEach
    void clean() throws Exception {
        resetDatabases();
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        registerUser(USER);
    }

    @Test
    void rejectsRequestsWithoutAValidToken() {
        final Reply none = send(HttpRequest.POST("/user/accreditation", BRIEF_POST));
        final Reply forged = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).bearerAuth(adminToken() + "x"));
        final Reply read = send(HttpRequest.GET("/user/" + USER + "/accreditation"));

        assertThat(List.of(none, forged, read)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(401);
            assertThat(reply.body()).containsEntry("error_code", "UNAUTHORIZED").containsKey("request_id");
        });
    }

    @Test
    void adminWritesAreRecordedUnderTheTokenSubject() {
        final Reply created = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).bearerAuth(adminToken()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(rows("accreditation_write", "SELECT created_by FROM accreditations").getFirst())
                .containsEntry("created_by", "admin-7");
        assertThat((String) rows("accreditation_write", "SELECT payload FROM outbox_events").getFirst().get("payload"))
                .contains("\"performed_by\":\"admin-7\"");
    }

    @Test
    void usersCannotUseAdminEndpoints() {
        final String token = userToken(USER);
        final String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST).bearerAuth(adminToken())));

        final Reply post = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).bearerAuth(token));
        final Reply put = send(put(id, "CONFIRMED").bearerAuth(token));
        final Reply history = send(HttpRequest.GET("/user/accreditation/" + id + "/history").bearerAuth(token));

        assertThat(List.of(post, put, history)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(403);
            assertThat(reply.body()).containsEntry("error_code", "FORBIDDEN");
        });
    }

    @Test
    void aUserReadsOnlyTheirOwnAccreditations() {
        final Reply own = send(HttpRequest.GET("/user/" + USER + "/accreditation").bearerAuth(userToken(USER)));
        final Reply other = send(HttpRequest.GET("/user/" + USER + "/accreditation").bearerAuth(userToken(OTHER_USER)));
        final Reply admin = send(HttpRequest.GET("/user/" + USER + "/accreditation").bearerAuth(adminToken()));

        assertThat(own.status()).isEqualTo(200);
        assertThat(other.status()).isEqualTo(403);
        assertThat(admin.status()).isEqualTo(200);
    }

    @Test
    void tokenRequestsAreValidated() {
        final Reply userWithoutId = send(HttpRequest.POST("/auth/token", "{\"role\": \"USER\"}"));
        final Reply unknownRole = send(HttpRequest.POST("/auth/token", "{\"role\": \"ROOT\"}"));

        assertThat(invalidFields(userWithoutId)).containsExactly("user_id:REQUIRED");
        assertThat(invalidFields(unknownRole)).containsExactly("role:INVALID_VALUE");
    }

    private String adminToken() {
        return token("{\"role\": \"ADMIN\", \"subject\": \"admin-7\"}");
    }

    private String userToken(final String userId) {
        return token("{\"role\": \"USER\", \"user_id\": \"" + userId + "\"}");
    }

    private String token(final String body) {
        final Reply reply = send(HttpRequest.POST("/auth/token", body));
        assertThat(reply.status()).as("token %s", reply.body()).isEqualTo(200);
        assertThat(reply.body()).containsEntry("token_type", "Bearer");
        return (String) reply.body().get("access_token");
    }
}
