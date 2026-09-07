rootProject.name = "mondoo-intellij-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle provision the JDK 21 toolchain automatically when it is not
    // installed locally. 1.0.0 is the first release that works on Gradle 9: 0.9.0
    // reads JvmVendorSpec.IBM_SEMERU, which Gradle 9 removed, so it threw
    // NoSuchFieldError the moment it was actually needed. CI never hit that, because
    // setup-java installs 21 and the resolver is only consulted when no local JDK
    // matches — so this only ever broke `./gradlew test` on a developer machine
    // without a JDK 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
