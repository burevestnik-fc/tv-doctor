plugins {
    // AGP 9 содержит встроенную поддержку Kotlin — отдельный плагин kotlin-android не нужен
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
