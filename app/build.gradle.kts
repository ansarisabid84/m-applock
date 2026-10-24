import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Upload-key credentials, read from `keystore.properties` at the repo root.
 *
 * Both that file and the `.jks` it points at are gitignored, so a fresh clone still builds
 * and installs debug builds, and only a machine holding the real key can produce something
 * Play will accept. Copy `keystore.properties.template` to get started.
 *
 * Missing file is not an error: `bundleRelease` then produces an *unsigned* bundle, which is
 * still useful for checking that R8 and resource shrinking have not broken anything.
 */
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val hasUploadKey = signing.getProperty("storeFile") != null

android {
    namespace = "com.sabid.applock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sabid.applock"
        minSdk = 26
        targetSdk = 36
        // Play will not accept a replacement under a version code it has already seen, so
        // every rejection costs one. 1 was flagged for declaring READ_MEDIA_IMAGES /
        // READ_MEDIA_VIDEO. 5 carried a prominent disclosure for the AccessibilityService
        // grant and was rejected anyway: the disclosure was only reachable by tapping a
        // permission row, and the policy also requires it be shown "in the normal usage of
        // the app". It is now a step of first run — see ui/permissions/AccessibilityDisclosure.
        versionCode = 8
        versionName = "3.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Needed to host a Compose tree in a bare WindowManager window (LockOverlay): the
    // ViewTree*Owner setters live in these two artifacts, and Compose refuses to compose
    // in a view whose tree has no lifecycle or saved-state owner.
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.graphics:graphics-path:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Stable Room. The project was pinned to 2.7.0-alpha01, which shipped breaking
    // changes to the migration API mid-alpha; a lock app's database is not the place
    // to take that risk.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.biometric:biometric:1.1.0")

    // Pinned explicitly. androidx.biometric drags in fragment 1.2.5, whose FragmentActivity
    // does not call super.onRequestPermissionsResult() and uses invalid request codes — so
    // registerForActivityResult() permission launchers on it are unreliable, which is
    // precisely how granting notifications or the camera could blow up. Lint flags this as
    // a fatal error (InvalidFragmentVersionForActivityResult) rather than a style nit.
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")

    // No WorkManager. The intruder-photo retention sweep runs on app start and on unlock
    // (see AppLockApp), so the dependency was unused — and not harmlessly: work-runtime
    // merges ACCESS_NETWORK_STATE and WAKE_LOCK into the manifest, which then appear in the
    // Play listing's permission list for an app whose whole claim is that it has no reason
    // to touch the network.

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
