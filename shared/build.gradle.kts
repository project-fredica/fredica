import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    kotlin("plugin.serialization") version "2.3.0"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.json.schema.validator)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.openai.client)
//            implementation(kotlin("reflect"))
            implementation(libs.kotlin.reflect)
            implementation(libs.ktor.client.okhttp)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            //noinspection UseTomlInstead
            implementation("com.google.guava:guava:33.5.0-android")
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.logback)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.cors)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation("org.burningwave:core:12.66.2") {
                exclude("ch.qos.logback", "logback-classic")
            }
            implementation(libs.vertx.core)
            //noinspection UseTomlInstead
            implementation("com.google.guava:guava:33.5.0-jre")
            implementation(libs.vertx.core)
            implementation(libs.vertx.lang.kotlin.coroutines)
            implementation(libs.vertx.jdbc.client)
            // https://mvnrepository.com/artifact/org.xerial/sqlite-jdbc
            implementation(libs.sqlite.jdbc)
        }
    }
}

android {
    namespace = "com.github.project_fredica.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
