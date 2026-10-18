---
title: Development
nav_order: 4
---

# Development

The [README](https://github.com/youtpout/azwall#readme) has every command. In short:

```bash
cp .env.example .env            # RPC endpoint per network
scripts/fetch-crs.sh            # proving setup files, bundled by both apps
scripts/build-bb-android.sh all arm64-v8a   # one Barretenberg library per release
scripts/build-android.sh arm64-v8a          # Rust core + bindings for Android
cd android && ./gradlew assembleDebug
```

On macOS, `scripts/build-ios.sh` then `xcodegen generate` in `ios/` produce the Xcode project.

## Tests

```bash
scripts/test-all.sh
```

| Suite | What it covers |
|---|---|
| `scripts/check-limits.py` | At most 600 lines per file and 50 per function |
| Rust (`cargo test`) | Network registry, `.env` resolution, node client, proof framing, release switching, bindings |
| Rust, live (`LIVE=1`) | Every configured node answers and matches its network |
| Android (JVM) | View model and binding mappings, with a fake backend |
| iOS (`swift test`) | Wallet model, with a fake backend |

## Rules

Contributors and coding agents follow
[AGENTS.md](https://github.com/youtpout/azwall/blob/main/AGENTS.md): English everywhere, the size
limits, tests with every change, secrets only in `.env`, latest stable toolchains.
