#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BUILD_DIR="${1:-${TMPDIR:-/tmp}/policyscript-core-verification}"
if [[ ! -d "$BUILD_DIR" ]]; then
  echo "Compiled core classes not found: $BUILD_DIR" >&2
  exit 2
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
PASS=0
FAIL=0
check() {
  local name="$1" condition="$2"
  if eval "$condition"; then
    echo "PASS  $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL  $name" >&2
    FAIL=$((FAIL + 1))
  fi
}

cat > "$TMP_DIR/equal-top.policy" <<'POLICY'
ALLOW student TO access computer_lab PRIORITY 5;
DENY student TO access computer_lab PRIORITY 5;
POLICY
set +e
java -cp "$BUILD_DIR:src/main/resources" com.policyscript.cli.PolicyScriptCli \
  "$TMP_DIR/equal-top.policy" --request student computer_lab Monday 12:00 \
  >"$TMP_DIR/equal-top.out" 2>&1
EQUAL_STATUS=$?
set -e
check "CLI exits with policy-error code for repeated matching priority" '[[ "$EQUAL_STATUS" -eq 4 ]]'
check "CLI reports priority conflict and no decision, not default DENY" 'grep -q "PRIORITY CONFLICT ERROR — ERROR\|PRIORITY CONFLICT — ERROR\|PRIORITY CONFLICT ? ERROR" "$TMP_DIR/equal-top.out" && grep -q "none (PRIORITY CONFLICT ERROR - no decision issued)" "$TMP_DIR/equal-top.out" && ! grep -q "none (default DENY)" "$TMP_DIR/equal-top.out"'

cat > "$TMP_DIR/distinct-priority.policy" <<'POLICY'
DENY student TO access computer_lab PRIORITY 10;
ALLOW student TO access computer_lab PRIORITY 1;
POLICY
set +e
java -cp "$BUILD_DIR:src/main/resources" com.policyscript.cli.PolicyScriptCli \
  "$TMP_DIR/distinct-priority.policy" --request student computer_lab Monday 12:00 \
  >"$TMP_DIR/distinct-priority.out" 2>&1
WINNER_STATUS=$?
set -e
check "CLI applies smaller numeric priority and returns success" '[[ "$WINNER_STATUS" -eq 0 ]] && grep -q "ACCESS GRANTED" "$TMP_DIR/distinct-priority.out" && grep -q "Winning policy: R2" "$TMP_DIR/distinct-priority.out"'


cat > "$TMP_DIR/repeated-lower-tier.policy" <<'POLICY'
ALLOW student TO access computer_lab PRIORITY 1;
DENY student TO access computer_lab PRIORITY 10;
ALLOW student TO access computer_lab PRIORITY 10;
POLICY
set +e
java -cp "$BUILD_DIR:src/main/resources" com.policyscript.cli.PolicyScriptCli \
  "$TMP_DIR/repeated-lower-tier.policy" --request student computer_lab Monday 12:00 \
  >"$TMP_DIR/repeated-lower-tier.out" 2>&1
LOWER_TIER_STATUS=$?
set -e
check "CLI rejects a repeated lower-priority tier even when priority one also matches" '[[ "$LOWER_TIER_STATUS" -eq 4 ]] && grep -q "PRIORITY CONFLICT" "$TMP_DIR/repeated-lower-tier.out" && grep -q "10" "$TMP_DIR/repeated-lower-tier.out" && grep -q "no decision issued" "$TMP_DIR/repeated-lower-tier.out"'

cat > "$TMP_DIR/disjoint-day.policy" <<'POLICY'
ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 5;
DENY student TO access computer_lab WHEN day = Tuesday PRIORITY 5;
POLICY
set +e
java -cp "$BUILD_DIR:src/main/resources" com.policyscript.cli.PolicyScriptCli \
  "$TMP_DIR/disjoint-day.policy" --request student computer_lab Monday 12:00 \
  >"$TMP_DIR/disjoint-day.out" 2>&1
DISJOINT_STATUS=$?
set -e
check "CLI does not report a conflict for equal priorities that cannot match the same request" '[[ "$DISJOINT_STATUS" -eq 0 ]] && grep -q "ACCESS GRANTED" "$TMP_DIR/disjoint-day.out" && ! grep -q "PRIORITY CONFLICT ERROR" "$TMP_DIR/disjoint-day.out"'

cat > "$TMP_DIR/no-request-conflict.policy" <<'POLICY'
ALLOW student TO access computer_lab PRIORITY 3;
DENY student TO access computer_lab PRIORITY 3;
POLICY
set +e
java -cp "$BUILD_DIR:src/main/resources" com.policyscript.cli.PolicyScriptCli \
  "$TMP_DIR/no-request-conflict.policy" >"$TMP_DIR/no-request-conflict.out" 2>&1
NO_REQUEST_STATUS=$?
set -e
check "CLI policy analysis reports conflicts even without a specific access request" '[[ "$NO_REQUEST_STATUS" -eq 4 ]] && grep -q "POLICY ANALYSIS ERROR" "$TMP_DIR/no-request-conflict.out"'

echo "CLI priority verification: $PASS passed, $FAIL failed."
[[ "$FAIL" -eq 0 ]]
