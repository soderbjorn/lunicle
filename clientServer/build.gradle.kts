import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Only the two targets Stage 1 actually ships: the JVM server and the
    // browser bundle. Android/iOS targets are deliberately absent — this
    // tracker has no mobile client and adding targets "just in case" costs
    // build time on every compile. See docs/stages.html.
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
            // `api`, not `implementation`: the DTOs are @Serializable and the
            // HTTP client's response type is CounterState, so every consumer
            // of this module compiles against these.
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.ktor.client.contentNegotiation)
            api(libs.ktor.client.serializationKotlinxJson)
            api(libs.kotlinx.coroutines.core)
        }
        jsMain.dependencies {
            // The engine is resolved from the classpath by the argument-less
            // `HttpClient { … }` constructor in LunicleApi — exactly one engine
            // per target, so there is nothing to disambiguate.
            implementation(libs.ktor.client.js)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
