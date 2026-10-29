package in.chinthanshetty.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import in.chinthanshetty.payments.api.ApiException;
import in.chinthanshetty.payments.api.ApiKeyFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes POSTs safe to retry. The first request with a key claims it (a committed "in_progress" row),
 * runs, then stores its response. A retry with the same key and body gets that stored response
 * back without running again. Reusing a key for a different request is a client error, and a
 * duplicate that arrives while the first is still running gets a 409 instead of running twice.
 */
@Service
public class IdempotencyService {
    /** A claim older than this is treated as abandoned (the first request's process died). */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        // Canonical form for fingerprints: key order in maps must not change the hash.
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ResponseEntity<Object> run(String merchantId, String key, String method, String path, Object body,
                                      Supplier<ResponseEntity<Object>> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 255) {
            throw ApiException.badRequest("idempotency_key_invalid", "Idempotency-Key must be at most 255 characters");
        }
        String fingerprint = ApiKeyFilter.sha256(method + " " + path + " " + canonical(body));

        ResponseEntity<Object> replay = claimOrReplay(merchantId, key, fingerprint);
        if (replay != null) {
            return replay;
        }
        try {
            ResponseEntity<Object> response = action.get();
            complete(merchantId, key, response.getStatusCode().value(), response.getBody());
            return response;
        } catch (ApiException e) {
            // Expected client errors are part of the result: a retry should see the same error.
            complete(merchantId, key, e.status().value(), e.body());
            throw e;
        } catch (RuntimeException e) {
            // Unexpected failure: release the key so the client can safely retry.
            jdbc.sql("DELETE FROM idempotency_keys WHERE merchant_id = ? AND key = ?").params(merchantId, key).update();
            throw e;
        }
    }

    private ResponseEntity<Object> claimOrReplay(String merchantId, String key, String fingerprint) {
        Timestamp now = Timestamp.from(clock.instant());
        try {
            tx.executeWithoutResult(s -> jdbc.sql("""
                    INSERT INTO idempotency_keys (merchant_id, key, fingerprint, status, locked_at)
                    VALUES (?, ?, ?, 'in_progress', ?)""")
                    .params(merchantId, key, fingerprint, now).update());
            return null; // we own the key
        } catch (DuplicateKeyException taken) {
            var row = jdbc.sql("SELECT fingerprint, status, response_status, response_body, locked_at FROM idempotency_keys WHERE merchant_id = ? AND key = ?")
                    .params(merchantId, key)
                    .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5)))
                    .single();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                        "This Idempotency-Key was already used with a different request");
            }
            if ("in_progress".equals(row.status())) {
                boolean stale = row.lockedAt().toInstant().plus(STALE_AFTER).isBefore(clock.instant());
                if (stale && takeOver(merchantId, key, row.lockedAt(), now)) {
                    return null;
                }
                throw ApiException.conflict("idempotency_in_progress",
                        "A request with this Idempotency-Key is still being processed; retry shortly");
            }
            return ResponseEntity.status(row.responseStatus())
                    .header("Idempotent-Replayed", "true")
                    .body(parse(row.responseBody()));
        }
    }

    private boolean takeOver(String merchantId, String key, Timestamp seenLock, Timestamp now) {
        // Compare-and-set on locked_at: only one retry can take over an abandoned claim.
        return jdbc.sql("UPDATE idempotency_keys SET locked_at = ? WHERE merchant_id = ? AND key = ? AND status = 'in_progress' AND locked_at = ?")
                .params(now, merchantId, key, seenLock).update() == 1;
    }

    private void complete(String merchantId, String key, int status, Object body) {
        jdbc.sql("UPDATE idempotency_keys SET status = 'completed', response_status = ?, response_body = ? WHERE merchant_id = ? AND key = ?")
                .params(status, write(body), merchantId, key).update();
    }

    private String canonical(Object body) {
        return body == null ? "" : write(json.convertValue(body, Map.class));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object parse(String body) {
        try {
            return body == null ? null : json.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(String fingerprint, String status, int responseStatus, String responseBody, Timestamp lockedAt) {}
}
