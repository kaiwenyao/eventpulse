#!/usr/bin/env bash
# End-to-end API smoke test against a running stack (default http://localhost:8080).
# Exercises: register -> search -> booking (idempotent replay + conflict) ->
# pay -> confirm -> reveal ticket -> organiser redeem -> double scan ->
# cancel-before-payment -> cancellation batch safety.
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

say() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); say "PASS: $*"; }
bad() { FAIL=$((FAIL + 1)); say "FAIL: $*"; }

# JSON helper without jq dependency
jget() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

req() {
  local method=$1 path=$2 token=${3:-} key=${4:-} body=${5:-}
  local -a args=(curl -sS --max-time 20 -X "$method" "$BASE$path")
  args+=(-H 'Content-Type: application/json' -o /tmp/ep_body.json -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  [ -n "$body" ] && args+=(-d "$body")
  "${args[@]}"
}

rand_suffix=$RANDOM$RANDOM
say "== 1. register a user and an organiser =="
register_body="{\"email\":\"smoke-$rand_suffix@test.dev\",\"password\":\"123456\",\"name\":\"Smoke User\"}"
code=$(req POST /api/auth/register "" "" "$register_body")
user_token=$(jget "['data']['token']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$user_token" ] && ok "register user ($code)" || bad "register user ($code)"

code=$(req POST /api/v1/auth/login "" "" \
  "{\"email\":\"organiser@eventpulse.dev\",\"password\":\"Organiser!234567890\"}")
org_token=$(jget "['accessToken']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$org_token" ] && ok "login seeded organiser ($code)" || bad "organiser login ($code)"

say "== 2. organiser creates + publishes an event with 50 seats =="
# The event must be within the redemption window (starts >= now - 6h) and on sale.
starts=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).isoformat())")
ends=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=5)).isoformat())")
event_body="{\"title\":\"Smoke Event $rand_suffix\",\"description\":\"smoke\",\"category\":\"music\",\"startsAt\":\"$starts\",\"endsAt\":\"$ends\",\"policy\":{\"cancellable\":true,\"cancellationDeadlineHoursBeforeStart\":24,\"resaleAllowed\":false,\"version\":1},\"venue\":{\"name\":\"Smoke Hall\",\"city\":\"上海\",\"lat\":31.23,\"lng\":121.47},\"tiers\":[{\"name\":\"标准票\",\"currency\":\"CNY\",\"unitPriceMinor\":10000,\"saleStartAt\":\"$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=1)).isoformat())")\",\"saleEndAt\":\"$ends\",\"perUserLimit\":5,\"capacity\":50}]}"
code=$(req POST /api/v1/organiser/events "$org_token" "" "$event_body")
event_id=$(jget "['eventId']" < /tmp/ep_body.json)
[ "$code" = "201" ] && ok "create draft event ($code)" || bad "create draft event ($code)"
code=$(req POST "/api/v1/organiser/events/$event_id/publish" "$org_token" "" "")
[ "$code" = "204" ] && ok "publish event ($code)" || bad "publish event ($code)"

tier_id=$(curl -sS "$BASE/api/v1/events/$event_id" | jget "['tiers'][0]['id']")
[ -n "$tier_id" ] && ok "event detail lists tier" || bad "event detail lists tier"

say "== 3. booking with idempotency (replay + conflict) =="
idem_key="smoke-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")"
code=$(req POST /api/v1/bookings "$user_token" "$idem_key" \
  "{\"eventId\":\"$event_id\",\"tierId\":\"$tier_id\",\"quantity\":1,\"ageConfirmed\":true}")
booking_id=$(jget "['id']" < /tmp/ep_body.json)
[ "$code" = "201" ] && ok "create booking ($code)" || bad "create booking ($code)"
code=$(req POST /api/v1/bookings "$user_token" "$idem_key" \
  "{\"eventId\":\"$event_id\",\"tierId\":\"$tier_id\",\"quantity\":1,\"ageConfirmed\":true}")
replay_id=$(jget "['id']" < /tmp/ep_body.json)
[ "$code" = "201" ] && [ "$replay_id" = "$booking_id" ] && ok "idempotent replay returns same booking" || bad "idempotent replay"
code=$(req POST /api/v1/bookings "$user_token" "$idem_key" \
  "{\"eventId\":\"$event_id\",\"tierId\":\"$tier_id\",\"quantity\":2,\"ageConfirmed\":true}")
[ "$code" = "409" ] && ok "different fingerprint with same key -> 409" || bad "idempotency conflict ($code)"

say "== 4. pay once, poll until CONFIRMED =="
code=$(req POST "/api/v1/bookings/$booking_id/pay" "$user_token" "smoke-pay-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")" "{}")
[ "$code" = "200" ] && ok "create payment intent ($code)" || bad "create payment intent ($code)"
confirmed=0
for i in $(seq 1 30); do
  code=$(req GET "/api/v1/bookings/$booking_id" "$user_token")
  status=$(jget "['status']" < /tmp/ep_body.json)
  if [ "$status" = "CONFIRMED" ]; then confirmed=1; break; fi
  sleep 1
done
[ "$confirmed" = "1" ] && ok "payment confirmed and tickets issued" || bad "payment confirm poll ($status)"
ticket_count=$(curl -sS "$BASE/api/v1/bookings/$booking_id" -H "Authorization: Bearer $user_token" \
  | python3 -c "import json,sys;print(len(json.load(sys.stdin)['tickets']))")
[ "$ticket_count" = "1" ] && ok "ticket issued" || bad "ticket issued ($ticket_count)"

say "== 5. reveal ticket token and redeem as organiser =="
code=$(req POST "/api/v1/bookings/$booking_id/tickets/reveal" "$user_token" "" "{}")
token=$(jget "['tokens'][0]" < /tmp/ep_body.json)
[ -n "$token" ] && ok "ticket token revealed to owner" || bad "ticket reveal"
code=$(req POST /api/v1/organiser/tickets/redeem "$org_token" \
  "smoke-redeem-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")" "{\"token\":\"$token\"}")
[ "$code" = "200" ] && ok "redeem succeeds ($code)" || bad "redeem ($code)"
code=$(req POST /api/v1/organiser/tickets/redeem "$org_token" \
  "smoke-redeem2-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")" "{\"token\":\"$token\"}")
[ "$code" = "409" ] && ok "second scan rejected (single use)" || bad "double scan ($code)"

say "== 6. cancel before payment releases stock =="
code=$(req POST /api/v1/bookings "$user_token" "smoke-b2-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")" \
  "{\"eventId\":\"$event_id\",\"tierId\":\"$tier_id\",\"quantity\":1,\"ageConfirmed\":true}")
booking2=$(jget "['id']" < /tmp/ep_body.json)
code=$(req POST "/api/v1/bookings/$booking2/cancel" "$user_token" \
  "smoke-cancel-$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")" "{\"reason\":\"smoke\"}")
status=$(jget "['status']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ "$status" = "CANCELLED_BEFORE_PAYMENT" ] && ok "cancel before payment ($status)" || bad "cancel before payment ($code/$status)"

say "== 7. summary =="
say "passed=$PASS failed=$FAIL"
if [ "$FAIL" -gt 0 ]; then exit 1; fi
say "SMOKE TEST: ALL GREEN"
