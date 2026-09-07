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

Four repository secrets are required **to publish to the Marketplace**. Without them a
release still builds and attaches its binary; only the publish job is skipped.

| Secret | What it is |
| --- | --- |
| `PUBLISH_TOKEN` | A JetBrains Marketplace permanent token, from your profile's **Tokens** tab. Scoped to the Mondoo vendor account. |
| `CERTIFICATE_CHAIN` | The signing certificate chain, PEM. |
| `PRIVATE_KEY` | The signing private key, PEM. |
| `PRIVATE_KEY_PASSWORD` | The private key's passphrase. |

Generating the signing key pair is described in
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
An unsigned upload is accepted but flagged, so it is worth doing properly the first time.

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

Also needed before the first publish, and both have lead time:

- The plugin ID **`com.mondoo.security`** reserved on the Marketplace. Note it does not
  contain the word "intellij" — the Plugin Verifier rejects IDs that do.
- A Mondoo vendor account with the publishing user added to it.

## Cutting a release

Three workflows, numbered because their names alone did not say which one to run. You
run the first; the other two run themselves.

| | Trigger | What it does |
| --- | --- | --- |
| **Release 1 - prepare** | you run it, with a version | Opens a PR bumping the version and closing the changelog |
| **Release 2 - tag** | that PR merging | Tags the version on main and opens a **draft** release |
| **Release 3 - publish** | you publishing the draft | Builds, attaches the ZIP, uploads to the Marketplace |

1. **Run *Release 1 - prepare*** with the new version, e.g. `0.3.0` or `1.0.0-beta.1`.
   It validates the version, bumps `gradle.properties`, closes the changelog, runs the
   tests, and opens a pull request. Nothing is tagged or published.

2. **Review and merge that PR.** This is the point to read the changelog as a user
   would — those words become the release notes and the Marketplace "What's new".

3. ***Release 2 - tag*** then runs on its own. It reads the version from
   `gradle.properties` on main, tags it, and opens a draft release.

   The version is typed once, in step 1, and the tag is derived from what merged. A tag
   and a `pluginVersion` that disagree is what broke the first v0.2.0 attempt; there is
   no longer a second place to get it wrong.

4. **Publish the draft.** Tick pre-release first if it should reach only the beta
   channel.

   Publishing is deliberately yours, for two reasons. It is the last point at which the
   notes can change before anything is public. And a release created by a workflow's own
   token cannot trigger another workflow — if *Release 2* published it, *Release 3*
   would never run and the release would sit there with no plugin attached, which is
   the failure this whole sequence exists to prevent.

5. ***Release 3 - publish*** then runs. The build job, in this order:
   - the tag matches `pluginVersion` — belt and braces now that step 3 derives it;
   - the changelog has a section for the version;
   - `check` (tests) and `verifyPlugin`;
   - `buildPlugin`, then `signPlugin` and `verifyPluginSignature` when the signing
     secrets exist;
   - the ZIP is attached to the GitHub Release, signed or not.

   Then the publish job, only when both the signing secrets and `PUBLISH_TOKEN` are
   set: `publishPlugin`, uploading the artifact the build job produced.

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
