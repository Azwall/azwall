plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.azwall.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// uniffi bindings and the native libraries are generated from rust/ (scripts/), never edited.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/generated/kotlin")
    }
}

val repoRoot = rootDir.parentFile

// Regenerates the Kotlin bindings when the FFI crate changes (host build, library mode).
val generateUniffiBindings by tasks.registering(Exec::class) {
    workingDir = repoRoot
    commandLine("scripts/gen-bindings.sh", "kotlin")
    inputs.dir(repoRoot.resolve("rust/azwall-ffi/src"))
    inputs.file(repoRoot.resolve("rust/azwall-ffi/uniffi.toml"))
    outputs.dir(layout.projectDirectory.dir("src/generated/kotlin"))
}

tasks.named("preBuild") { dependsOn(generateUniffiBindings) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.webkit)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.jna)
    testImplementation(libs.kotlinx.coroutines.test)
}
