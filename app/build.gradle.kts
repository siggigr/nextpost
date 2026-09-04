import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.secrets.gradle.plugin)
    alias(libs.plugins.google.services)
}

/**
 * Release signing credentials, read from a gitignored `keystore.properties` in the project root --
 * the same "secrets live in an untracked properties file" pattern as MAPS_API_KEY in
 * local.properties. See keystore.properties.template for the expected keys.
 *
 * The file is deliberately optional: a fresh clone (or any machine without the keystore) can still
 * sync, build debug, and run tests. What it cannot do is quietly produce an unsigned release --
 * see the assembleRelease/bundleRelease guard at the bottom of this file.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "is.siggi.nextpost"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "is.siggi.nextpost"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // file() resolves an absolute path as-is, so storeFile can (and should) point
                // outside the repo.
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")

            optimization {
                // R8 is off for this first release. AGP 9 already defaults `enable` to false; it
                // is stated explicitly because turning it on is a deliberate, separately-tested
                // change -- shrinker breakage surfaces as a runtime crash in the shipped APK, not
                // as a build failure, and there are no keep rules in this project yet.
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

/**
 * Without a signing config AGP does not fail -- it emits app-release-unsigned.apk, which installs
 * nowhere and is easy to hand out by mistake. Fail loudly instead.
 *
 * This validates what keystore.properties actually points at, not merely that it exists: a
 * filled-in properties file whose storeFile has not been created yet otherwise fails much deeper
 * in AGP's signing task with a far less obvious message.
 */
val releaseSigningProblem: String? = if (!hasReleaseSigning) {
    "No keystore.properties found at the project root, so the release build would be unsigned. " +
        "Copy keystore.properties.template to keystore.properties and fill in the path to the " +
        "release keystore and its passwords."
} else {
    val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = required.filter { keystoreProperties.getProperty(it).isNullOrBlank() }
    val store = keystoreProperties.getProperty("storeFile")?.let { file(it) }
    when {
        missing.isNotEmpty() ->
            "keystore.properties is missing values for: " + missing.joinToString()
        store != null && !store.isFile ->
            "keystore.properties points at a keystore that does not exist: " + store.path +
                " -- generate it with keytool before building a release. Note that a Java " +
                ".properties file treats a backslash as an escape character, so storeFile must " +
                "use forward slashes even on Windows."
        else -> null
    }
}

/*
 * Checked here at configuration time rather than in a task doFirst: this build runs with the
 * configuration cache enabled, and a doFirst closure reading the values above would capture the
 * build script object itself, which the configuration cache cannot serialize. Gating on the
 * requested task names keeps debug builds, tests and IDE sync working without the keystore.
 */
val buildsRelease = gradle.startParameter.taskNames.any { requested ->
    val name = requested.substringAfterLast(':')
    name.contains("Release") || name == "build" || name == "assemble" || name == "bundle"
}
if (buildsRelease && releaseSigningProblem != null) {
    throw GradleException(releaseSigningProblem)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.maps.compose)
    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)
}
