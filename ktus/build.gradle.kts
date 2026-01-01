import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.ldartools"
version = "0.0.1_alpha"

kotlin {
    jvm()
    androidLibrary {
        namespace = "com.ldartools.ktus"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation(libs.ktor.client.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "ktus", version.toString())

    pom {
        name = "Ktus"
        description = "Ktus is a multiplatform client library for uploading files using the tus resumable upload protocol to any remote server supporting it. It is build on top of the Ktor client library and supports all platforms supported by Ktor."
        inceptionYear = "2025"
        url = "https://github.com/LDARtools/Ktus"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "ldartools"
                name = "LDARtools, Inc."
                url = "https://github.com/LDARtools"
            }
            developer {
                id = "chrisg32"
                name = "Chris Gonzales"
                url = "https://github.com/chrisg32"
            }
        }
        scm {
            url = "https://github.com/LDARtools/Ktus"
            connection = "scm:git:git://github.com/LDARtools/Ktus.git"
            developerConnection = "scm:git:ssh://git@github.com/LDARtools/Ktus.git"
        }
    }
}
