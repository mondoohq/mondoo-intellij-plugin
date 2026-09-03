# Developer Guide

## Prerequisites

- **JDK 21.** Platform 261 class files are Java 21 bytecode (major version 65), even
  though the IDEs themselves bundle a JBR 25. Any JBR 21 works, including the one inside
  an installed IDE:

  ```bash
  export JAVA_HOME="/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home"
  ```

  Gradle's toolchain resolver will download a JDK 21 if none is installed.
- **Kotlin 2.3.0 or newer.** Platform 2026.1.4 ships Kotlin 2.3.0 metadata; an older
  compiler rejects every platform `.kotlin_module` and dies with an opaque "internal
  compiler error". Pinned in `gradle/libs.versions.toml`.
- **Gradle** comes from the wrapper (`./gradlew`, pinned to 9.2.1). Do not use a system
  Gradle: the IntelliJ Platform Gradle Plugin 2.x requires Gradle 9+.
- **xgrep** on your PATH for manual testing. `~/go/bin/xgrep` is picked up automatically.

## Build and run

```bash
./gradlew build            # compile + test
./gradlew runIde           # IntelliJ IDEA 2026.1.4 with the plugin loaded
./gradlew runGoLand        # GoLand — catches accidental Java-only dependencies
./gradlew runAndroidStudio # Android Studio — the one host JetBrains does not build
./gradlew ktlintCheck      # formatting; ktlintFormat fixes most of it
./gradlew buildPlugin      # -> build/distributions/*.zip
./gradlew verifyPlugin     # Plugin Verifier — see below
```

`runGoLand` and `runAndroidStudio` use the local installs in `/Applications`;
`runIde` downloads the IDE on first use.

### What gets verified

Three hosts, chosen for what each one catches rather than to enumerate the product
list: **IntelliJ IDEA** is the unified distribution the others are built from,
**GoLand** has no Java, Python or Kotlin plugin so a product-specific dependency
surfaces there, and **Android Studio** is the one host JetBrains does not build.

CI verifies the first two, one per job — the verifier downloads a full IDE per target
and several at once exhaust a runner's disk. Android Studio is not published as a
resolvable artifact, so it can only be verified from a local install:

```bash
./gradlew verifyPlugin -PverifyLocal=true   # every install in /Applications at or above the floor
./gradlew verifyPlugin -PverifyIde=GO       # one downloadable IDE, as CI does it
```

Run the local one before a release. The rest of the family — PyCharm, WebStorm,
PhpStorm, RubyMine, CLion, Rider, RustRover, DataGrip — shares IDEA's platform and is
covered by the CI guard that `plugin.xml` declares no product-specific `<depends>`;
add any of them back to the matrix in one line if that guard ever proves too weak.

### Operating systems

The plugin has real per-OS logic — it resolves `xgrep.exe` rather than `xgrep`, looks
in Chocolatey, WinGet and Scoop directories, unpacks a `.zip` instead of a `.tar.gz`,
and writes Windows paths into JSON and YAML where a backslash must survive escaping.
All of that is in the pure, IDE-free layer and is unit-tested, and CI runs that suite
on Linux **and** Windows.

What is *not* covered: nobody has run the plugin inside an IDE on Windows or Linux.
The verifier and the smoke test both run on macOS here, and the smoke-test script is
bash. If you work on Windows, say so in a pull request — that is the coverage gap
worth closing next.

For installing a build into an IDE you actually work in, and for what to check when it
does not appear, see [docs/install-dev-build.md](docs/install-dev-build.md).

## Testing policy

Mirrors the VS Code repo: **no test spawns a real binary, hits the network, or starts a
language server.**

- **Tier 1 — pure JUnit 5, no IDE.** The bulk of the suite. Argument builders, parsers,
  the language table, glob matching, semver, release-manifest selection. These functions
  take plain data — never `Project`, `VirtualFile` or `Editor`. Keeping that boundary is
  what makes the suite fast; enforce it in review.
- **Tier 2 — `BasePlatformTestCase`.** A handful: settings round-trip, findings store to
  tree model, the suppression quick fix via `myFixture`.

```bash
./gradlew test
```

## Smoke test

The unit suite is deliberately IDE-free, so nothing in it can catch a plugin that fails
to load, an extension point that does not resolve, or a language server that never
starts — the regressions that actually reach users. Those are all visible in `idea.log`,
so there is a script that launches a real IDE and asserts on it:

```bash
scripts/smoke-test.sh              # GoLand
scripts/smoke-test.sh runIde       # IntelliJ IDEA
scripts/smoke-test.sh runAndroidStudio
```

It checks the plugin loads, the optional LSP module resolves, the server starts for a
file and completes its handshake, and that the log has no `ERROR` lines. Run it before
opening a pull request that touches plugin.xml, an extension point, or startup.

## Cross-IDE compatibility

