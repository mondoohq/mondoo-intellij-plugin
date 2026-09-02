# Releasing

Publishing is driven by a GitHub Release. `main` is PR-only, so a release starts with a
workflow rather than a direct commit.

## One-time setup

Four repository secrets are required. Without them the release job fails at signing,
before anything reaches the Marketplace.

| Secret | What it is |
| --- | --- |
| `PUBLISH_TOKEN` | A JetBrains Marketplace permanent token, from your profile's **Tokens** tab. Scoped to the Mondoo vendor account. |
| `CERTIFICATE_CHAIN` | The signing certificate chain, PEM. |
| `PRIVATE_KEY` | The signing private key, PEM. |
| `PRIVATE_KEY_PASSWORD` | The private key's passphrase. |

Generating the signing key pair is described in
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
An unsigned upload is accepted but flagged, so it is worth doing properly the first time.

Also needed before the first publish, and both have lead time:

- The plugin ID **`com.mondoo.security`** reserved on the Marketplace. Note it does not
  contain the word "intellij" — the Plugin Verifier rejects IDs that do.
- A Mondoo vendor account with the publishing user added to it.

## Cutting a release

1. **Prepare.** Run the **Prepare release** workflow with the new version, e.g. `0.2.0`
   or `1.0.0-beta.1`. It bumps `pluginVersion`, closes the changelog's `Unreleased`
   section into a version section, and opens a PR.
2. **Review and merge** that PR. This is the point to read the changelog as a user would.
3. **Create a GitHub Release** tagged `v<version>` against `main`. Mark it as a
   **pre-release** to route the upload to a non-default Marketplace channel.
4. The **Release** workflow then runs, in this order:
   - the tag matches `pluginVersion` — publishing 0.1.0 from a tag that says v0.2.0 is
     silent and hard to undo, so this fails the job rather than guessing;
   - the changelog has a section for the version;
   - `check` (tests) and `verifyPlugin` (the IDE compatibility matrix);
   - `buildPlugin`, `signPlugin`, `verifyPluginSignature`;
   - `publishPlugin`;
   - the signed ZIP is attached to the GitHub Release.

A release cannot ship something the normal pipeline would reject: the same tests and
verifier run again here rather than trusting an earlier green build of a different
commit.

## Channels

The channel is derived from the version's pre-release suffix:

| Version | Channel | Who gets it |
| --- | --- | --- |
| `1.2.0` | `default` | Everyone |
| `1.2.0-beta.1` | `beta` | People who added the beta repository URL |
| `1.2.0-eap.1` | `eap` | People who added the EAP repository URL |

## Dry run

The **Release** workflow can be run manually with `dryRun` left on. It builds, tests,
verifies and signs, and uploads the ZIP as a workflow artifact without publishing —
useful for checking the signing secrets before a real release depends on them.

## Version numbering

`pluginVersion` in `gradle.properties` is the single source of truth. The tag mirrors it
with a `v` prefix.

`sinceBuild` is `261.26222` and `untilBuild` is deliberately unset, so the plugin keeps
working on newer IDEs without a re-publish. Raise `sinceBuild` only when a platform API
the plugin needs is genuinely unavailable below it; every bump strands users.
