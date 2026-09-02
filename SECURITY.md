# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability.

Report it to **security@mondoo.com**, or through GitHub's private vulnerability
reporting on this repository (**Security** → **Report a vulnerability**). Include the
affected version, what an attacker could achieve, and steps to reproduce if you have
them.

We aim to acknowledge a report within three business days.

## Scope

This repository is the Mondoo plugin for JetBrains IDEs. It is a thin client: scanning,
rule loading and fix computation happen in the `xgrep` binary, so findings about the
scanner itself belong in the [xgrep](https://mondoo.com/xgrep) project.

Vulnerabilities that are in scope here include, but are not limited to:

- The scanner-download path — the release manifest is fetched over HTTPS and the
  published SHA-256 is verified before anything is unpacked. Report anything that
  bypasses that check or allows an unverified binary to run.
- Archive extraction, which refuses entries that escape the destination directory.
- Anything that causes the plugin to execute code or a binary the user did not choose.
- Leakage of credentials or file contents beyond the local machine. The plugin makes no
  network calls other than fetching the scanner release.
