pluginManagement {
    repositories {
        // NOTE: dl.google.com is unreachable from this network (404 on every
        // artifact). Mirrors that DO work here: repo.maven.apache.org (Central
        // direct) and maven.aliyun.com/repository/google (AGP + androidx only).
        // Aliyun does NOT mirror com.google.devtools.ksp — put mavenCentral()
        // FIRST so plugin markers on Central resolve before Aliyun is asked.
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
    }
}

rootProject.name = "NarcicTub"
include(":app")
