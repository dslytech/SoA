plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apkcompatpatcher.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.apkcompatpatcher.android"
        minSdk = 26
        targetSdk = 34
        // Bumped with every dex/manifest fix from here on (see README.md's numbered fix list
        // for what each one is) specifically so it's possible to tell, from the device alone -
        // Settings > Apps > Save old APK > App info shows versionName, no rebuild-log-diffing
        // needed - whether a freshly-installed build actually contains the latest fix. Before
        // this, versionCode/versionName never changed across 16 real fixes, so there was no way
        // to confirm from the device whether a given retest used the build it was meant to.
        versionCode = 117
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    // dexlib2 and apksig are plain-JVM libraries pulled in below; nothing
    // here needs native code, so no NDK/CMake config is required.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // ARSCLib (see below) bundles a full copy of every Android framework's
            // resource table (android-23.apk .. android-37.apk, ~2.8MB total) as
            // classpath resources, for its own dynamic "resolve an android:foo
            // attribute name against a real framework" API. This app doesn't use
            // that API at runtime (see ManifestPatcher.ID_required's doc comment -
            // the one attribute id it would have resolved is hardcoded instead,
            // already cross-checked against that same table offline), so there's
            // no reason to ship 2.8MB of unused framework blobs in the APK.
            excludes += "frameworks/**"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // dex/smali-level editing - the exact engine apktool wraps internally
    // (decode dex -> smali text -> edit -> reassemble), maintained by the
    // Android team as the successor to jesusfreke/smali+dexlib2.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("com.android.tools.smali:smali:3.0.9")

    // APK v1/v2/v3 signing - the same library apksigner.jar and Android
    // Studio itself use. Pure JVM, works fine on-device under ART.
    implementation("com.android.tools.build:apksig:9.3.2")

    // Proper (non-corrupting) binary AndroidManifest.xml element insertion -
    // see ManifestPatcher.addUsesLibrary()'s doc comment for why ManifestPatcher's
    // own hand-rolled AXML code deliberately never attempts this itself. Pure
    // JVM, no native code, works fine on-device under ART (same reasoning as
    // apksig/smali above).
    implementation("io.github.reandroid:ARSCLib:1.4.0")
}
