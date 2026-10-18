# Conventions

- **English** for code, comments, docs and commit messages.
- **At most 600 lines per source file and 50 lines per function** (Rust, Kotlin, Swift).
  `scripts/check-limits.py` enforces it and runs first in `scripts/test-all.sh`.
- **Tests** come with every change: Rust unit tests next to the code, integration tests in
  `rust/*/tests/`, JVM tests in `android/*/src/test/`, swift-testing in `ios/AzwallKit/Tests/`.
  Tests that need the network are `#[ignore]`d and run with `LIVE=1 scripts/test-all.sh`.
- **Secrets** stay in `.env` (gitignored). `.env.example` lists every key, without values.
- **Generated code** (uniffi bindings, `assets/`, native libraries) is never committed nor
  edited; regenerate it with the scripts in `scripts/`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat(prover): …`).
