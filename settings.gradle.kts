pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // YukiHook(libxposed 底座) 依赖 io.github.libxposed:api / :service，托管于 Maven Central；
        // 兼容旧 xposed82 底座时才需 de.robv.android.xposed:api（本项目用 libxposed，可不依赖，保留以防万一）
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "CaptureToPhotoPicker"
include(":app")    // Demo 靶子（测试床，非交付物），包名 com.richkobe.capturetophotopicker.demo
include(":module") // LSPosed 模块「我没带身份证」（真正交付物），包名 com.richkobe.capturetophotopicker
