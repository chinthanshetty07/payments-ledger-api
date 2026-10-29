package in.chinthanshetty.payments.intents;

import static in.chinthanshetty.payments.intents.PaymentIntent.CANCELED;
import static in.chinthanshetty.payments.intents.PaymentIntent.REQUIRES_CAPTURE;
import static in.chinthanshetty.payments.intents.PaymentIntent.REQUIRES_CONFIRMATION;
import static in.chinthanshetty.payments.intents.PaymentIntent.REQUIRES_PAYMENT_METHOD;
import static in.chinthanshetty.payments.intents.PaymentIntent.SUCCEEDED;

import in.chinthanshetty.payments.Ids;
import in.chinthanshetty.payments.api.ApiException;
import in.chinthanshetty.payments.ledger.LedgerService;
import in.chinthanshetty.payments.ledger.LedgerService.Entry;
import in.chinthanshetty.payments.webhooks.WebhookService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment intent state machine. Each method is one database transaction that changes state,
 * posts to the ledger and writes webhook events together, so they can never disagree.
 *
 * <pre>
 * requires_confirmation --confirm ok--> requires_capture (manual) | succeeded (automatic)
 *                       --confirm declined--> requires_payment_method --confirm ok--> ...
 * requires_capture --capture--> succeeded        any pre-capture state --cancel--> canceled
 * succeeded --refund (partial or full, up to amount captured)--> succeeded
 * </pre>
 */
@Service
public class PaymentIntentService {
    public record ConfirmOutcome(Map<String, Object> intent, String declineCode) {}

    private static final Set<String> CONFIRMABLE = Set.of(REQUIRES_CONFIRMATION, REQUIRES_PAYMENT_METHOD);
    private static final Set<String> CANCELABLE = Set.of(REQUIRES_CONFIRMATION, REQUIRES_PAYMENT_METHOD, REQUIRES_CAPTURE);

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final WebhookService webhooks;
    private final CardNetwork network;
    private final Clock clock;

