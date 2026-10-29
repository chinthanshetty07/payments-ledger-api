package in.chinthanshetty.payments.webhooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending webhooks. Due deliveries are claimed by pushing next_attempt_at forward by a
 * lease inside a short transaction (FOR UPDATE SKIP LOCKED), so several workers can run without
 * sending the same delivery twice, and no database lock is held during the HTTP call. Failures
 * retry with exponential backoff until max-attempts, then the delivery is marked failed.
 * Delivery is at-least-once: receivers should de-duplicate on the event id.
 */
@Service
public class WebhookDispatcher {
    public record Claimed(long id, String eventId, String payload, String url, String secret, int attempts) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HttpClient http;
    private final long backoffBaseMs;
    private final int maxAttempts;
    private final long leaseSeconds;

    public WebhookDispatcher(JdbcClient jdbc, TransactionTemplate tx, Clock clock,
                             @Value("${webhooks.backoff-base-ms}") long backoffBaseMs,
                             @Value("${webhooks.max-attempts}") int maxAttempts,
                             @Value("${webhooks.lease-seconds}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.backoffBaseMs = backoffBaseMs;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** One pass over due deliveries. Returns how many were attempted. */
    public int dispatchDue() {
        List<Claimed> batch = claim(20);
        for (Claimed d : batch) {
            deliver(d);
        }
        return batch.size();
    }

    public List<Claimed> claim(int limit) {
        return tx.execute(s -> {
            Timestamp now = Timestamp.from(clock.instant());
            List<Claimed> due = jdbc.sql("""
                    SELECT d.id, d.event_id, e.payload, w.url, w.secret, d.attempts
                    FROM webhook_deliveries d
                    JOIN events e ON e.id = d.event_id
                    JOIN webhook_endpoints w ON w.id = d.endpoint_id
                    WHERE d.status = 'pending' AND d.next_attempt_at <= ?
                    ORDER BY d.next_attempt_at
                    LIMIT ?
                    FOR UPDATE OF d SKIP LOCKED""")
                    .params(now, limit)
                    .query((rs, n) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)))
                    .list();
            Timestamp lease = Timestamp.from(clock.instant().plusSeconds(leaseSeconds));
            for (Claimed d : due) {
                jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?").params(lease, d.id()).update();
            }
            return due;
        });
    }

    void deliver(Claimed d) {
        Instant now = clock.instant();
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(d.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Webhook-Id", d.eventId())
                    .header("Webhook-Signature", WebhookSigner.header(d.secret(), d.payload(), now))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                error = "HTTP " + response.statusCode();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        record(d, error);
    }

    private void record(Claimed d, String error) {
        int attempts = d.attempts() + 1;
        if (error == null) {
            jdbc.sql("UPDATE webhook_deliveries SET status = 'succeeded', attempts = ?, last_error = NULL WHERE id = ?")
                    .params(attempts, d.id()).update();
            return;
        }
        if (attempts >= maxAttempts) {
            jdbc.sql("UPDATE webhook_deliveries SET status = 'failed', attempts = ?, last_error = ? WHERE id = ?")
                    .params(attempts, error, d.id()).update();
            return;
        }
        long delayMs = Math.min(backoffBaseMs * (1L << (attempts - 1)), Duration.ofHours(1).toMillis());
        jdbc.sql("UPDATE webhook_deliveries SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?")
                .params(attempts, error, Timestamp.from(clock.instant().plusMillis(delayMs)), d.id()).update();
    }
}
