# Azwall

A mobile wallet for [Aztec](https://aztec.network), native on both platforms:

- **Android**: Kotlin + Jetpack Compose (`android/`)
- **iOS**: Swift + SwiftUI (`ios/`)
- **Rust core** shared by both (`rust/`), exposed through [uniffi](https://mozilla.github.io/uniffi-rs/):
  network registry, RPC configuration, node checks, and the native Chonk prover (Barretenberg).

The app supports **several Aztec versions at once** (today testnet v6 only), each with its own
Barretenberg release, side by side in one process. Documentation: https://azwall.github.io/azwall/
(sources in [docs/](docs/)).

It builds on the findings of the [azwall-prototype](https://github.com/youtpout/azwall-prototype)
(Tauri), whose benchmarks showed native proving is practical on a phone.

## Layout

```
networks.json        Supported networks: Aztec version, Barretenberg release, expected chain values
.env.example         RPC endpoints per network (copy to .env)
rust/azwall-core     Registry, .env resolution, node JSON-RPC client and compatibility check
rust/azwall-prover   Multi-release Chonk prover (dlopen per Barretenberg release)
rust/azwall-ffi      uniffi API for Kotlin and Swift
pxe/                 PXE hosts in JavaScript, one per Aztec version (v6 today), run in a hidden web view
android/             Gradle project: core (bindings wrapper) + app (Compose UI)
ios/                 AzwallKit (wallet logic + platform), AzwallRust (FFI wrapper), Azwall app (XcodeGen)
scripts/             Native builds, bindings, CRS, tests, size limits
```

## Prerequisites

| | Android (Linux or macOS) | iOS (macOS only) |
|---|---|---|
| Rust | ≥ 1.89 (`rustup`) | same |
| Rust targets | `aarch64-linux-android x86_64-linux-android` | added by `scripts/build-ios.sh` |
| Platform tools | JDK 17+, Android SDK (platform 37), NDK 27, [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) | Xcode with Swift ≥ 6.4, [XcodeGen](https://github.com/yonaskolb/XcodeGen) |
| PXE hosts | Node ≥ 22, pnpm | same |
| Barretenberg | CMake ≥ 3.24, Ninja, Python 3, git | same (`brew install cmake ninja xcodegen`) |

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
export NDK_HOME=$HOME/Android/Sdk/ndk/27.3.13750724   # your NDK; macOS: ~/Library/Android/sdk/ndk/…
```

## Configuration

```bash
cp .env.example .env
```

Set `AZWALL_RPC_<NETWORK_ID>` for each network (empty = the public `defaultRpc` from
`networks.json`). The build resolves both files into `assets/azwall-config.json`, which the apps
bundle. Values end up in the app binary: only use keys restricted by quota or origin.

## Build

Three things are built outside Gradle and Xcode, once, then again only when they change:

| Step | What | When to rerun |
|---|---|---|
| `scripts/fetch-crs.sh` | Proving setup files (≈ 20 MiB), bundled by both apps | Never (shared by every release) |
| Barretenberg | The native prover library, one per release in `networks.json` | A network gets a new `barretenberg` release |
| Rust core | `rust/azwall-ffi` and its Kotlin/Swift bindings | Any change under `rust/` |

The PXE hosts (`pxe/`) and `assets/azwall-config.json` are built by Gradle and by Xcode's pre-build
step on every build; nothing to run by hand.

### Barretenberg

Aztec proofs are made by [Barretenberg](https://github.com/AztecProtocol/aztec-packages/tree/master/barretenberg),
Aztec's C++ prover, built from source for the phone. Each network pins the release it needs
(`barretenberg` in `networks.json`), and the app can load several releases side by side (one per
Aztec version): each release becomes one self-contained library exporting only two
symbols, which the Rust prover opens at run time (`rust/azwall-prover/src/library.rs`).

The scripts clone the release's sources (sparse checkout of `aztec-packages` at tag
`v<release>`, into `vendor/`), build them with CMake and Ninja, and wrap them:

```bash
scripts/build-bb-android.sh all arm64-v8a           # phones; add x86_64 for the emulator
scripts/build-bb-ios.sh all                         # macOS: device + Apple Silicon simulator
scripts/build-bb-android.sh 6.0.0 arm64-v8a    # or a single release
```

| | Output | Packaged by |
|---|---|---|
| Android | `vendor/bb-shared/<abi>/libbb_v<release>.so` | `scripts/build-android.sh`, into `jniLibs` |
| iOS | `vendor/bb-ios/AzBB_<release>.xcframework` (`.` and `-` become `_`) | Xcode, embedded (not linked) |

Count about 10 minutes per release and per ABI (or per iOS SDK) on an 8-core machine, and a few
GB of disk for the sources and build trees in `vendor/`. Without a library the app still runs, but
sending a transaction fails with a message naming the missing release.

### Android

```bash
scripts/fetch-crs.sh
scripts/build-bb-android.sh all arm64-v8a
scripts/build-android.sh arm64-v8a      # Rust core, Kotlin bindings, and the Barretenberg libraries into jniLibs
cd android && ./gradlew installDebug    # builds the PXE hosts too, installs on the connected phone
```

Run `scripts/build-android.sh` again after any Rust change: an app whose bindings and native
library come from different builds fails at start.

### iOS (macOS)

```bash
scripts/fetch-crs.sh
scripts/build-bb-ios.sh all
scripts/build-ios.sh                    # Rust core into build/ios/AzwallFFI.xcframework, Swift bindings
cd ios && xcodegen generate && open Azwall.xcodeproj
```

In Xcode, pick your team under Signing & Capabilities (`xcodegen generate` resets it), then run on a
device or an Apple Silicon simulator. Xcode's pre-build step builds the PXE hosts and the network
config (it needs `cargo` and `pnpm` in `~/.cargo/bin`, `/opt/homebrew/bin` or `/usr/local/bin`).
Run `scripts/build-ios.sh` again after any Rust change.

### App icon

`branding/azwall-logo.png` (cream nested diamonds, a pyramid seen from above, on purple) is the source of every icon and logo; after changing it:

```bash
scripts/make-icons.sh   # needs ImageMagick 7
```

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

See [AGENTS.md](AGENTS.md): English everywhere, at most 600 lines per file and 50
lines per function (`scripts/check-limits.py`).

## License

Azwall is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.
