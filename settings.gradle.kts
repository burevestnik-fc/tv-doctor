pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Нужен библиотеке Kadb (spake2-java для сопряжения по коду)
        maven("https://jitpack.io")
    }
}

rootProject.name = "TvDoctor"
include(":app")
