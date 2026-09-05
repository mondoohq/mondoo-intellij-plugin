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

## Starting a policy

**New Policy from Template…** asks for a file name, scaffolds it with cnspec, and
opens it. The result is a working example policy rather than an empty file — the
bundle format is a nested YAML shape nobody types from memory, and starting from
something that already runs is faster than starting from the documentation.

The name must end in `.mql.yaml` or `.mql.yml`, or neither the language server nor the
Policies tab would recognise what you just made.

If the file already exists the plugin says so and stops. It does not offer to
overwrite, because cnspec refuses to: given an existing path it writes nothing and
reports that the policy already exists, so an overwrite prompt would be offering
something that cannot be honoured.

## Finding your way around a bundle

The **Policies** tab in the Mondoo tool window lists every `*.mql.yaml` in the
project, by directory:

```
policies/
  linux/
    ssh.mql.yaml            2 policies, 7 queries
      Policies
        SSH Baseline        ssh-baseline
          Server hardening  3 checks
            Disallow root login
            sshd-protocol   not defined in this file
      Queries
        Disallow root login sshd-permit-root
```

A group lists the queries its checks name, resolved through the bundle, so you can
read a policy as it will actually run rather than as a list of uids. A check naming a
uid nothing defines is shown in red rather than dropped — that is a bug in the bundle,
and this is where you would notice it.

- **Double-click** anything to jump to where it is declared.
- **Start typing** to filter the tree; there is no search box to clear afterwards.
- **Run** executes what is selected against a target you pick: a single query on its
  own, one policy from its bundle, or the whole bundle.
- The tree follows the file you are editing, not the file on disk, and updates when
  bundles are saved, added or removed.

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

## Running policies against a target

**Scan Target…** runs a policy scan. **Run MQL Query…** runs a single query, which is
the fastest way to answer "what does this resource actually return here?" — select an
expression in the editor first and it is offered as the default.

Output streams into a console tab in the Mondoo tool window.

**This machine** is always available and needs no configuration. **Manage Targets…**
adds others:

| Target | Needs |
| --- | --- |
| SSH host | `host`, or `user@host:port`. A private key file, or your SSH agent |
| Docker | A container name or id, or an image reference |
| Kubernetes | Nothing for the current context; a path for local manifests |

cnspec supports many more providers than these. The rest remain available from its
command line.

## Managing targets

**Manage Targets…** offers four things:

- **Add a target** — pick a kind, name it, fill in its fields. Secrets are prompted
  masked and go straight to the IDE password safe.
- **Edit a target** — every field comes back pre-filled with what is stored. Leave a
  secret blank to keep the one already saved; changing a hostname should not cost you
  a password. The name is fixed, because it is the key the password safe uses —
  renaming means removing and adding.
- **Test a connection** — asks cnspec whether it can actually reach the target, before
  you commit to a scan. Without it, the first sign of a wrong host or a stale key is a
  scan that fails minutes in, with no way to tell whether the fault is the target, the
  credentials or the policy.
- **Remove a target** — also forgets its secrets, so nothing is orphaned in the safe.

Note that cnspec exits successfully even when it cannot reach an asset, so the
connection test reads its output rather than its exit code. A test keyed on the exit
code would call every failure a success.

## How credentials are handled

Two rules, both enforced by the code rather than by convention:

- **Secrets are never written to project settings.** They go to the IDE's password
  safe — the OS keychain where there is one. A target's configuration physically
  cannot hold a secret field, so `mondoo-targets.xml` is safe to commit.
- **Secrets are never put on a command line.** Anything on a command line is visible
  to every process on the machine through the process table. Connection details go in
  an inventory file that is mode `0600` in a private directory and deleted when the
  run ends; secrets are passed through the environment.

Inventory files left behind by a crash are swept on the next start.

Scans are disabled in a project you have not trusted, since running one connects to
infrastructure using the project's configuration.

Secrets never reach a command line. They are written into a temporary inventory file
— mode 0600, in a directory only you can enter, deleted when the process exits —
because a command line is visible to every process on the machine through the process
table.

For SSH the credential depends on what you configured, in this order:

| Configured | Credential sent |
| --- | --- |
| A key file | `private_key`, with the path; cnspec reads the key itself |
| A password | `password`, from the IDE password safe |
| Neither | `ssh_agent`, deferring to your agent |

A passphrase-protected key is not supported: cnspec rejects a `private_key` credential
carrying a passphrase with "no authentication method defined", so there is nothing
useful to send. Use an agent for those.

