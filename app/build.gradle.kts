plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.aria.memo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aria.memo"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.12.16-p8"

        // Fix-W3 / Fix-WidgetBdd: required for instrumented test discovery on
        // emulator (CI android-instrumented job) — without this the runner
        // can't find @RunWith(AndroidJUnit4::class) classes.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Room schema export so migrations can be diffed in git.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)

    // Material Components (XML Theme.Material3.*)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // DataStore (non-secret config)
    implementation(libs.androidx.datastore.preferences)

    // Security — EncryptedSharedPreferences for the PAT
    implementation(libs.androidx.security.crypto)

    // Compose (BOM pins version for all compose-* modules)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation (single-activity Compose nav)
    implementation(libs.androidx.navigation.compose)

    // Calendar month view
    implementation(libs.calendar.compose)

    // Glance (AppWidget)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Room (local database, single source of truth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager (background sync)
    implementation(libs.androidx.workmanager)

    // AndroidX Startup — explicit so R$string survives `nonTransitiveRClass=true`
    // (WorkManager pulls it transitively; without a direct impl the inner R class
    // is stripped and AppInitializer crashes on first install). See P5 BDD crash.
    implementation(libs.androidx.startup)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // Fix-W1: Robolectric + AndroidX test-core lift JVM unit tests onto a real
    // Android framework. Used by MemoWidgetIntegrationTest / WidgetCrossDayTest /
    // WidgetDebounceIntegrationTest to exercise Glance widgets end-to-end without
    // an emulator. testOptions.unitTests.isIncludeAndroidResources is already
    // enabled above — that's what lets Robolectric load merged manifest + R class.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Fix-WidgetBdd: instrumented (emulator) test deps for WidgetSmokeTest +
    // WidgetBddTest. Run on CI's android-instrumented job (api-level 33 emu).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
