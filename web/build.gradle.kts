plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(projects.clientServer)
            implementation(projects.client)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
