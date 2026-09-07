#!/usr/bin/env bash
#
# Sets the plugin version everywhere a release needs it, and proves it landed.
#
# Two files carry the version, and they must agree or the release workflow refuses to
# publish — a tag that says v0.2.0 while gradle.properties says 0.1.0 would otherwise
# ship a binary identifying itself as the wrong version, silently:
#
#   gradle.properties   pluginVersion, which everything else derives from
#   CHANGELOG.md        the Unreleased section closed into a dated version section
#
# Nothing else is versioned on purpose. Documentation that showed a version in example
# output now says <version> instead, because example output that has to be bumped on
# every release is example output that will be wrong.
#
# A script rather than steps inlined in a workflow, so the same thing runs locally and
# in CI, and so it can be read in one place.
#
# Usage: scripts/bump-version.sh 0.2.0
set -euo pipefail

VERSION="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "$VERSION" ]; then
  echo "usage: scripts/bump-version.sh <version>    e.g. 0.2.0 or 1.0.0-beta.1" >&2
  exit 2
fi

# Semantic version, optionally with a pre-release suffix. The suffix is not cosmetic:
# release.yml derives the Marketplace channel from it, so 1.0.0-beta.1 reaches only
# people who opted into the beta repository.
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
  echo "FAIL: '$VERSION' is not a semantic version" >&2
  exit 1
fi

CURRENT=$(grep '^pluginVersion' gradle.properties | cut -d'=' -f2 | tr -d ' ')
if [ "$CURRENT" = "$VERSION" ]; then
  echo "FAIL: gradle.properties already says $VERSION." >&2
  echo "      Either this release was already prepared, or you meant a different version." >&2
  exit 1
fi

# An empty Unreleased section means empty release notes. Better to say so now than to
# publish a release nobody can read.
if ! awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' CHANGELOG.md | grep -q '^- '; then
  echo "FAIL: CHANGELOG.md has nothing under [Unreleased]." >&2
  echo "      Add what changed before cutting a release." >&2
  exit 1
fi

echo "Bumping $CURRENT -> $VERSION"

# gradle.properties. Anchored to the line start so a comment mentioning the key is
# untouched, and the separator is preserved rather than reformatted.
sed -i.bak "s/^pluginVersion = .*/pluginVersion = $VERSION/" gradle.properties
rm -f gradle.properties.bak

./gradlew patchChangelog --quiet

# patchChangelog rewrites the file header and drops the blank line under the title.
# Cosmetic, but it turns every release into a diff that touches an unrelated line, and
# restoring it by hand has already been forgotten twice.
python3 - <<'PY'
import pathlib
p = pathlib.Path("CHANGELOG.md")
s = p.read_text()
joined = "All notable changes to the Mondoo plugin for JetBrains IDEs.\nThe format is based on"
if joined in s:
    s = s.replace(joined, joined.replace(".\nThe format", ".\n\nThe format"))
    p.write_text(s)
    print("  restored the blank line patchChangelog strips")
PY

# Prove it, rather than trust the edits. This script exists to prevent a mismatch, so
# it must not be able to report success while leaving one.
AFTER=$(grep '^pluginVersion' gradle.properties | cut -d'=' -f2 | tr -d ' ')
if [ "$AFTER" != "$VERSION" ]; then
  echo "FAIL: gradle.properties says '$AFTER' after the bump, expected '$VERSION'" >&2
  exit 1
fi
if ! grep -qE "^## \[$VERSION\]" CHANGELOG.md; then
  echo "FAIL: CHANGELOG.md has no section for $VERSION after patchChangelog" >&2
  exit 1
fi

echo "  gradle.properties: pluginVersion = $AFTER"
echo "  CHANGELOG.md:      $(grep -E "^## \[$VERSION\]" CHANGELOG.md)"
echo "OK"
