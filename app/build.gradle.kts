import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.kunwooroh.gemmaagent"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.kunwooroh.gemmaagent"
    minSdk = 31
    targetSdk = 36
    versionCode = 2
    versionName = "1.1.0"
  }

  // 개인용 고정 서명키 (업데이트 설치가 계속 되도록 항상 같은 키로 서명)
  signingConfigs {
    create("personal") {
      storeFile = file("gemma-agent.keystore")
      storePassword = "gemma-agent-pass"
      keyAlias = "gemma"
      keyPassword = "gemma-agent-pass"
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("personal") }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("personal")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
  packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.material.icon.extended)
  implementation(libs.kotlin.reflect)
  implementation(libs.litertlm)
  debugImplementation(libs.androidx.ui.tooling)
}
