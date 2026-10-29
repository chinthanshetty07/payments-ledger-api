package in.chinthanshetty.payments.api;

import in.chinthanshetty.payments.idempotency.IdempotencyService;
import in.chinthanshetty.payments.intents.PaymentIntentService;
import in.chinthanshetty.payments.ledger.LedgerService;
import in.chinthanshetty.payments.webhooks.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentsController {
    private static final String KEY = "Idempotency-Key";

    public record CreateIntent(
            @NotNull @Min(50) @Max(99_999_999) Long amount,
            @NotBlank @Pattern(regexp = "inr|usd|eur|gbp", message = "must be one of inr, usd, eur, gbp") String currency,
            @Pattern(regexp = "automatic|manual", message = "must be automatic or manual") String captureMethod) {}

    public record Confirm(@NotBlank String paymentMethod) {}

    public record Capture(@Min(1) Long amountToCapture) {}

    public record CreateRefund(@NotBlank String paymentIntent, @Min(1) Long amount) {}

    public record CreateEndpoint(@NotBlank @Pattern(regexp = "https?://.+", message = "must be an http(s) URL") String url) {}

    private final PaymentIntentService intents;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;
    private final WebhookService webhooks;

    public PaymentsController(PaymentIntentService intents, IdempotencyService idempotency, LedgerService ledger, WebhookService webhooks) {
        this.intents = intents;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.webhooks = webhooks;
    }

    @PostMapping("/v1/payment_intents")
    public ResponseEntity<Object> create(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant,
                                         @RequestHeader(value = KEY, required = false) String key,
                                         @Valid @RequestBody CreateIntent body) {
        return idempotency.run(merchant, key, "POST", "/v1/payment_intents", body, () ->
                ResponseEntity.ok(intents.create(merchant, body.amount(), body.currency(),
                        body.captureMethod() == null ? "automatic" : body.captureMethod())));
    }

    @GetMapping("/v1/payment_intents/{id}")
    public Map<String, Object> get(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant, @PathVariable String id) {
        return intents.get(merchant, id);
    }

    @PostMapping("/v1/payment_intents/{id}/confirm")
    public ResponseEntity<Object> confirm(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant, @PathVariable String id,
                                          @RequestHeader(value = KEY, required = false) String key,
                                          @Valid @RequestBody Confirm body) {
        return idempotency.run(merchant, key, "POST", "/v1/payment_intents/" + id + "/confirm", body, () -> {
            var outcome = intents.confirm(merchant, id, body.paymentMethod());
            if (outcome.declineCode() != null) {
                // 402 like card APIs commonly use; the intent (now requires_payment_method) is in the body.
                ApiException declined = new ApiException(HttpStatus.PAYMENT_REQUIRED, outcome.declineCode(), "The card was declined");
                Map<String, Object> problem = new LinkedHashMap<>(declined.body());
                problem.put("payment_intent", outcome.intent());
                throw new DeclinedException(declined, problem);
            }
            return ResponseEntity.ok(outcome.intent());
        });
    }

    @PostMapping("/v1/payment_intents/{id}/capture")
    public ResponseEntity<Object> capture(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant, @PathVariable String id,
                                          @RequestHeader(value = KEY, required = false) String key,
                                          @Valid @RequestBody(required = false) Capture body) {
        Capture b = body == null ? new Capture(null) : body;
        return idempotency.run(merchant, key, "POST", "/v1/payment_intents/" + id + "/capture", b, () ->
                ResponseEntity.ok(intents.capture(merchant, id, b.amountToCapture())));
    }

    @PostMapping("/v1/payment_intents/{id}/cancel")
    public ResponseEntity<Object> cancel(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant, @PathVariable String id,
                                         @RequestHeader(value = KEY, required = false) String key) {
        return idempotency.run(merchant, key, "POST", "/v1/payment_intents/" + id + "/cancel", null, () ->
                ResponseEntity.ok(intents.cancel(merchant, id)));
    }

    @PostMapping("/v1/refunds")
    public ResponseEntity<Object> refund(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant,
                                         @RequestHeader(value = KEY, required = false) String key,
                                         @Valid @RequestBody CreateRefund body) {
        return idempotency.run(merchant, key, "POST", "/v1/refunds", body, () ->
                ResponseEntity.ok(intents.refund(merchant, body.paymentIntent(), body.amount())));
    }

    @GetMapping("/v1/balance")
    public Map<String, Object> balance(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant) {
        return Map.of("object", "balance", "available", ledger.balances(LedgerService.merchantBalance(merchant)));
    }

    @PostMapping("/v1/webhook_endpoints")
    public Map<String, Object> createEndpoint(@RequestAttribute(ApiKeyFilter.MERCHANT) String merchant,
                                              @Valid @RequestBody CreateEndpoint body) {
        return webhooks.createEndpoint(merchant, body.url());
    }

    @GetMapping("/v1/ledger/reconciliation")
    public Map<String, Object> reconcile() {
        return ledger.reconcile();
    }

    /** A decline whose body carries the updated intent. Extends ApiException so idempotency stores it. */
    static class DeclinedException extends ApiException {
        private final Map<String, Object> problem;

        DeclinedException(ApiException base, Map<String, Object> problem) {
            super(base.status(), base.code(), base.getMessage());
            this.problem = problem;
        }

        @Override
        public Map<String, Object> body() {
            return problem;
        }
    }
}
