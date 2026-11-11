plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.escom.sendero_seguro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.escom.sendero_seguro"
        minSdk = 26 // Android 8.0 (RNF14, dispositivo.android_api >= 26)
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // URL del backend (Ktor, Módulo 4):
    // - debug: el servidor en la PC. 10.0.2.2 es la PC anfitriona vista desde el emulador.
    // - release: el servidor en la nube (Azure).
    // Para probar la versión debug contra la nube: .\gradlew.bat :app:installDebug -PsenderoApiUrl=https://.../api/v1/
    val urlLocal = "http://10.0.2.2:8080/api/v1/"
    val urlNube = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1/"
    val urlForzada = providers.gradleProperty("senderoApiUrl").orNull

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${urlForzada ?: urlLocal}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"${urlForzada ?: urlNube}\"")
            optimization {
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
        buildConfig = true
    }
    // Pruebas unitarias en la PC: las clases de Android (Log, Looper, Handler) responden vacío en vez de tronar.
    // Lo necesitan las pruebas del Módulo 2, que crean GestorMalla y GestorCaminataSegura fuera del teléfono.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // --- Módulo 1 (identidad)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // --- Módulo 2 (emergencias): mapa
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}