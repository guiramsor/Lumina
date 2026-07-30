import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Las credenciales de Supabase se leen del mismo .env.local que usa la app de
// escritorio, para no tener dos sitios donde mantenerlas. Ese archivo esta en
// .gitignore, asi que una compilacion sin el produce una app sin
// sincronizacion, pero perfectamente funcional en local.
val entorno = Properties().apply {
    val archivo = rootProject.file("../.env.local")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
fun entorno(clave: String): String = entorno.getProperty(clave, "").trim()

// La firma de release vive fuera del repositorio, como las credenciales.
//
// Sin `keystore.properties` el proyecto compila igual, pero el APK de release
// sale **sin firmar**: no se instala en ningun telefono ni se sube a Play. Era
// el estado hasta ahora, y explica por que lo unico instalable era la
// compilacion de depuracion, que va marcada DEBUGGABLE y firmada con la clave
// de debug de Android Studio. Eso es justo lo que Play Protect mira con lupa.
val firma = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
val almacenDeFirma = firma.getProperty("storeFile")?.let { rootProject.file(it) }
val hayFirma = almacenDeFirma?.exists() == true

android {
    namespace = "com.lumina.audiolibros"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lumina.audiolibros"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${entorno("VITE_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${entorno("VITE_SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        if (hayFirma) {
            create("release") {
                storeFile = almacenDeFirma
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
                // v1 (firma JAR) solo hace falta por debajo de API 24 y aqui
                // el minimo es 26, asi que sobra. v2 y v3 son las que valida
                // Android moderno, y v3 es la que permitiria rotar la clave
                // algun dia sin perder la identidad de la app.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hayFirma) signingConfig = signingConfigs.getByName("release")
            // Sin ofuscar ni encoger: es una app personal y R8 puede romper
            // Compose o Media3 por reflexion sin avisar. Si algun dia se
            // publica en Play conviene activarlo y volver a probar en serio.
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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}