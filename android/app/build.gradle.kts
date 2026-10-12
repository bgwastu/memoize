plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "net.wastu.memoize"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "net.wastu.memoize"
        minSdk = 36
        targetSdk = 37
        versionCode = providers.gradleProperty("memoizeVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("memoizeVersionName").orElse("0.1.0").get()
    }
    signingConfigs {
        val keyPath = providers.environmentVariable("MEMOIZE_ANDROID_KEYSTORE").orNull
        if (keyPath != null) {
            create("distribution") {
                storeFile = file(keyPath)
                storePassword = providers.environmentVariable("MEMOIZE_ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MEMOIZE_ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MEMOIZE_ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        debug {
            val localTestId = providers.gradleProperty("memoizeDebugApplicationId").orNull
            require(localTestId == null || (testBuildType == "debug" && localTestId == "net.wastu.memoize.qa.debug")) {
                "memoizeDebugApplicationId is restricted to the isolated QA debug package"
            }
            applicationIdSuffix = localTestId?.removePrefix("net.wastu.memoize") ?: ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization { enable = true }
            signingConfig = signingConfigs.findByName("distribution")
            providers.gradleProperty("memoizeTestApplicationId").orNull?.let { testId ->
                require(testBuildType == "release" && testId in setOf("net.wastu.memoize.debug", "net.wastu.memoize.qa")) {
                    "memoizeTestApplicationId is restricted to the debug/qa package with testBuildType=release"
                }
                applicationIdSuffix = testId.removePrefix("net.wastu.memoize")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
        enable += "Deprecated"
        checkReleaseBuilds = true
    }
    sourceSets {
        named("main") { resources.directories.add("../../shared/assets") }
        named("test") {
            resources.directories.add("../../shared/fixtures")
            resources.directories.add("schemas")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}
room { schemaDirectory("$projectDir/schemas") }

// Robolectric runs the production BundledSQLiteDriver on the host, using the
// matching official JNI binary. No alternate database or in-memory fake is used.
val sqliteJvmNatives = configurations.create("sqliteJvmNatives") {
    isCanBeConsumed = false
    isTransitive = false
}
val sqliteHost = when {
    System.getProperty("os.name").startsWith("Mac") -> "osx"
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    else -> "linux"
} + when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "_arm64"
    "amd64", "x86_64" -> "_x64"
    else -> throw GradleException("Unsupported SQLite test host architecture")
}
val sqliteNativeDirectory = layout.buildDirectory.dir("sqlite-test-native")
val extractSqliteTestNative = tasks.register<Sync>("extractSqliteTestNative") {
    from(zipTree(sqliteJvmNatives.elements.map { it.single().asFile })) {
        include("natives/$sqliteHost/*")
        eachFile { relativePath = RelativePath(true, name) }
    }
    includeEmptyDirs = false
    into(sqliteNativeDirectory)
}
tasks.withType<Test>().configureEach {
    dependsOn(extractSqliteTestNative)
    systemProperty("java.library.path", sqliteNativeDirectory.get().asFile.absolutePath)
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.commonmark:commonmark:0.30.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    add(sqliteJvmNatives.name, "androidx.sqlite:sqlite-bundled-jvm:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
}
