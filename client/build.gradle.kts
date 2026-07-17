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
            // and constructs a LunicleApi, so both are part of this module's
            // public surface.
            api(projects.clientServer)
            api(libs.kotlinx.coroutines.core)
            // `implementation`, not `api`: the timestamps cross this module's
            // boundary as already-formatted strings, so :web never names a
            // datetime type and has no business seeing this on its compile
            // classpath. See Dates.kt.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
