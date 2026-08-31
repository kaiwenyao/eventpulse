#!/usr/bin/env bash
# End-to-end API smoke test against a running stack (default http://localhost:8080).
# Exercises: register -> list events -> organiser publish -> book -> Kafka notification -> cancel.
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

say() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); say "PASS: $*"; }
bad() { FAIL=$((FAIL + 1)); say "FAIL: $*"; }

jget() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

req() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local -a args=(curl -sS --max-time 20 -X "$method" "$BASE$path")
  args+=(-H 'Content-Type: application/json' -o /tmp/ep_body.json -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  "${args[@]}"
}

rand_suffix=$RANDOM$RANDOM
say "== 1. register a user and login the seeded organiser =="
register_body="{\"email\":\"smoke-$rand_suffix@test.dev\",\"password\":\"123456\",\"name\":\"Smoke User\"}"
code=$(req POST /api/auth/register "" "$register_body")
user_token=$(jget "['data']['token']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$user_token" ] && ok "register user ($code)" || bad "register user ($code)"

code=$(req POST /api/auth/login "" \
  "{\"email\":\"organiser@eventpulse.dev\",\"password\":\"Organiser123456\"}")
org_token=$(jget "['data']['token']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$org_token" ] && ok "login seeded organiser ($code)" || bad "organiser login ($code)"

say "== 2. public event list =="
code=$(req GET /api/events)
listed=$(jget "['data'][0]['id']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$listed" ] && ok "list events ($code)" || bad "list events ($code)"

say "== 3. organiser publishes an event =="
starts=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=7)).isoformat())")
event_body="{\"title\":\"Smoke Event $rand_suffix\",\"description\":\"smoke\",\"category\":\"music\",\"city\":\"上海\",\"startsAt\":\"$starts\",\"priceCents\":10000,\"capacity\":50}"
code=$(req POST /api/events "$org_token" "$event_body")
event_id=$(jget "['data']['id']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$event_id" ] && ok "create event ($code)" || bad "create event ($code)"

code=$(req GET "/api/events/$event_id")
title=$(jget "['data']['title']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ -n "$title" ] && ok "event detail ($code)" || bad "event detail ($code)"

say "== 4. user books a seat =="
code=$(req POST /api/bookings "$user_token" "{\"eventId\":$event_id,\"quantity\":1}")
booking_id=$(jget "['data']['id']" < /tmp/ep_body.json)
status=$(jget "['data']['status']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ "$status" = "CONFIRMED" ] && ok "create booking ($code)" || bad "create booking ($code/$status)"

say "== 5. Kafka consumer writes a notification =="
got_note=0
for _ in $(seq 1 20); do
  code=$(req GET /api/notifications "$user_token")
  count=$(python3 -c "import json;d=json.load(open('/tmp/ep_body.json'));print(len(d.get('data') or []))" 2>/dev/null)
  if [ "$code" = "200" ] && [ "${count:-0}" -ge 1 ]; then
    got_note=1
    break
  fi
  sleep 1
done
[ "$got_note" = "1" ] && ok "notification from Kafka" || bad "notification from Kafka"

say "== 6. cancel booking =="
code=$(req POST "/api/bookings/$booking_id/cancel" "$user_token")
status=$(jget "['data']['status']" < /tmp/ep_body.json)
[ "$code" = "200" ] && [ "$status" = "CANCELLED" ] && ok "cancel booking ($status)" || bad "cancel booking ($code/$status)"

say "== 7. summary =="
say "passed=$PASS failed=$FAIL"
if [ "$FAIL" -gt 0 ]; then exit 1; fi
say "SMOKE TEST: ALL GREEN"
