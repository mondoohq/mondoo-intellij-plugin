# Infrastructure security

Author Mondoo policies in MQL, with the editor help you would expect for a real
language.

## Writing policies

Open a `*.mql.yaml` bundle or a `*.mql` query file and the cnspec language server
provides:

- **Completion** for MQL resources and their fields — press `.` after a resource. MQL
  has a large schema and this is the fastest way through it.
- **Hover** documentation for the resource or field under the caret.
- **Diagnostics** as you type, so a query that will not compile says so immediately.

Only files matching `*.mql.yaml`, `*.mql.yml` or `*.mql` are treated as Mondoo
content. Ordinary YAML in your project is left alone.

## Linting a bundle

**Tools** → **Mondoo Code Security** → **Lint Policy Bundle**, on an open bundle.

The linter checks things the language server does not: required tags, missing asset
filters, queries that no policy references. On a bundle with a compile error and
missing tags, the server reports the compile error and the linter reports seven
problems — they are complementary, which is why both run.

Findings appear in the Mondoo tool window alongside code-security findings.

## Formatting

**Format Policy Bundle** applies cnspec's own formatting, so bundles look the same
whoever wrote them and diffs stay small.

**Format and Sort Policy Bundle** also orders the bundle's contents, which is worth
doing once on a bundle that has grown organically.

Both write the file on disk, so the editor saves first and reloads after.

## Requirements

The cnspec CLI. Unlike the code scanner, it is **never downloaded automatically** —
cnspec connects to infrastructure with credentials and is normally installed and
updated by your system package manager, so the plugin does not fork that. When it is
missing, the plugin offers the install command for your platform.

Install it with:

```shell
bash -c "$(curl -sSL https://install.mondoo.com/sh)"
```

Set a custom location in **Settings** → **Tools** → **Mondoo** → **cnspec path**.

## Not yet available

Running policies against targets — local, SSH, Docker, Kubernetes, cloud — and the
credential handling that needs, are not in the plugin yet. Use the cnspec CLI for
those in the meantime.
