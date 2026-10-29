plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobile.nodeide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobile.nodeide"
        minSdk = 26
        // targetSdk 28 mantém a permissão de executar binários gravados no diretório
        // privado do app (regra W^X do Android 10+). Sem isso, um Node.js importado
        // ou baixado em files/usr/bin não poderia ser executado. É a mesma escolha
        // feita pelo Termux.
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    packaging {
        jniLibs {
            // Extrai libnode.so (quando empacotado) para nativeLibraryDir, onde o
            // arquivo pode ser executado com permissão de execução.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("com.google.android.material:material:1.12.0")
    // XZ: índices Packages.xz e o data.tar.xz dos pacotes .deb do Termux.
    implementation("org.tukaani:xz:1.9")
}
