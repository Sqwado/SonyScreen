import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sqwado.sonyscreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sqwado.sonyscreen"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystore = rootProject.file("keystore.properties")
    val releaseProps = Properties().also { props ->
        if (releaseKeystore.exists()) {
            releaseKeystore.inputStream().use(props::load)
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                val storeFilePath = releaseProps.getProperty("storeFile")
                    ?: error("keystore.properties: missing storeFile (UTF-8 BOM?)")
                storeFile = rootProject.file(storeFilePath.trim())
                storePassword = releaseProps.getProperty("storePassword")
                    ?: error("keystore.properties: missing storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                    ?: error("keystore.properties: missing keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
                    ?: error("keystore.properties: missing keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
