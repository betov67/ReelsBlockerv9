// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// No separate Kotlin plugin is declared here: AGP 9.0+ ships Kotlin support
// built in (https://developer.android.com/build/migrate-to-built-in-kotlin),
// pulling in its own compatible Kotlin Gradle Plugin version automatically.
plugins {
    id("com.android.application") version "9.4.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