The plugin must load in every IntelliJ-based IDE. Two rules:

1. `plugin.xml` declares **only** `com.intellij.modules.platform`. Never add
   `com.intellij.modules.java`, `.python`, `.go`, or a `bundledPlugin` requirement — any
   of those silently narrows Marketplace compatibility.
2. Scope files by **name/extension**, never by IntelliJ `Language`. GoLand has no Python
   language and Android Studio has no Go language, so those files resolve to
   `PlainTextLanguage` there. See `XgrepLanguages`.

`./gradlew verifyPlugin` enforces rule 1; `runGoLand` catches violations in seconds.

## Checking whether an IDE exposes the LSP module

**Do not answer this by looking at the jars.** Jar presence says nothing about whether a
module is exposed to plugins. Check `product-info.json`, or better, run the plugin:

```bash
python3 -c "
import json; d=json.load(open('/Applications/Android Studio.app/Contents/Resources/product-info.json'))
print([l for l in d['layout'] if 'lsp' in l['name']])"
```

`XgrepLspModuleProbe` is the runtime check. It logs one line when the optional module
loads, because `<depends optional>` is otherwise completely silent. It is an
`ApplicationInitializedListener`, **not** a `ProjectActivity` — a project-scoped probe
cannot distinguish "module absent" from "project never finished opening", and that false
negative already produced one wrong conclusion.

```bash
# "<project-dir>,<file>" — the dir so a project opens, the file so an editor opens it
# and fileOpened() fires. Probe runs set idea.trust.all.projects so no modal dialog
# can block startup.
P=/tmp/mondoo-lsp-probe
./gradlew runAndroidStudio -PmondooProbeProject="$P,$P/vuln.py"

L=.intellijPlatform/sandbox/intellij-mondoo/*/log_runAndroidStudio/idea.log
grep -E "LSP module loaded|starting xgrep lsp|LSP server initialized" $L
```

Three lines means live scanning works in that IDE. Re-run on each Android Studio release
rather than assuming it keeps working.

Give the probe project an obvious name. A trust dialog for a project called `lsproj` gets
declined, and the run then fails for a reason that never reaches the log.

## Verifying the download path

Discovery finds any xgrep already on the machine, which means the download path is
never exercised by accident. `-PmondooIsolateBinary=<empty-dir>` hides PATH, `~/go/bin`
and Homebrew from the sandbox so the plugin has to fetch one:

```bash
P=/tmp/mondoo-lsp-probe
./gradlew runGoLand -PmondooProbeProject="$P,$P/vuln.go" -PmondooIsolateBinary=/tmp/fakehome

L=.intellijPlatform/sandbox/intellij-mondoo/*/log_runGoLand/idea.log
grep -E "no xgrep binary resolved|Installing the xgrep|starting xgrep lsp|LSP server initialized" $L
```

The expected sequence is: nothing resolved → install task → server started from
`<sandbox>/system_runGoLand/mondoo/xgrep/<version>/xgrep`. That also exercises the
install → restart-language-server wiring, which is easy to break and invisible in unit
tests.

To re-check the production assumption that the manifest hash covers the archive as
served:

```bash
python3 - <<'EOF'
import json, re, urllib.request, hashlib
m = json.load(urllib.request.urlopen("https://releases.mondoo.com/xgrep/latest.json"))
f = next(x for x in m["files"]
         if re.match(r'^xgrep_[0-9.]+_darwin_arm64\.tar\.gz$', x["filename"].rsplit("/", 1)[-1]))
h = hashlib.sha256()
with urllib.request.urlopen(f["filename"]) as r:
    for b in iter(lambda: r.read(1 << 20), b""): h.update(b)
print("match:", h.hexdigest() == f["hash"])
EOF
```

## Capturing xgrep LSP traffic

Before writing or changing a parser, capture what the current binary actually sends
rather than trusting the docs. A minimal stdio LSP client that performs
`initialize` / `didOpen` and dumps the responses is enough; the diagnostic `data`
payload, the `window/showMessage` scan text, and `executeCommandProvider.commands` are
the three things worth re-checking on every xgrep bump.

## Architecture decisions

See [docs/adr/](docs/adr/). Start with
[ADR-0001](docs/adr/0001-lsp-client-and-ide-compatibility.md).

## First build

The first `./gradlew` invocation downloads the IntelliJ IDEA 2026.1.4 distribution
(~1 GB+). It is cached in `~/.gradle` afterwards, so this cost is paid once per machine.

If you need to compile without that download, point the build at an IDE you already have
installed — Android Studio Quail 4 is `AI-261.26222.65`, the exact platform build this
plugin targets:

```kotlin
// build.gradle.kts, temporarily, for local iteration only
intellijPlatform {
    local("/Applications/Android Studio.app")
}
```

Do not commit that: CI needs the resolvable artifact, and a local IDE pins you to
whatever build happens to be installed.
