import java.util.Properties

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
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SMS_GATEWAY_NUMBER", "\"\"")
    }

    // URL del backend (Ktor, Módulo 4):
    // - debug: 10.0.2.2 funciona en emuladores; en teléfono físico senderoApiUrl se lee de local.properties.
    // - release: el servidor en la nube (Azure).
    // Para un teléfono físico: .\gradlew.bat :app:installDebug -PsenderoApiUrl=http://<IP-PC>:8080/api/v1/
    val urlLocal = "http://10.0.2.2:8080/api/v1/"
    val propiedadesLocales = Properties().apply {
        val archivo = rootProject.file("local.properties")
        if (archivo.isFile) archivo.inputStream().use { load(it) }
    }
    val urlLocalTelefono = propiedadesLocales.getProperty("senderoApiUrl")
    val urlNube = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1/"
    val urlForzada = providers.gradleProperty("senderoApiUrl").orNull

    // Firma del APK release. La llave y su contraseña viven fuera del repo y se leen de local.properties
    // (senderoFirmaArchivo, senderoFirmaAlias, senderoFirmaClave). Sin ellas el release sale sin firmar.
    val firmaArchivo = propiedadesLocales.getProperty("senderoFirmaArchivo")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (firmaArchivo != null) {
            create("release") {
                storeFile = firmaArchivo
                storePassword = propiedadesLocales.getProperty("senderoFirmaClave")
                keyAlias = propiedadesLocales.getProperty("senderoFirmaAlias")
                keyPassword = propiedadesLocales.getProperty("senderoFirmaClave")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${urlForzada ?: urlLocalTelefono ?: urlLocal}\"")
            val smsGatewayNumber = providers.gradleProperty("senderoSmsGatewayNumber").orNull.orEmpty()
            require(smsGatewayNumber.isBlank() || Regex("\\+?[0-9]{10,15}").matches(smsGatewayNumber)) {
                "senderoSmsGatewayNumber debe tener de 10 a 15 dígitos, opcionalmente con + inicial"
            }
            buildConfigField("String", "SMS_GATEWAY_NUMBER", "\"$smsGatewayNumber\"")
        }
        release {
            if (firmaArchivo != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"${urlForzada ?: urlNube}\"")
            buildConfigField("String", "SMS_GATEWAY_NUMBER", "\"\"")
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // --- Módulo 1 (identidad)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.maplibre.android)
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