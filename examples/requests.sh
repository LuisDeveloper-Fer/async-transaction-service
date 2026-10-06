#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
curl -i -X POST "$BASE/api/transactions" -H 'Content-Type: application/json' -H 'X-Correlation-ID: interview-01' --data '{"amount":125.5,"currency":"PEN","scenario":"SUCCESS"}'
curl -i "$BASE/api/transactions"
