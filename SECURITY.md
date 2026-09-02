# Security Policies

Mondoo's organization-wide policy applies to this repository. See
[Mondoo Security Policies](https://github.com/mondoohq/.github/blob/main/SECURITY.md)
for the full guidelines on responsible disclosure.

## Reporting a Vulnerability

Submit individual reports to **security@mondoo.com**, including a full description of
the finding, how to reproduce the behavior and any supporting information. Please do
not open a public issue for a security vulnerability. Applicable submissions will be
directed to our Bug Bounty Program.

## Scope for this repository

This repository is the Mondoo plugin for JetBrains IDEs. It is a thin client:
scanning, rule loading and fix computation happen in the
[xgrep](https://mondoo.com/xgrep) binary, so findings in the scanner itself belong to
that project.

Findings that are in scope here include, but are not limited to:

- **The scanner download path.** The release manifest is fetched over HTTPS and the
  published SHA-256 is verified before anything is unpacked. Anything that bypasses
  that verification, or causes an unverified binary to be executed, is in scope.
- **Archive extraction**, which refuses entries that would escape the destination
  directory.
- Anything causing the plugin to execute code or a binary the user did not choose.
- Leakage of credentials or file contents off the local machine. The plugin makes no
  network requests other than fetching the scanner release.
