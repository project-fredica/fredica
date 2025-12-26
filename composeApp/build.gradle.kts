import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            api("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")

            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.vertx.core)
        }
    }
}

android {
    namespace = "com.github.project_fredica"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.github.project_fredica"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.github.project_fredica.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Fredica"
            packageVersion = "1.0.0"

            appResourcesRootDir = rootDir.resolve("desktop_assets")

            description = packageName
            vendor = "zh.jobs@foxmail.com"
            includeAllModules = true

            linux {
                shortcut = true
                appCategory = "Education"
            }

            windows {
                shortcut = true
                upgradeUuid = "06fc04c7-aa95-4e56-886f-75ba080069b5"
                menu = true
                console = true
                perUserInstall = true
            }

            macOS {

            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.github.project_fredica.resources"
    generateResClass = auto
}

//afterEvaluate {
//    tasks.withType<JavaExec> {
//        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
//        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
//
//        if (System.getProperty("os.name").contains("Mac")) {
//            jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
//            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
//            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
//        }
//    }
//}

