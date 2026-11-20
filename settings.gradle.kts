pluginManagement {
    resolutionStrategy { eachPlugin {
        if (requested.id.id == "com.android.application") useModule("com.android.tools.build:gradle:${requested.version}")
    } }
    repositories { maven("https://maven.aliyun.com/repository/google"); google(); maven("https://maven.aliyun.com/repository/central"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven("https://maven.aliyun.com/repository/google"); google(); maven("https://maven.aliyun.com/repository/central"); mavenCentral() }
}
rootProject.name = "AutoHotspot"
include(":app")
