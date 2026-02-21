import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    kotlin("plugin.serialization") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.4"
}

// Add KSP processor for common target
dependencies {
    add("kspCommonMainMetadata", "org.jetbrains.kotlinx:kotlinx-schema-ksp:0.0.2")
}



kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }

    @Suppress("DEPRECATION")
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")

            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.ktor.client.core)
                api(libs.kotlin.reflect)
                api(libs.json.schema.validator)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
                api(libs.openai.client)
                api(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.schema.annotations)
//                implementation(libs.kotlinx.serialization.json)
                api(libs.s3)
                // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime
                runtimeOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
                api(libs.yabapi.core)
                implementation("org.ktorm:ktorm-core:4.1.1")
                // Source: https://mvnrepository.com/artifact/com.github.seancfoley/ipaddress
                api("com.github.seancfoley:ipaddress:5.6.1")
            }
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
            implementation(libs.ktor.server.swagger)
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
            //noinspection NewerVersionAvailable,UseTomlInstead
            implementation("aws.smithy.kotlin:http-client-engine-okhttp-jvm:1.5.24")
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



tasks.withType<KotlinCompilationTask<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

//// Configure KSP arguments
//ksp {
//    arg("kotlinx.schema.withSchemaObject", "true")
//    arg("kotlinx.schema.rootPackage", "com.github.project_fredica")
//}
//
//tasks.named("compileKotlinJs") {
//    dependsOn("kspCommonMainKotlinMetadata")
//}
