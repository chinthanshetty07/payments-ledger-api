#!/usr/bin/env bash
# Walks through a payment end to end against a running instance (docker compose up).
set -euo pipefail
API=${API:-http://localhost:8080}
AUTH="Authorization: Bearer demo_key_merchant_a"   # public demo key, seeded by Flyway
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

echo "1. Create a manual-capture payment intent for ₹500.00 (50000 paise)"
PI=$(curl -s -X POST "$API/v1/payment_intents" -H "$AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-create-$RANDOM" -d '{"amount":50000,"currency":"inr","capture_method":"manual"}' | j 'd["id"]')
echo "   $PI"

echo "2. Confirm with a declining test card, then with a good one"
curl -s -X POST "$API/v1/payment_intents/$PI/confirm" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"payment_method":"pm_card_declined"}' | j '"   " + str(d["status"]) + " " + d["code"] + " -> intent " + d["payment_intent"]["status"]'
curl -s -X POST "$API/v1/payment_intents/$PI/confirm" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"payment_method":"pm_card_visa"}' | j '"   " + d["status"]'

echo "3. Capture ₹400.00, retried with the same Idempotency-Key"
KEY="demo-capture-$RANDOM"
for i in 1 2; do
  curl -s -i -X POST "$API/v1/payment_intents/$PI/capture" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $KEY" -d '{"amount_to_capture":40000}' | grep -iE '^(HTTP|idempotent-replayed)' | tr -d '\r' | sed 's/^/   /'
done

echo "4. Refund ₹150.00"
curl -s -X POST "$API/v1/refunds" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"payment_intent\":\"$PI\",\"amount\":15000}" | j '"   " + d["id"] + " " + d["status"]'

echo "5. Balance and ledger check"
curl -s "$API/v1/balance" -H "$AUTH" | j '"   available: " + str(d["available"])'
curl -s "$API/v1/ledger/reconciliation" -H "$AUTH" | j '"   ledger ok: " + str(d["ok"]) + ", transactions: " + str(d["transactions_checked"])'
