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
    versionCode = 6
    versionName = "1.4.0"
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
  buildFeatures {
    compose = true
    buildConfig = true
  }

  // standard: 그대로 설치되는 기본판 / full: 화면 조작(접근성)까지 들어간 전체판
  flavorDimensions += "edition"
  productFlavors {
    create("standard") {
      dimension = "edition"
      buildConfigField("boolean", "SCREEN_CONTROL", "false")
    }
    create("full") {
      dimension = "edition"
      buildConfigField("boolean", "SCREEN_CONTROL", "true")
    }
  }
  packaging { jniLibs { pickFirsts.addAll(listOf("**/liblitertlm_jni.so", "**/libc++_shared.so")) } }
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
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.onnxruntime.android)
  debugImplementation(libs.androidx.ui.tooling)
}
