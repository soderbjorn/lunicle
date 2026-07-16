import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    js {
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: :web builds the view against CounterBackingViewModel.State
            // and constructs an IssuesApi, so both are part of this module's
            // public surface.
            api(projects.clientServer)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
