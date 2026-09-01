rootProject.name = "intellij-mondoo"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle provision the JDK 21 toolchain automatically when it is not
    // installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
