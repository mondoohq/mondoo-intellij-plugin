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
# A policy bundle, so the second language server starts too. Both must coexist:
# they are separate providers with separate lifecycles, and a regression that
# breaks one while leaving the other working is easy to miss by hand.
cat > "$PROBE/example.mql.yaml" <<'EOF'
policies:
  - uid: smoke-policy
    name: Smoke policy
    version: "1.0.0"
    groups:
      - filters: asset.family.contains("unix")
        checks:
          - uid: smoke-check
queries:
  - uid: smoke-check
    title: A check
    mql: sshd.config.params["PermitRootLogin"] == "no"
EOF

# The built-in runIde task logs to the sandbox's default "log" directory; tasks
# registered through intellijPlatformTesting get one named after the task. Guessing
# wrong here reports "the language server never started" for a run that started fine,
# which is worse than no run at all.
SANDBOX="$ROOT/.intellijPlatform/sandbox/mondoo-intellij-plugin/IU-2026.1.4"
if [ "$TASK" = "runIde" ]; then
  LOG_DIR="$SANDBOX/log"
else
  LOG_DIR="$SANDBOX/log_${TASK}"
fi
LOG="$LOG_DIR/idea.log"

GRADLE_PID=""
cleanup() {
  pkill -f "plugins_${TASK}" 2>/dev/null || true
  [ -n "$GRADLE_PID" ] && kill "$GRADLE_PID" 2>/dev/null || true
}
trap cleanup EXIT

# One retry. An IDE that was killed moments earlier can still be releasing its sandbox
# when the next one starts; the newcomer then fails to open a project and writes
# nothing useful. That is an artefact of the harness, not of the plugin, and a run that
# reports it as a plugin failure is worse than no run at all.
attempt() {
  rm -rf "$LOG_DIR"
  ./gradlew "$TASK" -PmondooProbeProject="$PROBE,$PROBE/main.py,$PROBE/example.mql.yaml" \
    --console=plain >/tmp/mondoo-smoke.out 2>&1 &
  GRADLE_PID=$!

  local deadline=$((SECONDS + 300))
  until [ -f "$LOG" ] && grep -q "XgrepLspServerDescriptor.*LSP server initialized" "$LOG" 2>/dev/null; do
    [ $SECONDS -ge $deadline ] && return 1
    sleep 5
  done
  # The MQL server loads a resource schema first, so it lands later than the code
  # scanner. Give it its own window rather than assuming one implies the other.
  local mql_deadline=$((SECONDS + 60))
  until grep -q "CnspecLspServerDescriptor.*LSP server initialized" "$LOG" 2>/dev/null; do
    [ $SECONDS -ge $mql_deadline ] && break
    sleep 2
  done
  sleep 3
  return 0
}

echo "Launching $TASK with a scratch project at $PROBE"
if ! attempt; then
  echo "  first attempt did not start; retrying once after full teardown"
  cleanup
  # Wait for the IDE to actually exit, not merely to leave the process table.
  for _ in $(seq 1 20); do pgrep -f "plugins_${TASK}" >/dev/null 2>&1 || break; sleep 1; done
  sleep 5
  if ! attempt; then
    echo "FAIL: language server did not initialize within 300s (twice)"
    if [ ! -f "$LOG" ]; then
      echo "      No idea.log was written — the IDE did not start."
      echo "      Check /tmp/mondoo-smoke.out for the Gradle output."
    else
      tail -20 "$LOG"
    fi
    exit 1
  fi
fi

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

# Resolves every action id in DeclaredActions and instantiates every service inside
# the running IDE. A menu item whose class was renamed, or a service that throws in
# its constructor, is invisible to the compiler and to the unit suite; this is where
# it surfaces. See MondooSelfCheck.
check "declared actions and services resolve" "Mondoo self-check PASS"
if grep -q "Mondoo self-check FAIL" "$LOG"; then
  echo "  FAIL self-check reported problems:"
  grep "Mondoo self-check FAIL" "$LOG" | head -10 | sed 's/^/       /'
  fail=1
fi

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

# The MQL server is optional here: cnspec is never auto-installed, so a machine
# without it is a supported state rather than a failure. Report either way.
if grep -q "starting cnspec lsp" "$LOG"; then
  # cnspec reports itself as "mql-language-server", not by binary name.
  if grep -qE "CnspecLspServerDescriptor.*LSP server initialized" "$LOG"; then
    echo "  ok   MQL server runs alongside the code scanner"
  else
    echo "  FAIL MQL server started but did not initialize"
    fail=1
  fi
else
  echo "  --   MQL server not started (cnspec not installed; not a failure)"
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
