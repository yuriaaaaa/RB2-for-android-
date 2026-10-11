plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.deivid22srk.restuff"
    compileSdk = 35

    defaultConfig {
        // Internal Kotlin package/namespace stays com.deivid22srk.restuff on
        // purpose: the JNI symbols in native/jni/android_main.cpp are bound to
        // those class names. Only the installed app id changes.
        applicationId = "com.rexglue.rb2"
        minSdk = 26
        targetSdk = 35
        // versionCode acompanha o número do run do GitHub Actions: cada build
        // nova instala por cima da anterior (in-place upgrade). O workflow de
        // release (release.yml) sobrepõe via RESTUFF_VERSION_CODE =
        // AAAAMMDD*100 + <run do release.yml> — sempre maior que qualquer run
        // number do build.yml e monotônico no tempo (segunda release no mesmo
        // dia ganha sufixo +N).
        versionCode = System.getenv("RESTUFF_VERSION_CODE")?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // release.yml sobrepõe o nome da versão (ex: "1.1.0", casando com a tag
        // da GitHub Release); builds de CI continuam com "1.0.<run>".
        versionName = System.getenv("RESTUFF_VERSION_NAME")
            ?: "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // ABI única: o recomp (SIMDE/NEON) + SDL3 + Vulkan visam arm64 moderno.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A lib nativa (librestuff.so) é construída por scripts/build_android_native.sh
    // (CMake + NDK, incluindo codegen do recomp) e empacotada de jniLibs.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    // No signing keys or passwords are kept in the repository.
    // Debug: keystore/debug.keystore if you add one (password "android"), so debug builds
    // update over each other; otherwise the standard ~/.android/debug.keystore.
    // Release: set RB2_STORE_PASSWORD (and RB2_KEY_ALIAS / RB2_KEY_PASSWORD) and put your
    // key at keystore/release.keystore or point RB2_KEYSTORE at it. Without them the release
    // APK is signed with the debug key.
    val debugKeystore = rootProject.file("keystore/debug.keystore")
    val releaseKeystore = System.getenv("RB2_KEYSTORE")?.let { file(it) }
        ?: rootProject.file("keystore/release.keystore")
    val releaseStorePassword = System.getenv("RB2_STORE_PASSWORD")
    val hasReleaseKey = releaseKeystore.isFile && !releaseStorePassword.isNullOrEmpty()

    signingConfigs {
        if (debugKeystore.isFile) {
            getByName("debug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = System.getenv("RB2_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("RB2_KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // OBRIGATÓRIO p/ AdrenoTools (driver Turnip custom): os hooks
            // (libmain_hook.so etc.) precisam existir COMO ARQUIVOS em
            // nativeLibraryDir — com descompactação desligada o Android lê
            // os .so direto do APK e o hookLibDir do adrenotools não aponta
            // para arquivos reais (o hook falha e o driver do sistema é
            // usado em silêncio, ou 0 devices são enumerados).
            useLegacyPackaging = true
            // NOTA (ARM PERF/diagnóstico): as line tables do librestuff.so
            // NÃO ficam no APK — o build_android_native.sh stripa --strip-debug
            // a lib para o jniLibs e preserva a versão com debug em
            // librestuff.so.unstripped, que o CI upa como artefato separado
            // "naughtybear-restuff-debug-symbols". Backtraces "module+0xOFFSET"
            // se resolvem offline com llvm-symbolizer contra ESSE artefato.
        }
    }
}

dependencies {
    // Núcleo Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose (BOM alinha todas as versões)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Ícones completos (Folder, ErrorOutline etc.) — core tem só o subconjunto básico
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ViewModel + ciclo de vida Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navegação entre a tela de seleção e a tela de Configurações
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // DocumentFile — navegação em pastas via Storage Access Framework
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testes de unidade (JVM puro — sem Robolectric):
    // ActiveFileStoreTest cobre o fix do ENOENT do Vortek e o self-heal
    // do reconcile (roda no CI antes do build nativo de ~45min).
    testImplementation("junit:junit:4.13.2")
}
