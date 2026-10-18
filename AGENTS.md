# Agent rules

Rules for coding agents (and humans) working on this repository. Architecture and the
multi-version design: [docs/architecture.md](docs/architecture.md).

## Code

- **English only**: code, identifiers, comments, docs and commit messages.
- **At most 600 lines per source file** and **at most 50 lines per function/method** (Rust,
  Kotlin, Swift). Split modules and extract helpers before reaching the limit.
  `scripts/check-limits.py` enforces both.
- Match the surrounding style: doc comments on public items, comments explain *why*, not *what*.
- Never edit generated code (`android/core/src/generated/`, `ios/AzwallRust/Sources/AzwallCoreFFI/generated/`,
  `assets/`, `jniLibs/`, `build/`, `vendor/`): change the source (`rust/azwall-ffi`, `networks.json`, `.env`)
  and rerun the scripts in `scripts/`.

## Tests

- Every change comes with tests: Rust unit tests next to the code and integration tests in
  `rust/*/tests/`, JVM tests in `android/*/src/test/`, swift-testing in `ios/AzwallKit/Tests/`.
- App logic depends on the `WalletBackend` interface so it is tested with fakes, never against the
  generated bindings.
- Tests that need the network are `#[ignore]`d; run them with `LIVE=1 scripts/test-all.sh`.
- Run `scripts/test-all.sh` before every commit; it must pass.

## Configuration and secrets

- RPC endpoints go in `.env` (gitignored), one `AZWALL_RPC_<NETWORK_ID>` key per network in
  `networks.json`. Every key is listed, without value, in `.env.example`.
- Never commit `.env` or print its values; RPC URLs may carry API keys (show the host only).

## Versions

- Several Aztec versions are supported at once. Adding one follows "Adding an Aztec version" in
  `docs/architecture.md`: registry entry, `.env.example` key, Barretenberg binding if needed.
- Stay on the **latest stable toolchains** (Gradle, AGP, Kotlin, Compose, Swift tools, Rust crates)
  to avoid compatibility issues; upgrade rather than pin old versions. Exception: Aztec,
  Barretenberg and Noir versions are pinned exactly, per network.

## Git

- Small commits, [Conventional Commits](https://www.conventionalcommits.org/) (`feat(prover): …`),
  pushed as each change is finished.
- **No `Co-Authored-By` trailer** or any other AI attribution in commits or pull requests.
