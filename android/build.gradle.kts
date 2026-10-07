plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

// OneDrive file locking makes Gradle's frequently replaced test/lint outputs
// unreliable. Keep all generated build data local and outside cloud sync.
val localBuildRoot = layout.dir(
    providers.environmentVariable("LOCALAPPDATA").map {
        file("$it/OuraHealthBridge/android-build")
    },
)
layout.buildDirectory.set(localBuildRoot)

subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
}
