plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持已内置，无需再 apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.richkobe.capturetophotopicker"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.richkobe.capturetophotopicker"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
        // KSP 编译器依赖 BuildConfig 识别模块包名，必须开启（项目 3 已验证的硬前置）
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            // 多个 AAR（yukihook/libxposed/kavaref）自带 META-INF 元数据，去重防打包冲突
            excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // ---- YukiHook 1.5.0（BOM 统一版本，implementation 与 ksp 两个配置都要挂 BOM）----
    platform(libs.yukihook.bom).apply {
        implementation(this)
        ksp(this)
    }
    implementation(libs.yukihook.core)
    ksp(libs.yukihook.compiler)
    // libxposed 底座：对接 Modern Xposed API 101+（本机 LSPosed JingMatrix 2.2.1 / API 102）
    implementation(libs.yukihook.runtime.libxposed)
    // KavaRef 语法糖（resolve()/method{}/firstMethod{} 等 DSL 桥接）
    implementation(libs.yukihook.extension.kavaref)

    // ---- libxposed 官方库 ----
    // api 由框架运行时注入，务必 compileOnly 不打包
    compileOnly(libs.libxposed.api)
    // service 提供 XposedService Binder 通道（requestScope/getScope/removeScope 作用域同步），打包进模块
    implementation(libs.libxposed.service)

    // ---- KavaRef（版本由 BOM 锁定 1.1.1，与 yukihook-core 1.5.0 POM 一致）----
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.kavaref.extension)

    // ---- 模块自身 M3 界面 ----
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
