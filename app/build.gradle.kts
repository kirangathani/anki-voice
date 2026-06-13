import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read local.properties (gitignored). CI workflow writes ANTHROPIC_API_KEY here
// from the GitHub repo secret of the same name; locally devs can set it manually.
val localProperties: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val anthropicApiKey: String = localProperties.getProperty("ANTHROPIC_API_KEY", "").trim()

fun gitShortSha(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (_: Exception) { "unknown" }

val buildTimestamp: String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    .withZone(ZoneOffset.UTC).format(Instant.now())

android {
    namespace = "dev.kiran.ankivoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kiran.ankivoice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests (Tier 1). Pure logic only (no Android framework, no
    // emulator). Run with: ./gradlew testDebugUnitTest
    // Plain JUnit only for now; add kotlinx-coroutines-test when ReviewSession
    // coroutine tests need it. (Google Truth was dropped: its Gradle module
    // metadata does not match AGP's unit-test variant and silently fails to
    // land on the classpath.)
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM unit-test classpath. The android.jar stub throws
    // "not mocked" off-device, so classes that build/parse JSON (e.g.
    // LlmCommandClassifier) can only be unit-tested with the genuine library.
    testImplementation("org.json:json:20240303")

    // Instrumented UI tests (Tier 3). Run on an emulator/device via
    // ./gradlew connectedDebugAndroidTest. UIAutomator drives the real app
    // (taps buttons, reads the Compose accessibility tree).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
