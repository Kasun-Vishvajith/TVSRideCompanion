import java.util.Properties
plugins { id("com.android.application") }
val keys = Properties().apply { val f = rootProject.file("../key.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "dev.raider.companion"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.raider.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }
    signingConfigs {
        if (keys.getProperty("storeFile") != null) create("project") {
            storeFile = rootProject.file("../" + keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
