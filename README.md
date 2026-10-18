# Azwall

A mobile wallet for [Aztec](https://aztec.network), native on both platforms:

- **Android**: Kotlin + Jetpack Compose (`android/`)
- **iOS**: Swift + SwiftUI (`ios/`)
- **Rust core** shared by both (`rust/`), exposed through [uniffi](https://mozilla.github.io/uniffi-rs/):
  network registry, RPC configuration, node checks, and the native Chonk prover (Barretenberg).

The app supports **several Aztec versions at once** (testnet v5, testnet v6, …), each with its own
Barretenberg release, side by side in one process. See [docs/architecture.md](docs/architecture.md).

It builds on the findings of the [azwall-prototype](https://github.com/youtpout/azwall-prototype)
(Tauri), whose benchmarks showed native proving is practical on a phone.

## Layout

```
networks.json        Supported networks: Aztec version, Barretenberg release, expected chain values
.env.example         RPC endpoints per network (copy to .env)
rust/azwall-core     Registry, .env resolution, node JSON-RPC client and compatibility check
rust/azwall-prover   Multi-release Chonk prover (dlopen per Barretenberg release)
rust/azwall-ffi      uniffi API for Kotlin and Swift
android/             Gradle project: core (bindings wrapper) + app (Compose UI)
ios/                 AzwallKit (pure Swift), AzwallRust (FFI wrapper), Azwall app (XcodeGen)
scripts/             Native builds, bindings, CRS, tests, size limits
```

## Prerequisites

- Rust ≥ 1.89 with `rustup target add aarch64-linux-android x86_64-linux-android`
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk), JDK 17+, Android SDK (platform 37) and NDK 27
- For iOS: macOS, Xcode with Swift ≥ 6.4, [XcodeGen](https://github.com/yonaskolb/XcodeGen)
- To build Barretenberg: CMake ≥ 3.24, Ninja, Node ≥ 22, Python 3

```bash
export NDK_HOME=$HOME/Android/Sdk/ndk/27.3.13750724
```

## Configuration

```bash
cp .env.example .env
```

Set `AZWALL_RPC_<NETWORK_ID>` for each network (empty = the public `defaultRpc` from
`networks.json`). The build resolves both files into `assets/azwall-config.json`, which the apps
bundle. Values end up in the app binary: only use keys restricted by quota or origin.

## Build

Fetch the CRS (≈ 20 MiB, bundled by both apps):

```bash
scripts/fetch-crs.sh
```

Build every Barretenberg release listed in `networks.json` (≈ 10 min per release and ABI):

```bash
scripts/build-bb-android.sh all arm64-v8a
```

### Android

```bash
scripts/build-android.sh arm64-v8a x86_64
```

```bash
cd android && ./gradlew assembleDebug
```

### iOS (macOS)

```bash
scripts/build-ios.sh
```

```bash
cd ios && xcodegen generate && open Azwall.xcodeproj
```

Barretenberg is not yet built for iOS: the app runs, but the prover reports the missing library.

## Tests

```bash
scripts/test-all.sh
```

Runs the size rules, the Rust tests, the Android JVM tests and, when Swift is installed, the
AzwallKit tests. The live checks against the configured RPC endpoints are opt-in:

```bash
LIVE=1 scripts/test-all.sh
```

## Conventions

See [CONTRIBUTING.md](CONTRIBUTING.md): English everywhere, at most 600 lines per file and 50
lines per function (`scripts/check-limits.py`).
