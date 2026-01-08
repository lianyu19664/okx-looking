pluginManagement {
    repositories {
        // 新增：Google 托管的 Maven 镜像，专为 CI 环境优化，避免 429 限流
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 新增：Google 托管的 Maven 镜像
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        
        google()
        mavenCentral()
    }
}
rootProject.name = "PhaseDetector"
include(":app")
include(":core")
include(":network")
include(":data")