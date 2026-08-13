pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TaskLinker"
include(":app")             // 客户端 App（3 个 flavor，可同时安装 3 个实例）
include(":server")          // 服务端 App（独立进程运行调度服务）
include(":tasklinker-api")  // 共享 AIDL 接口库（两端的 Parcelable/AIDL 必须完全一致）
