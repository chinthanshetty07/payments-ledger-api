# Payments Ledger API

[![CI](https://github.com/chinthanshetty07/payments-ledger-api/actions/workflows/ci.yml/badge.svg)](https://github.com/chinthanshetty07/payments-ledger-api/actions/workflows/ci.yml)

A small payments API in Java 21 and Spring Boot: payment intents (authorise, capture, cancel,
refund), **idempotency keys**, a **double-entry ledger**, and **signed webhooks** delivered from a
transactional outbox. Every test runs against a real PostgreSQL, including concurrency tests.

> **Independent learning project.** I built it after applying to Stripe's New Grad role, to work
> through ideas from public payment-API documentation (idempotency keys, payment intents, signed
> webhooks). It is not affiliated with or endorsed by Stripe and contains none of their code.
> The card network is simulated with test tokens, and no real card data exists anywhere.
> Built with Claude Code as a pair programmer.

## The four things it gets right

**1. A retry never charges twice.** `POST` endpoints accept an `Idempotency-Key`. The first request
claims the key in its own committed transaction, runs, and stores its response; a retry with the
same key and body gets that stored response back (`Idempotent-Replayed: true`) without running again.
- Same key, **different** body → `422 idempotency_key_reused`.
- Same key arriving **while the first is still running** → `409 idempotency_in_progress`, never a second execution.
- Errors are stored too, so a retried decline returns the same decline.
- If the server crashes mid-request, the claim goes stale after 60 s and one retry can take it over (compare-and-set).

**2. Two requests can't both win a state change.** Payment intents carry a `version`; every transition
is `UPDATE … WHERE id = ? AND version = ?`. Under PostgreSQL row locking, eight parallel captures
of one authorisation produce exactly one capture and seven `409 concurrent_update` or invalid-state
errors. Ten parallel refunds can never exceed the captured amount (there is also a `CHECK` constraint).

**3. Money is a ledger, not a number.** Balances are never stored; they are the sum of immutable
entries, and every transaction must sum to zero per currency or it is refused. Amounts are integer
minor units (paise, cents), never floating point. `GET /v1/ledger/reconciliation` independently re-checks
every transaction.

| Event | Entries (₹500.00 capture, illustrative fee 2.9% + 30) |
|---|---|
| Authorise | none: a hold is not a money movement |
| Capture 50,000 | `network:card_clearing −50,000`, `merchant:m_a:balance +48,520`, `platform:fees +1,480` |
| Refund 15,000 | `merchant:m_a:balance −15,000`, `network:card_clearing +15,000` |

**4. Webhooks are never lost and never lie.** Events are written in the **same transaction** as the
state change (transactional outbox), so an event exists if and only if the change committed. A
dispatcher claims due deliveries with `FOR UPDATE SKIP LOCKED` plus a lease (so several workers never
send the same one, and no lock is held during HTTP), retries with exponential backoff, and marks a
delivery failed after the attempt limit. Each request is signed:

```
Webhook-Signature: t=1793280000,v1=<hex HMAC-SHA256 of "1793280000.<body>">
```

`WebhookSigner.verify` is what a receiver runs: constant-time comparison, rejecting timestamps older
than 5 minutes to stop replays. Delivery is at-least-once; receivers de-duplicate on `Webhook-Id`.

## API

All `/v1` calls need `Authorization: Bearer <api key>`. Keys are stored only as SHA-256 hashes.
Two demo merchants are seeded with the public demo keys `demo_key_merchant_a` and `demo_key_merchant_b`.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/payment_intents` | `{amount, currency: inr\|usd\|eur\|gbp, capture_method: automatic\|manual}` |
| GET | `/v1/payment_intents/{id}` | Another merchant's id returns 404, the same as a missing one |
| POST | `/v1/payment_intents/{id}/confirm` | `{payment_method: pm_card_visa \| pm_card_declined \| pm_card_insufficient_funds \| …}`; a decline returns `402` with the updated intent |
| POST | `/v1/payment_intents/{id}/capture` | Optional `{amount_to_capture}` up to the authorised amount |
| POST | `/v1/payment_intents/{id}/cancel` | Any state before capture |
| POST | `/v1/refunds` | `{payment_intent, amount?}`; partial or full; the fee is not returned |
| GET | `/v1/balance` | Derived from ledger entries |
| POST | `/v1/webhook_endpoints` | `{url}`; returns the signing secret once |
| GET | `/v1/ledger/reconciliation` | Every transaction balanced, ledger nets to zero |

Errors use RFC 9457 problem JSON with a stable `code`, e.g. `payment_intent_unexpected_state`,
`amount_too_large`, `charge_already_refunded`, `card_declined`.

## Run it

```bash
docker compose up --build          # API on :8080 with PostgreSQL
./scripts/demo.sh                  # create -> decline -> confirm -> capture (retried) -> refund -> reconcile
```

Tests need a PostgreSQL (`DATABASE_URL`). Without a local JDK:

```bash
docker network create pay && docker run -d --name pg --network pay -e POSTGRES_DB=payments \
  -e POSTGRES_USER=payments -e POSTGRES_PASSWORD=payments-local-dev postgres:16-alpine
docker run --rm --network pay -v "$PWD":/src -w /src -e DATABASE_URL=jdbc:postgresql://pg:5432/payments \
  maven:3.9-eclipse-temurin-21 mvn -B verify
```

## Tests (23, all against PostgreSQL)

- **Flows:** automatic capture and the exact fee/balance; manual capture with partial capture and two
  partial refunds; over-refund rejected with no side effects; decline then retry with another card;
  invalid transitions; cancel moves no money; merchant isolation and API-key checks; validation.
- **Idempotency and concurrency:** replay instead of double charge; key reuse with a different body;
  replayed errors; per-merchant key scope; 8 parallel captures → exactly one; 8 parallel creates with
  one key → one payment; 10 parallel refunds never exceed the captured amount.
- **Webhooks:** signed delivery after two failures with signature verification (and tampered body /
  wrong secret rejected); marked failed after max attempts; a leased delivery isn't handed to a second
  worker; no event when a state change fails; stale or malformed signatures rejected.
- **Ledger rules:** unbalanced, cross-currency, zero-amount and single-entry transactions refused; fee rounding.

CI runs the tests on a PostgreSQL service, builds the image, then runs the demo against Docker Compose.

## What I'd do next

- Move the outbox dispatcher to its own process, add a dead-letter view and a "resend event" endpoint.
- Partition the idempotency table by time and expire keys after 24 hours.
- Multi-currency settlement and payouts (merchant balance → bank), with pending vs available balances.
- Encrypt webhook secrets at rest; rotate them with an overlap window where both sign.
