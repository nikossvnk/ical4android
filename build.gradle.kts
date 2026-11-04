import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

val ical4jVersion = "4.4.0"

android {
    namespace = "at.bitfire.ical4android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23        // Android 6.0

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "version_ical4j", "\"$ical4jVersion\"")
    }

    compileOptions {
        // ical4j uses the Java 8 Time API
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/*.md")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.directories += "opentasks-contract/src/main/java"
        }
    }

    lint {
        disable += setOf("AllowBackup", "InvalidPackage")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    api("org.mnode.ical4j:ical4j:$ical4jVersion")
    api("org.threeten:threeten-extra:1.11.0")
    // ThreeTen-Extra leaves these annotations optional; R8 needs their actual classes.
    // Use the current Java 8-compatible Joda-Convert line; 3.x requires Java 21.
    runtimeOnly("org.joda:joda-convert:2.2.4")
    runtimeOnly("org.jspecify:jspecify:1.0.1")
    // ical4j logs with slf4j, ical4android uses java.util.logging
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    implementation("androidx.core:core-ktx:1.19.1")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