    public PaymentIntentService(JdbcClient jdbc, LedgerService ledger, WebhookService webhooks, CardNetwork network, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.webhooks = webhooks;
        this.network = network;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> create(String merchantId, long amount, String currency, String captureMethod) {
        String id = Ids.next("pi");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                INSERT INTO payment_intents (id, merchant_id, amount, currency, capture_method, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, merchantId, amount, currency, captureMethod, REQUIRES_CONFIRMATION, now, now).update();
        return view(load(merchantId, id));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String merchantId, String id) {
        return view(load(merchantId, id));
    }

    @Transactional
    public ConfirmOutcome confirm(String merchantId, String id, String paymentMethod) {
        PaymentIntent pi = load(merchantId, id);
        requireStatus(pi, CONFIRMABLE, "confirm");
        CardNetwork.Authorization auth;
        try {
            auth = network.authorize(paymentMethod, pi.amount(), pi.currency());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("payment_method_invalid", "Unknown test payment method: " + paymentMethod);
        }

        if (!auth.approved()) {
            update(pi, REQUIRES_PAYMENT_METHOD, 0, 0, auth.declineCode());
            PaymentIntent failed = load(merchantId, id);
            webhooks.enqueue(merchantId, "payment_intent.payment_failed", view(failed));
            // The decline is recorded and committed; the controller turns it into a 402 for the caller.
            return new ConfirmOutcome(view(failed), auth.declineCode());
        }
        if ("manual".equals(pi.captureMethod())) {
            update(pi, REQUIRES_CAPTURE, 0, 0, null);
            PaymentIntent held = load(merchantId, id);
            webhooks.enqueue(merchantId, "payment_intent.amount_capturable_updated", view(held));
            return new ConfirmOutcome(view(held), null);
        }
        return new ConfirmOutcome(captureInternal(pi, pi.amount()), null);
    }

    @Transactional
    public Map<String, Object> capture(String merchantId, String id, Long amountToCapture) {
        PaymentIntent pi = load(merchantId, id);
        requireStatus(pi, Set.of(REQUIRES_CAPTURE), "capture");
        long amount = amountToCapture == null ? pi.amount() : amountToCapture;
        if (amount <= 0 || amount > pi.amount()) {
            throw ApiException.badRequest("amount_too_large",
                    "amount_to_capture must be between 1 and the authorized amount (" + pi.amount() + ")");
        }
        return captureInternal(pi, amount);
    }

    private Map<String, Object> captureInternal(PaymentIntent pi, long amount) {
        update(pi, SUCCEEDED, amount, 0, null);
        long fee = Fees.forCapture(amount);
        List<Entry> entries = fee == amount
                ? List.of(new Entry(LedgerService.CARD_CLEARING, pi.currency(), -amount),
                          new Entry(LedgerService.FEES, pi.currency(), fee))
                : List.of(new Entry(LedgerService.CARD_CLEARING, pi.currency(), -amount),
                          new Entry(LedgerService.merchantBalance(pi.merchantId()), pi.currency(), amount - fee),
                          new Entry(LedgerService.FEES, pi.currency(), fee));
        ledger.post("capture", pi.id(), entries);
        PaymentIntent done = load(pi.merchantId(), pi.id());
        webhooks.enqueue(pi.merchantId(), "payment_intent.succeeded", view(done));
        return view(done);
    }

    @Transactional
    public Map<String, Object> cancel(String merchantId, String id) {
        PaymentIntent pi = load(merchantId, id);
        requireStatus(pi, CANCELABLE, "cancel");
        update(pi, CANCELED, 0, 0, pi.lastErrorCode());
        PaymentIntent canceled = load(merchantId, id);
        webhooks.enqueue(merchantId, "payment_intent.canceled", view(canceled));
        return view(canceled);
    }

    @Transactional
    public Map<String, Object> refund(String merchantId, String paymentIntentId, Long requested) {
        PaymentIntent pi = load(merchantId, paymentIntentId);
        requireStatus(pi, Set.of(SUCCEEDED), "refund");
        long amount = requested == null ? pi.refundable() : requested;
        if (pi.refundable() == 0) {
            throw ApiException.badRequest("charge_already_refunded", "This payment has already been fully refunded");
        }
        if (amount <= 0 || amount > pi.refundable()) {
            throw ApiException.badRequest("amount_too_large",
                    "Refund amount must be between 1 and the refundable amount (" + pi.refundable() + ")");
        }
        update(pi, SUCCEEDED, pi.amountCaptured(), pi.amountRefunded() + amount, pi.lastErrorCode());

        String refundId = Ids.next("re");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("INSERT INTO refunds (id, payment_intent_id, amount, created_at) VALUES (?, ?, ?, ?)")
                .params(refundId, pi.id(), amount, now).update();
        // The fee is not returned on refund, so the merchant's balance carries the full refund.
        ledger.post("refund", refundId, List.of(
                new Entry(LedgerService.merchantBalance(merchantId), pi.currency(), -amount),
                new Entry(LedgerService.CARD_CLEARING, pi.currency(), amount)));

        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("id", refundId);
        refund.put("object", "refund");
        refund.put("payment_intent", pi.id());
        refund.put("amount", amount);
        refund.put("currency", pi.currency());
        refund.put("status", "succeeded");
        refund.put("created", now.toInstant().getEpochSecond());
        webhooks.enqueue(merchantId, "charge.refunded", Map.of("refund", refund, "payment_intent", view(load(merchantId, pi.id()))));
        return refund;
    }

    /** Optimistic lock: the UPDATE only applies if nobody changed the row since we read it. */
    private void update(PaymentIntent pi, String status, long captured, long refunded, String errorCode) {
        int rows = jdbc.sql("""
                UPDATE payment_intents
                SET status = ?, amount_captured = ?, amount_refunded = ?, last_error_code = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?""")
                .params(status, captured, refunded, errorCode, Timestamp.from(clock.instant()), pi.id(), pi.version())
                .update();
        if (rows == 0) {
            throw ApiException.conflict("concurrent_update",
                    "This payment intent was changed by another request at the same time; fetch it and retry");
        }
    }

    private static void requireStatus(PaymentIntent pi, Set<String> allowed, String action) {
        if (!allowed.contains(pi.status())) {
            throw ApiException.badRequest("payment_intent_unexpected_state",
                    "You cannot " + action + " a payment intent with status " + pi.status());
        }
    }

    private PaymentIntent load(String merchantId, String id) {
        // Scoped by merchant: another merchant's id is indistinguishable from a missing one.
        return jdbc.sql("SELECT * FROM payment_intents WHERE id = ? AND merchant_id = ?")
                .params(id, merchantId)
                .query(PaymentIntentService::map)
                .optional()
                .orElseThrow(() -> ApiException.notFound("payment intent: " + id));
    }

    private static PaymentIntent map(ResultSet rs, int n) throws SQLException {
        return new PaymentIntent(rs.getString("id"), rs.getString("merchant_id"), rs.getLong("amount"),
                rs.getString("currency"), rs.getString("capture_method"), rs.getString("status"),
                rs.getLong("amount_captured"), rs.getLong("amount_refunded"), rs.getString("last_error_code"),
                rs.getInt("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    static Map<String, Object> view(PaymentIntent pi) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", pi.id());
        v.put("object", "payment_intent");
        v.put("amount", pi.amount());
        v.put("currency", pi.currency());
        v.put("status", pi.status());
        v.put("capture_method", pi.captureMethod());
        v.put("amount_capturable", REQUIRES_CAPTURE.equals(pi.status()) ? pi.amount() : 0);
        v.put("amount_captured", pi.amountCaptured());
        v.put("amount_refunded", pi.amountRefunded());
        v.put("last_payment_error", pi.lastErrorCode() == null ? null : Map.of("code", pi.lastErrorCode()));
        v.put("created", pi.createdAt().getEpochSecond());
        return v;
    }
}
