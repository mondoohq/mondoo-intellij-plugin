# Bill of materials

Inventory what is actually in your project — its dependencies, its cryptography, and
its AI components — as a standard document you can hand to compliance, attach to a
release, or diff between versions.

**Tools** → **Mondoo Code Security** → **Generate Bill of Materials…**

## What you can generate

| Kind | What it inventories |
| --- | --- |
| **Software (SBOM)** | Dependencies from manifests and lockfiles — `go.mod`, `package-lock.json`, `poetry.lock`, `Cargo.lock`, `pom.xml` and others |
| **Cryptography (CBOM)** | Algorithms, keys, protocols and certificates detected in your source |
| **AI (AIBOM)** | AI SDKs, frameworks, runtimes and models detected in your source |

The software bill is parsed **offline** from manifest and lock files. It never invokes
a package manager and never reaches the network.

The cryptography and AI bills are different: they run the scanner's rule engine over
your source, so they take longer on a large repository. They are also **CycloneDX JSON
only** — cryptographic assets and AI components have no SPDX or table representation —
so the format step is skipped when you pick one of them.

## Formats

For a software bill: CycloneDX JSON, CycloneDX XML, SPDX JSON, SPDX tag-value, raw
JSON, or a human-readable table.

CycloneDX JSON is the default because it is the only format that can express every
kind, and because it is what most downstream tooling expects.

## Where it goes

You choose the file. The suggested name follows the project and the kind, so the
documents sort together and are recognisable later:

```
my-app.sbom.cdx.json
my-app.cbom.cdx.json
my-app.aibom.cdx.json
```

When generation finishes, the notification offers to open the result.

## Keeping noise out

The scanner skips vendored directories by default, and skips dependency files under
test, fixture, example and docs trees — those describe fixture data, not what your
project actually ships.

## Requirements

The same scanner used for code security. If it is not installed, the action is disabled
and **Set Up Scanner** installs it.

Generation is disabled in a project you have not trusted, since it reads the whole
tree.
