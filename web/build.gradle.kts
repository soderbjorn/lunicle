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
            // The darkness-toolkit web shell: app frame, top bar, floating
            // windows, theming. toolkit-core rides in transitively (api).
            implementation(libs.darkness.core)
            implementation(libs.darkness.web)
        }
        // The first tests in this module, and they need a browser rather than a
        // JVM: the serialiser reads a real DOM, which is the whole point of it —
        // a fake one would answer questions about the fake. `js { browser() }`
        // above already runs these in headless Chrome, exactly as :client's are.
        jsTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
