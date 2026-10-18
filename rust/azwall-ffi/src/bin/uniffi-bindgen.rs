//! Generates the Kotlin and Swift bindings (`scripts/build-android.sh`, `scripts/build-ios.sh`).
fn main() {
    uniffi::uniffi_bindgen_main()
}
