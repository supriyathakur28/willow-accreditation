package com.supriya.accreditation.platform.idempotency;

import com.supriya.accreditation.platform.web.ApiException;
import com.supriya.accreditation.platform.web.ErrorCodes;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.BeanProvider;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Rejects an Idempotency-Key reused with a different body. One atomic command,
 * {@code SET idem:{caller}:{route}:{key} <body hash> NX GET EX 24h}, both claims the key and returns the hash
 * already stored, so there is no check-then-set race. Responses are not stored.
 * <p>
 * Redis is only an early check: if it is down the request goes ahead on the database guarantees
 * (the unique key on POST, the no-op on a repeated PUT), so a Redis outage never fails a write.
 */
@Singleton
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    static final Duration TTL = Duration.ofHours(24);

    private final BeanProvider<StatefulRedisConnection<String, String>> redis;

    public IdempotencyGuard(BeanProvider<StatefulRedisConnection<String, String>> redis) {
        this.redis = redis;
    }

    public void check(String caller, String route, String idempotencyKey, String requestSha256) {
        String key = "idem:" + caller + ":" + route + ":" + idempotencyKey;
        String previous;
        try {
            // Bounded by the ?timeout= in redis.uri, which also covers opening the connection
            previous = redis.get().sync().setGet(key, requestSha256, SetArgs.Builder.nx().ex(TTL));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, skipping the idempotency key check: {}", e.toString());
            return;
        }
        if (previous != null && !previous.equals(requestSha256)) {
            throw keyReused();
        }
    }

    public static ApiException keyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used with a different request body");
    }
}
