package in.chinthanshetty.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Real HTTP against a real PostgreSQL (DATABASE_URL). Tables are emptied before each test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestBase {
    public static final String KEY_A = "demo_key_merchant_a";
    public static final String KEY_B = "demo_key_merchant_b";

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcClient jdbc;

    protected final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public record Res(int status, Map<String, Object> body, HttpResponse<String> raw) {
        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String field) {
            return (Map<String, Object>) body.get(field);
        }

        public String str(String field) {
            return (String) body.get(field);
        }

        public long num(String field) {
            return ((Number) body.get(field)).longValue();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE webhook_deliveries, events, webhook_endpoints, idempotency_keys, ledger_entries, ledger_transactions, refunds, payment_intents RESTART IDENTITY CASCADE").update();
    }

    protected Res post(String key, String path, Object body) {
        return post(key, path, body, null);
    }

    protected Res post(String apiKey, String path, Object body, String idempotencyKey) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : json.writeValueAsString(body)));
            if (apiKey != null) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            return send(b.build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Res get(String apiKey, String path) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (apiKey != null) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return send(b.build());
    }

    @SuppressWarnings("unchecked")
    private Res send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = r.body().isBlank() ? Map.of() : json.readValue(r.body(), Map.class);
            return new Res(r.statusCode(), body, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Res createIntent(long amount, String captureMethod) {
        return post(KEY_A, "/v1/payment_intents", Map.of("amount", amount, "currency", "inr", "capture_method", captureMethod));
    }

    protected long balanceInr(String apiKey) {
        Map<?, ?> available = (Map<?, ?>) get(apiKey, "/v1/balance").body().get("available");
        Object inr = available.get("inr");
        return inr == null ? 0 : ((Number) inr).longValue();
    }

    protected boolean ledgerBalanced() {
        return (Boolean) get(KEY_A, "/v1/ledger/reconciliation").body().get("ok");
    }
}
