# Releasing

Publishing is driven by a GitHub Release. `main` is PR-only, so a release starts with a
workflow rather than a direct commit.

## What a release produces

Two things, from two jobs, because only one of them needs credentials:

| Job | Produces | Needs secrets |
| --- | --- | --- |
| **Build and attach to the release** | The plugin ZIP, attached to the GitHub Release | No |
| **Publish to JetBrains Marketplace** | The Marketplace listing update | Yes |

A release cut without any secrets still works and still gives people something to
install: the ZIP lands on the GitHub Release and installs with **Install Plugin from
Disk…**, which is how this plugin is distributed until the Marketplace listing exists.
The publish job is then simply skipped, and the build job's summary says so in as many
words — a green release is not by itself evidence that anything reached the
Marketplace.

The publish job uploads *the file the build job produced*, downloaded as a workflow
artifact rather than rebuilt. Two builds of one version could differ; this way what
someone downloads from GitHub and what the Marketplace serves are the same bytes.

## One-time setup

### Required to publish to the Marketplace

| Secret | What it is |
| --- | --- |
| `PUBLISH_TOKEN` | A JetBrains Marketplace permanent token, from your profile's **Tokens** tab. Scoped to the Mondoo vendor account. |

That is the whole requirement. With it set, a release publishes; without it, the release
still builds, tags and attaches the ZIP, and the summary says publishing was skipped.

### Optional, and deliberately deferred: signing

| Secret | What it is |
| --- | --- |
| `CERTIFICATE_CHAIN` | The signing certificate chain, PEM. |
| `PRIVATE_KEY` | The signing private key, PEM, unencrypted. |
| `PRIVATE_KEY_PASSWORD` | The passphrase used when the key was generated. |

**None of these are set yet, on purpose.** Getting the plugin published came first;
signing is the next step, once it is live on the Marketplace. Until then every release
publishes unsigned and logs a warning saying so — that warning is the reminder, and it
disappears once the three secrets exist.

Signing is **not** required to publish. The Marketplace accepts unsigned plugins — its
own web upload never asks for a certificate — and the SDK only advises to "make sure it
is signed". What a signature buys is JetBrains being able to prove the plugin was not
modified after you built it, which is worth having but is not a gate.

Set all three or none. When they are absent the build is unsigned and says so; when
they are present it signs and verifies the signature before publishing. No workflow
change is needed to switch it on.

Generating the signing key pair is described in
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
A self-signed certificate is fine — the SDK says "using a self-signed certificate is an
option if no internal CAs exist". Note the `-days` argument: the certificate expires,
and an expired one breaks signing rather than falling back to unsigned.

### Listing details set through the web UI

These cannot be set from `plugin.xml` and are only done once, at first publish. They
come from the Marketplace's
[listing guidelines](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html):

- **At least one tag.** Mandatory at upload. "Security" is the fitting one.
- **Screenshots**, minimum 1200 × 760, showing the plugin inside a JetBrains IDE, all
  at the same aspect ratio. `.github/images/plugin-overview.png` (1600 × 995) qualifies
  and is the obvious first one — note it is referenced from the README *after* the
  plugin-description markers, so it is deliberately not part of the extracted
  description and has to be uploaded separately.
- **No desktop backgrounds or personal information** in any screenshot.

What is already set in the repository and needs no web-UI work: the name, the
description (extracted from README.md between the plugin-description markers), the
change notes (from CHANGELOG.md), the vendor details, the plugin URL, and both light
and dark icons.

### The first upload has to be manual

JetBrains moderates a brand-new plugin's first submission, and `publishPlugin` cannot
create a listing that does not exist — it can only update one. So the first ZIP goes up
through the web UI; every release after that is automatic.

Also needed before the first publish, and both have lead time:

- The plugin ID **`com.mondoo.security`** reserved on the Marketplace. Note it does not
  contain the word "intellij" — the Plugin Verifier rejects IDs that do.
- A Mondoo vendor account with the publishing user added to it.

## Cutting a release

Two workflows. You run the first; the second runs itself.

| | Trigger | What it does |
| --- | --- | --- |
| **Release 1 - prepare** | you run it, with a version | Opens a PR bumping the version and closing the changelog |
| **Release 2 - publish** | that PR merging | Tests, builds, signs, tags, creates the GitHub Release with the ZIP, uploads to the Marketplace |

1. **Run *Release 1 - prepare*** with the new version, e.g. `0.3.0` or `1.0.0-beta.1`.
   It validates the version, bumps `gradle.properties`, closes the changelog, runs the
   tests, and opens a pull request. Nothing is tagged or released.

2. **Review and merge that PR.** This is the release decision, and the only human
   checkpoint — read the changelog diff as a user would, because those words become
   both the release notes and the Marketplace "What's new".

3. ***Release 2 - publish*** then runs on its own, and does everything else.

That is the same trigger shape cnspec uses — a version file changing on `main` — so the
two repositories release the same way.

### Why one workflow rather than two

The obvious split is one workflow that tags and releases, and another reacting to
`on: release` that builds and attaches. It does not work here: **a release created with
`GITHUB_TOKEN` does not trigger another workflow**, so the second would never run and
the release would sit there with nothing to install. That is exactly how the first
v0.2.0 attempt ended.

cnspec avoids this with a GitHub App token (`mondoo-mergebot`), whose secrets are not
granted to this repository — only eight org secrets are, and those two are not among
them. If they are ever granted, this can be split to match cnspec exactly.

### Where the version comes from

`gradle.properties`, and nowhere else. It is typed once, in step 1, and the tag is
derived from whatever merged. A tag and a `pluginVersion` that disagree is what broke
the first v0.2.0 attempt; there is no second place left to get it wrong.

*Release 2* only acts when all three hold: `gradle.properties` changed, the version has
no tag yet, and the changelog has a section for it. That file changes for other reasons
— a Gradle version, a JVM arg — and none of those should release anything.

A pre-release suffix carries through on its own: `1.0.0-beta.1` marks the GitHub Release
as a pre-release, and routes the Marketplace upload to the beta channel.

### What *Release 2* does, in order

- the version is releasable — untagged, and present in the changelog;
- `check` (tests) and `verifyPlugin`;
- `buildPlugin`, then `signPlugin` and `verifyPluginSignature` when the signing secrets
  exist;
- the tag is created and pushed;
- the GitHub Release is created **with the ZIP already attached**, so there is no window
  in which a release is public with nothing to install;
- then the publish job, only when the signing secrets and `PUBLISH_TOKEN` are both set:
  `publishPlugin`, uploading the artifact the build job produced.

The attached ZIP is what people install with **Install Plugin from Disk…** until the
plugin is on the Marketplace, so its name and contents are user-facing. The README
points at the latest release for exactly this.

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

## Bumping the version by hand

`scripts/bump-version.sh <version>` is what *Release 1 - prepare* calls, and it can be run
locally — useful for seeing the changelog diff before committing to anything. It
refuses a version that is not semantic, one that is already set, and an empty
`Unreleased` section, and it verifies both files afterwards rather than trusting its
own edits.

Only two files carry the version: `gradle.properties` and `CHANGELOG.md`. Documentation
that once showed a version in example output now says `<version>`, because example
output that must be bumped every release is example output that will be wrong.

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
