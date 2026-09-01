import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
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

intellijPlatform {
    pluginConfiguration {
        id = "com.mondoo.intellij"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

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
    }

    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
            // Settles whether Community carries com.intellij.modules.lsp.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1.4")
            create(IntelliJPlatformType.GoLand, "2026.1.4")
            create(IntelliJPlatformType.PyCharm, "2026.1.4")
            create(IntelliJPlatformType.WebStorm, "2026.1.4")
            create(IntelliJPlatformType.PhpStorm, "2026.1.4")
            create(IntelliJPlatformType.RubyMine, "2026.1.4")
            create(IntelliJPlatformType.CLion, "2026.1.4")
            create(IntelliJPlatformType.Rider, "2026.1.4")
            create(IntelliJPlatformType.RustRover, "2026.1.4")
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
        }
    }
    runIde.register("runPyCharm") {
        type = IntelliJPlatformType.PyCharm
        version = "2026.1.4"
        useInstaller = true
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
    test {
        useJUnitPlatform()
    }
}
