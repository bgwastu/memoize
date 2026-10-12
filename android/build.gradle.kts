plugins {
    id("com.android.application") version "9.3.3" apply false
    // AGP's built-in Kotlin is retained; this pins its runtime compiler dependency.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}
