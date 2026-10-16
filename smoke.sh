#!/usr/bin/env bash
# Replays the brief's requests against a running stack (./run.sh) and checks every response.
#
#   ./smoke.sh                              security off (the default)
#   SECURITY_ENABLED=true ./smoke.sh        fetches admin and user tokens first
#   BASE_URL=http://host:9999 ./smoke.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9999}"
USER_ID="g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V"
BRIEF_POST='{
  "user_id": "g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V",
  "accreditation_type": "BY_INCOME",
  "document": {
    "name": "2018.pdf",
    "mime_type": "application/pdf",
    "content": "ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg=="
  }
}'
AUTH=()
USER_AUTH=()
# ${ARRAY[@]+"${ARRAY[@]}"} expands an empty array safely under set -u, also on macOS's bash 3.2
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

failures=0
BODY=""
STATUS=""

# call METHOD PATH [BODY] [extra curl args...]: sets STATUS and BODY
call() {
  local method=$1 path=$2 data=${3:-}; shift 3 || shift $#
  local args=(-s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json')
  [[ -n "$data" ]] && args+=(-d "$data")
  STATUS=$(curl "${args[@]}" ${1+"$@"})
  BODY=$(cat "$BODY_FILE")
}

# expect STATUS DESCRIPTION
expect() {
  if [[ "$STATUS" == "$1" ]]; then
    printf '  ok    %-62s %s\n' "$2" "$STATUS"
  else
    printf '  FAIL  %-62s expected %s, got %s\n        %s\n' "$2" "$1" "$STATUS" "$BODY"
    failures=$((failures + 1))
  fi
}

field() { # extracts a top-level string field from BODY, without needing jq
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" <<<"$BODY"
}

# wait_for_status ID STATUS: the client view is eventually consistent, normally within a second
wait_for_status() {
  for _ in $(seq 1 30); do
    call GET "/user/$USER_ID/accreditation" "" ${USER_AUTH[@]+"${USER_AUTH[@]}"}
    if grep -q "\"$1\":{[^}]*\"status\":\"$2\"" <<<"$BODY"; then
      expect 200 "GET shows $2"
      return
    fi
    sleep 0.5
  done
  STATUS="timeout"; expect 200 "GET shows $2"
}

echo "Smoke test against $BASE_URL"

if [[ "${SECURITY_ENABLED:-false}" == "true" ]]; then
  call POST /auth/token '{"role":"ADMIN","subject":"smoke-admin"}'
  AUTH=(-H "Authorization: Bearer $(field access_token)")
  call POST /auth/token "{\"role\":\"USER\",\"user_id\":\"$USER_ID\"}"
  USER_AUTH=(-H "Authorization: Bearer $(field access_token)")
  call POST /user/accreditation "$BRIEF_POST"
  expect 401 "POST without a token is rejected"
else
  USER_AUTH=(${AUTH[@]+"${AUTH[@]}"})
fi

call POST /user/accreditation "$BRIEF_POST" ${AUTH[@]+"${AUTH[@]}"}
expect 201 "POST the brief's request"
ID=$(field accreditation_id)
echo "        accreditation_id = $ID"
if [[ -z "$ID" ]]; then
  echo "No accreditation was created: if a PENDING one is left from an earlier run, use ./run.sh clean" >&2
  exit 1
fi
wait_for_status "$ID" PENDING

call POST /user/accreditation "$BRIEF_POST" ${AUTH[@]+"${AUTH[@]}"}
expect 409 "a second POST while one is PENDING (rule 4c)"

call PUT "/user/accreditation/$ID" '{"outcome":"CONFIRMED"}' ${AUTH[@]+"${AUTH[@]}"}
expect 200 "PUT CONFIRMED"
wait_for_status "$ID" CONFIRMED

call PUT "/user/accreditation/$ID" '{"outcome":"CONFIRMED"}' ${AUTH[@]+"${AUTH[@]}"}
expect 200 "PUT CONFIRMED again is a no-op"

call PUT "/user/accreditation/$ID" '{"outcome":"FAILED"}' ${AUTH[@]+"${AUTH[@]}"}
expect 200 "PUT FAILED (revoking a confirmation)"
wait_for_status "$ID" FAILED

call PUT "/user/accreditation/$ID" '{"outcome":"CONFIRMED"}' ${AUTH[@]+"${AUTH[@]}"}
expect 409 "a FAILED accreditation cannot change (rule 4a)"

call GET "/user/accreditation/$ID/history" "" ${AUTH[@]+"${AUTH[@]}"}
expect 200 "history lists every change"
echo "        $BODY"

call POST /user/accreditation '{"user_id":"has spaces","accreditation_type":"BY_LUCK","document":{"name":"x","mime_type":"text/html","content":"!"}}' ${AUTH[@]+"${AUTH[@]}"}
expect 400 "an invalid body lists every invalid field"

call PUT /user/accreditation/not-a-uuid '{"outcome":"CONFIRMED"}' ${AUTH[@]+"${AUTH[@]}"}
expect 404 "PUT on an unknown accreditation"

call GET "/user/$USER_ID/accreditation" "" ${USER_AUTH[@]+"${USER_AUTH[@]}"}
expect 200 "final client view"
echo "        $BODY"

if (( failures > 0 )); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
