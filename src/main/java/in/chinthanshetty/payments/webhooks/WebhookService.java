package in.chinthanshetty.payments.webhooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.chinthanshetty.payments.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox. Events and their pending deliveries are written in the same database
 * transaction as the state change, so an event exists if and only if the change committed:
 * no "charged but never notified" and no "notified about something that rolled back".
 */
@Service
public class WebhookService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> createEndpoint(String merchantId, String url) {
        String id = Ids.next("we");
        String secret = "whsec_" + Ids.random(32);
        jdbc.sql("INSERT INTO webhook_endpoints (id, merchant_id, url, secret, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, merchantId, url, secret, Timestamp.from(clock.instant())).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("url", url);
        out.put("secret", secret); // shown once, at creation
        return out;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String merchantId, String type, Object object) {
        String eventId = Ids.next("evt");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", type);
        payload.put("created", clock.instant().getEpochSecond());
        payload.put("data", Map.of("object", object));
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("INSERT INTO events (id, merchant_id, type, payload, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(eventId, merchantId, type, write(payload), now).update();
        List<String> endpoints = jdbc.sql("SELECT id FROM webhook_endpoints WHERE merchant_id = ?")
                .param(merchantId).query(String.class).list();
        for (String endpoint : endpoints) {
            jdbc.sql("INSERT INTO webhook_deliveries (event_id, endpoint_id, status, next_attempt_at) VALUES (?, ?, 'pending', ?)")
                    .params(eventId, endpoint, now).update();
        }
        return eventId;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
