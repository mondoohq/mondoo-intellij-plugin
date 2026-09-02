# Installing a development build

How to run an unreleased build of the plugin in GoLand, IntelliJ IDEA, PyCharm, Android
Studio, or any other JetBrains IDE.

There are two ways, and the first is usually the one you want.

## 1. A sandboxed IDE (recommended)

Launches a separate IDE instance with the plugin loaded, using its own settings
directory. Your day-to-day IDE is untouched, so a broken build cannot disrupt your work.

```bash
./gradlew runIde            # IntelliJ IDEA
./gradlew runGoLand         # GoLand
./gradlew runPyCharm        # PyCharm
./gradlew runAndroidStudio  # Android Studio (uses your local install)
```

`runIde` and `runPyCharm` download the IDE on first use — around 1 GB, cached afterwards.
`runGoLand` and `runAndroidStudio` use the copy already in `/Applications`; point them
elsewhere in `build.gradle.kts` if yours lives somewhere else.

To run against a different IDE, add a task in `build.gradle.kts`:

```kotlin
intellijPlatformTesting {
    runIde.register("runWebStorm") {
        localPath = file("/Applications/WebStorm.app")   // or: type = ...; version = ...
    }
}
```

Sandbox settings, logs and installed plugins live under
`.intellijPlatform/sandbox/`. Delete that directory to start from a clean IDE.

## 2. Installing into your own IDE

Use this when you want the plugin in the IDE you actually work in — for dogfooding, or
to hand a build to someone else.

**1. Build the ZIP.**

```bash
./gradlew buildPlugin
# -> build/distributions/intellij-mondoo-0.1.0.zip
```

**2. Install it.** In the IDE:

**Settings/Preferences** → **Plugins** → the **⚙** gear icon →
**Install Plugin from Disk…** → select the ZIP.

**3. Restart** when prompted.

The IDE will not warn about an unsigned plugin when installing from disk; signing only
applies to Marketplace distribution.

### Requirements

The IDE must be **2026.1.4 or newer** (build `261.26222+`). An older IDE refuses the
plugin with an incompatibility message rather than installing a broken one — that floor
is where the platform LSP client API became available outside the commercial IDEs.

### Updating to a newer build

Repeat both steps. Installing over an existing version replaces it; there is no need to
uninstall first. The IDE restarts again.

### Removing it

**Settings** → **Plugins** → **Installed** → find **Mondoo** → the gear icon →
**Uninstall**, then restart.

## Checking it actually loaded

1. **Settings** → **Tools** → **Mondoo** exists.
2. **Tools** → **Mondoo Code Security** lists the actions.
3. **Tools** → **Mondoo Code Security** → **Show xgrep Path** reports a binary. If it
   does not, run **Set Up Scanner**.
4. Open a file in a supported language. Findings appear in the editor and in the
   **Mondoo** tool window.

Nothing there? Check the log: **Help** → **Show Log in Finder/Explorer**, and search for
`Mondoo`. These lines are the ones that matter:

```
Loaded custom plugins: Mondoo (0.1.0)
Mondoo: LSP module loaded (com.intellij.modules.lsp is available)
Mondoo: starting xgrep lsp for <file> using <path>
LSP server initialized ... name = xgrep
```

- **No "Loaded custom plugins" line** — the plugin did not install, or the IDE is below
  the version floor.
- **No "LSP module loaded" line** — the IDE does not expose `com.intellij.modules.lsp`.
  The plugin still works; live scanning does not. See
  [ADR-0001](adr/0001-lsp-client-and-ide-compatibility.md).
- **"no xgrep binary resolved"** — run **Set Up Scanner**, or set the path in
  **Settings** → **Tools** → **Mondoo**.

## Building for someone else

`build/distributions/intellij-mondoo-<version>.zip` is self-contained. The recipient
needs an IDE at 2026.1.4+ and nothing else — the scanner is downloaded on first use,
with its published checksum verified.
