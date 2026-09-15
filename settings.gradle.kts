pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hermes-android"
include(":app")
