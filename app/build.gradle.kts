// Imports must sit above `plugins`: inside a Gradle Kotlin script a bare `java.util.*` reference
// resolves against the `java` project extension, not the JDK package.
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Stamped into BuildConfig so the About panel can name the exact build that is installed. Debug
// APKs are side-loaded by hand rather than updated from a store, so "which build is this?" is a
// question the app itself has to be able to answer.
val buildStamp: String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

// Release signing is optional and is never required to build: the properties file is read when it is
// there, and the release variant simply comes out unsigned when it is not. The keystore and its
// password live next to it, outside version control — docs/BUILD.md §Release 签名 explains how to
// point this at your own key before uploading anything to a store.
val releaseKeystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.lumobrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lumobrowser.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.09"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en", "zh", "zh-rTW")

        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")
    }

    signingConfigs {
        // Created only when the properties file exists, so a checkout without a keystore still builds
        // every other variant — including `assembleRelease`, which then produces an unsigned APK.
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            if (releaseKeystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Size is not the point of this variant. It exists because a side-loaded debug build can
            // be rejected or killed outright by some OEM ROMs, and because a release build has to be
            // signed with a real key to be installed like one. R8 is therefore left off: it has never
            // been validated on a device here, the JS bridge is reflection-bound, and a shrinker bug
            // would be indistinguishable from the start-up crash this variant is meant to help
            // diagnose.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                // The unit-test JVM is a separate process from the Gradle daemon, and Gradle's
                // default for it is 512 MB. `LanguageSwitchLaunchTest` inflates the real Activity
                // plus the whole Compose tree under Robolectric and runs for well over two minutes
                // on its own; at 512 MB it dies with `OutOfMemoryError at OnPositionedDispatcher.kt`
                // depending on what else the build happens to be doing at that moment. That is an
                // under-provisioned worker, not a defect in the code under test, so the heap is
                // raised here rather than the test being weakened or excluded.
                it.maxHeapSize = "2g"

                // Robolectric normally downloads its `android-all-instrumented` runtime jar from
                // Maven Central on first use. On machines that cannot complete a TLS revocation
                // check that fetch stalls indefinitely and the test task never finishes, so a
                // pre-fetched directory can be used instead (see docs/BUILD.md §离线测试). It is
                // opt-in rather than the default: a fresh clone has no such directory, and
                // `-Drobolectric.offline=true` pointed at a missing folder fails every test with
                // "couldn't find an android-all jar" instead of simply downloading one.
                resolvedRobolectricDepsDir()?.let { depsDir ->
                    it.systemProperty("robolectric.offline", "true")
                    it.systemProperty("robolectric.dependency.dir", depsDir)
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

/**
 * Directory holding pre-fetched Robolectric SDK jars, or `null` when there is none.
 *
 * Resolution order keeps the project portable: an explicit `robolectric.deps.dir` Gradle property
 * wins, then `ROBOLECTRIC_DEPS` in the environment, then a developer-local folder. A directory that
 * does not exist resolves to `null`, which leaves Robolectric in its normal online mode — the
 * setting is a workaround for one machine's TLS revocation check, not a requirement of the build.
 */
fun resolvedRobolectricDepsDir(): String? {
    val configured =
        (findProperty("robolectric.deps.dir") as String?)
            ?: System.getenv("ROBOLECTRIC_DEPS")
            ?: "D:/robolectric-deps"
    val dir = File(configured)
    return if (dir.isDirectory) dir.absolutePath else null
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Back-ports the platform splash window to API 26+ and, on API 31+, is what hands the launch
    // window its `windowSplashScreenAnimatedIcon`. Without it the cold-start launch window is a flat
    // `windowBackground` with nothing in it — the "black screen before the animation" this app used
    // to open with.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)

    // Favicons. Coil is used purely as an image loader for the card favicons and the site icons
    // served by DuckDuckGo's public icon endpoint, so a card shows the real site mark instead of a
    // generated placeholder. Images are cached on disk by Coil; the app itself never uploads
    // bookmarks, history or settings anywhere (see docs/TESTING.md §Privacy).
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.junit)

    // Compose UI testing on the JVM: lets the real MainActivity be launched and composed under
    // Robolectric, so a startup crash is reproducible without a device or emulator.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
