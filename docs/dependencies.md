# Dependency reachability

Which of your dependencies does your code actually use?

**Tools** → **Mondoo Code Security** → **Analyze Dependencies**, or the refresh button
in the **Dependencies** tab of the Mondoo tool window.

## Why it matters

Most dependency triage time goes on packages that are not really in play. A
vulnerability in a package nothing imports is a different problem from one on a live
code path, and a flat dependency list cannot tell you which you are looking at.

The analysis resolves imports from your own source, so packages are grouped by how
they relate to first-party code:

| Group | Meaning | Typical action |
| --- | --- | --- |
| **Imported** | Your code imports it | Treat findings here as real |
| **Imported and reachable** | Imported, and on a live code path | Highest priority |
| **Imported but dead** | Imported, but nothing reaches it | Lower priority |
| **Transitive** | Pulled in by another dependency | Depends on the parent |
| **Transitive, orphaned** | Pulled in by something nothing reaches | Usually removable |
| **Declared but unused** | Declared, never imported | Usually removable |
| **Development only** | Not shipped | Out of scope for production risk |
| **Unknown** | Imports could not be resolved for that ecosystem | Triage by hand |

Expand a package to see the files that import it, and double-click a file to open it.

## What this does not do

It answers *"does my code use this package?"*, not *"is this package vulnerable?"*.

Matching dependencies against advisories needs an advisory feed, which needs a Mondoo
Platform account. This analysis is fully offline: it reads manifests and source, never
invoking a package manager and never reaching the network.

Function-level reachability — *does the vulnerable function actually run* — is a
further step again, and depends on advisories naming the affected symbol. Most
ecosystems publish none, so it is not surfaced here.
