import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.ktlint)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // The unified IntelliJ IDEA distribution (com.jetbrains.intellij.idea:idea),
        // which replaced the IC/IU split in 2025.3.
        intellijIdea(providers.gradleProperty("platformVersion"))

        // The LSP client API lives in a platform *content module*, so this is a
        // bundledModule() rather than a bundledPlugin().
        bundledModule("intellij.platform.lsp")

        // For TrustedProjects. The scanner is a process spawned over project
        // contents, so it must not run in a project the user has not trusted.
        bundledModule("intellij.platform.ide.impl")

        pluginVerifier()
        zipSigner()

        // Deliberately NOT testFramework(TestFrameworkType.Platform) here.
        // The `test` task runs tier-1 tests only: pure JUnit 5 over argument
        // builders and parsers, with no IDE. Pulling in the platform test
        // framework installs IntelliJ's JUnit5 session listener, which cannot
        // start outside a full IDE test environment. Tier-2 fixture tests get
        // their own task via intellijPlatformTesting.testIde (see below).
    }

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.opentest4j)
}

changelog {
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    groups.set(listOf("Added", "Changed", "Fixed", "Removed"))
}

/**
 * Rendered eagerly at configuration time. Doing it inside a provider would capture
 * the Project through the changelog extension, which the configuration cache cannot
 * serialize.
 */
val renderedChangeNotes: String = run {
    val version = providers.gradleProperty("pluginVersion").get()
    with(changelog) {
        renderItem(
            (getOrNull(version) ?: getUnreleased())
                .withHeader(false)
                .withEmptySections(false),
            org.jetbrains.changelog.Changelog.OutputType.HTML,
        )
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.mondoo.security"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // The Marketplace description is the README's own words, extracted between
        // the plugin-description markers, so the store listing and the repository
        // cannot describe the plugin differently.
        description = providers.fileContents(layout.projectDirectory.file("README.md"))
            .asText
            .map { readme ->
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"
                require(readme.contains(start) && readme.contains(end)) {
                    "README.md is missing the plugin description markers"
                }
                org.jetbrains.changelog.markdownToHTML(
                    readme.substringAfter(start).substringBefore(end).trim(),
                )
            }

        // Marketplace change notes come from CHANGELOG.md, so what a user reads on
        // the Marketplace and what is in the repository cannot drift apart.
        changeNotes = renderedChangeNotes

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended on purpose: the plugin keeps working on newer IDEs.
            untilBuild = provider { null }
        }

        vendor {
            name = "Mondoo, Inc."
            email = "hello@mondoo.com"
            url = "https://mondoo.com"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")

        // A pre-release suffix routes the upload to a non-default channel, so
        // 1.2.0-beta.1 reaches only people who opted into the beta repository while
        // 1.2.0 goes to everyone.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            // -PverifyLocal=true verifies only against IDEs already on this machine.
            // The full matrix downloads ~1 GB per IDE, which is a CI job, not
            // something to run on a laptop before every commit.
            val single = providers.gradleProperty("verifyIde").orNull
            if (single != null) {
                // One IDE per invocation. The verifier downloads a full IDE per
                // target — 1-2 GB each — and a runner cannot hold the whole matrix,
                // so CI fans these out one per job instead of looping here.
                create(IntelliJPlatformType.fromCode(single), "2026.1.4")
            } else if (providers.gradleProperty("verifyLocal").isPresent) {
                // Only IDEs at or above the declared floor. An older install
                // (e.g. a 2025.2 left on disk) has no LSP module and would report a
                // compatibility problem for an IDE this plugin does not claim to
                // support — a false failure that hides real ones.
                val floor = providers.gradleProperty("pluginSinceBuild").get().substringBefore('.').toInt()
                listOf(
                    "/Applications/Android Studio.app",
                    "/Applications/GoLand.app",
                    "/Applications/IntelliJ IDEA.app",
                    "/Applications/IntelliJ IDEA CE.app",
                ).map(::file).filter { ide ->
                    val build = File(ide, "Contents/Resources/build.txt")
                        .takeIf { it.isFile }?.readText()?.trim().orEmpty()
                    val major = build.substringAfter('-', build).substringBefore('.').toIntOrNull()
                    ide.exists() && major != null && major >= floor
                }.forEach { local(it) }
            } else {
                // Three hosts, chosen for what each one can catch rather than for
                // coverage of the product list:
                //
                //  - IntelliJ IDEA, the unified distribution every other IDE is built
                //    from. It moved to a single product in 2025.3, so there is no
                //    separate Community build at 261 to check as well.
                //  - GoLand, which has no Java, Python or Kotlin plugin. An accidental
                //    dependency on a product-specific module shows up here and nowhere
                //    else in this list.
                //  - Android Studio, the one host not built by JetBrains, and the one
                //    the SDK documentation still wrongly says has no LSP client.
                //
                // The rest of the family shares IDEA's platform and is covered by the
                // guard in CI that plugin.xml declares no product-specific <depends>.
                // Adding them back is one line each if that guard ever proves too weak.
                create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
                create(IntelliJPlatformType.GoLand, "2026.1.4")

                // Android Studio is not published as a resolvable artifact, so it can
                // only be verified from a local install — which means locally, not in
                // CI. `./gradlew verifyPlugin -PverifyLocal=true` is the one that
                // covers it.
                file("/Applications/Android Studio.app").takeIf { it.exists() }?.let { local(it) }
            }
        }
    }
}

