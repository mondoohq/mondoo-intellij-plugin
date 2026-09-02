#!/usr/bin/env bash
#
# End-to-end smoke test: launches a real IDE with the plugin and asserts on its log.
#
# The unit suite is deliberately IDE-free, so nothing in it can catch a plugin that
# fails to load, an extension point that does not resolve, or a language server that
# never starts. Those are exactly the regressions that reach users, and every one of
# them is visible in idea.log — so assert on it.
#
# Usage: scripts/smoke-test.sh [runGoLand|runIde|runAndroidStudio]
set -euo pipefail

TASK="${1:-runGoLand}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# A second IDE cannot start against a sandbox another instance is holding: it exits
# without writing idea.log, and the wait below then times out reporting that the
# language server never started — which is true but badly misleading.
if pgrep -f "plugins_${TASK}" >/dev/null 2>&1; then
  echo "FAIL: a sandbox IDE for $TASK is already running."
  echo "      Close it, or: pkill -f 'plugins_${TASK}'"
  exit 1
fi

PROBE="$(mktemp -d)/mondoo-smoke"
mkdir -p "$PROBE"
cat > "$PROBE/go.mod" <<'EOF'
module mondoo-smoke

go 1.22
EOF
# Deliberately vulnerable, and named as production code: the scanner drops security
# findings in test/fixture/example paths by default (secrets excepted), so a file
# called something like "testdata" would report nothing and the check would be a lie.
cat > "$PROBE/main.py" <<'EOF'
import os

def run(cmd):
    os.system(cmd)
EOF

LOG_DIR="$ROOT/.intellijPlatform/sandbox/mondoo-intellij-plugin/IU-2026.1.4/log_${TASK}"
rm -rf "$LOG_DIR"
LOG="$LOG_DIR/idea.log"

echo "Launching $TASK with a scratch project at $PROBE"
./gradlew "$TASK" -PmondooProbeProject="$PROBE,$PROBE/main.py" --console=plain >/tmp/mondoo-smoke.out 2>&1 &
GRADLE_PID=$!

cleanup() {
  pkill -f "plugins_${TASK}" 2>/dev/null || true
  kill "$GRADLE_PID" 2>/dev/null || true
}
trap cleanup EXIT

DEADLINE=$((SECONDS + 300))
until [ -f "$LOG" ] && grep -q "LSP server initialized" "$LOG" 2>/dev/null; do
  if [ $SECONDS -ge $DEADLINE ]; then
    echo "FAIL: language server did not initialize within 300s"
    if [ ! -f "$LOG" ]; then
      echo "      No idea.log was written — the IDE did not start."
      echo "      Check /tmp/mondoo-smoke.out for the Gradle output."
    else
      tail -20 "$LOG"
    fi
    exit 1
  fi
  sleep 5
done
sleep 5

fail=0
check() {
  if grep -q "$2" "$LOG"; then
    echo "  ok   $1"
  else
    echo "  FAIL $1"
    fail=1
  fi
}

echo "Assertions:"
check "plugin loads"                 "Loaded custom plugins: Mondoo"
check "optional LSP module loads"    "Mondoo: LSP module loaded"
check "server starts for a file"     "Mondoo: starting xgrep lsp"
check "server completes handshake"   "LSP server initialized"
check "findings reach the store"     "Mondoo: findings now"

# The store feeds both the tool window and the status-bar count, so a non-zero total
# is what makes those meaningful. Zero would mean the scanner ran and found nothing,
# which for a deliberately vulnerable file means the wiring is broken.
found=$(grep -oE "Mondoo: findings now [0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+$" || echo 0)
if [ "${found:-0}" -gt 0 ]; then
  echo "  ok   store holds $found finding(s) for a vulnerable file"
else
  echo "  FAIL store holds no findings for a deliberately vulnerable file"
  fail=1
fi

errors=$(grep -c " ERROR " "$LOG" || true)
if [ "$errors" -eq 0 ]; then
  echo "  ok   no ERROR lines"
else
  echo "  FAIL $errors ERROR lines"
  grep " ERROR " "$LOG" | head -5
  fail=1
fi

[ $fail -eq 0 ] && echo "smoke test passed" || echo "smoke test FAILED"
exit $fail