intellijPlatformTesting {
    // A non-Java IDE is where "accidentally depended on com.intellij.modules.java"
    // shows up instantly.
    // Uses the locally installed GoLand rather than downloading another ~1 GB
    // distribution. Point it elsewhere if your install is not at the default path.
    runIde.register("runGoLand") {
        localPath = file("/Applications/GoLand.app")
        task {
            providers.gradleProperty("mondooProbeProject").orNull
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.forEach { args(it) }
            // Probe-run only: a modal "Trust Project" dialog otherwise blocks project
            // open, so post-startup activities never fire and the run looks like a
            // failure. See docs/adr/0001.
            systemProperty("idea.trust.all.projects", "true")
            systemProperty("jb.consents.confirmation.enabled", "false")
            systemProperty("jb.privacy.policy.text", "<!--999.999-->")
            systemProperty("ide.show.tips.on.startup.default.value", "false")
            // Makes MondooSelfCheck resolve every declared action and instantiate
            // every service, and log the verdict. Sandbox runs only; it is inert
            // in a normal IDE. See MondooSelfCheck.
            systemProperty("mondoo.selfcheck", "true")
            // -PmondooIsolateBinary=<dir> hides any xgrep already on this machine
            // (PATH, ~/go/bin, Homebrew) so the download path is genuinely exercised.
            providers.gradleProperty("mondooIsolateBinary").orNull?.let { fakeHome ->
                systemProperty("user.home", fakeHome)
                environment("PATH", "/usr/bin:/bin")
                environment("GOPATH", "")
                environment("GOBIN", "")
            }
        }
    }
    // Sandboxed Android Studio, using the locally installed app. Android Studio is
    // not published as a resolvable Maven artifact, and this also avoids touching a
    // running IDE's own config. Quail 4 is AI-261.26222.65 — the exact platform
    // build this plugin targets.
    runIde.register("runAndroidStudio") {
        localPath = file("/Applications/Android Studio.app")
        task {
            // Open a scratch project with a vulnerable file already restored, so
            // fileOpened() fires and the language server starts without any UI
            // interaction. Set -PmondooProbeProject=<dir> to use it.
            providers.gradleProperty("mondooProbeProject").orNull
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.forEach { args(it) }
            // Probe-run only: a modal "Trust Project" dialog otherwise blocks project
            // open, so post-startup activities never fire and the run looks like a
            // failure. See docs/adr/0001.
            systemProperty("idea.trust.all.projects", "true")
            systemProperty("jb.consents.confirmation.enabled", "false")
            systemProperty("jb.privacy.policy.text", "<!--999.999-->")
            systemProperty("ide.show.tips.on.startup.default.value", "false")
            // Makes MondooSelfCheck resolve every declared action and instantiate
            // every service, and log the verdict. Sandbox runs only; it is inert
            // in a normal IDE. See MondooSelfCheck.
            systemProperty("mondoo.selfcheck", "true")
            // -PmondooIsolateBinary=<dir> hides any xgrep already on this machine
            // (PATH, ~/go/bin, Homebrew) so the download path is genuinely exercised.
            providers.gradleProperty("mondooIsolateBinary").orNull?.let { fakeHome ->
                systemProperty("user.home", fakeHome)
                environment("PATH", "/usr/bin:/bin")
                environment("GOPATH", "")
                environment("GOBIN", "")
            }
        }
    }

    // Tier 2: BasePlatformTestCase fixtures, which need a real IDE environment.
    // Kept out of `test` so ./gradlew test stays fast and IDE-free.
    testIde.register("testPlatform") {
        type = IntelliJPlatformType.IntellijIdea
        version = "2026.1.4"
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    // The built-in runIde launches the IntelliJ IDEA the plugin is compiled against.
    // It gets the same probe wiring as runGoLand and runAndroidStudio so the smoke
    // test can drive all three: without the project argument the IDE stops at the
    // welcome screen, no file is ever opened, and a run that started perfectly well
    // reports that the language server never started.
    runIde {
        providers.gradleProperty("mondooProbeProject").orNull
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.forEach { args(it) }
        systemProperty("idea.trust.all.projects", "true")
        systemProperty("jb.consents.confirmation.enabled", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("ide.show.tips.on.startup.default.value", "false")
        systemProperty("mondoo.selfcheck", "true")
    }
    test {
        useJUnitPlatform()
    }
}
