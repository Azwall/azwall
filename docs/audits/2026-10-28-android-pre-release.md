# Android pre-release audit — 2026-10-28

Reviewed revision: `f99b78b2b62918735e70cd2637c73475eb8bbf68`.

Decision: do not distribute this revision to external testers until findings 1–5 are resolved. The account persistence defect can remove an imported signing key from the vault. The staged prover also fails the native 16 KB alignment requirement.

Scope: repository source review of Android lifecycle, vault, browser and PXE bridges, dApp permissions and approvals, transaction tracking, recovery derivation, build configuration and privacy documentation; automated tests, Android lint and inspection of staged ARM64 ELF binaries. This is a pre-release engineering review, not a cryptographic certification or an exhaustive penetration test. No real wallet secrets were read or used. No publication was performed.

## Findings

### 1. P1 — Restarting a session can erase an imported account on the next vault write

Locations: `android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt:175`, `:215`, `:220`; `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:336`.

`AppViewModel.contents` is assigned when the wallet starts. Account creation and renaming update the session's separate `contents`, but never update the view model's copy. `restart()` and `switchNetwork()` create the next session from that stale copy. A subsequent account addition or rename serializes the stale list over the vault, removing accounts added since unlock. Names can also revert. Derived accounts can be recovered from the phrase; imported keys cannot be recovered from that phrase.

Confirmed with a temporary JVM test using a fake backend and a launcher simulating an engine failure:

1. Create and unlock a vault containing account `first`.
2. Import `second`; the vault now contains two accounts, although engine registration cannot complete.
3. Retry the session.
4. Import `third`.
5. Expect `[first, second, third]`; actual persisted contents are `[first, third]`.

The test waited for both IO writes and used synthetic keys only. The reproduction is preserved in [2026-10-28-account-persistence.kt.txt](2026-10-28-account-persistence.kt.txt); its temporary executable copy was removed from the test source tree. The same stale state also exists after a successful import and subsequent retry; that successful-engine path was established by source inspection, not this reproduction.

Remediation: make unlocked vault state authoritative in one place and serialize its mutations. Every restarted session must receive the latest state. Add regression tests covering successful imports, failed engine registration, retries, network switches, renames and consecutive additions; assert the persisted vault as well as the UI.

### 2. P1 — The staged Barretenberg library is aligned to 4 KB

Locations: `scripts/build-bb-shared.sh:31`; `android/core/src/main/jniLibs/arm64-v8a/libbb_v6.0.0.so` (generated artifact).

`readelf -lW` reports `0x1000` alignment for every LOAD segment of the staged Barretenberg library. The Rust FFI library reports `0x4000`. Packaging with extracted native libraries does not repair ELF segment alignment. The shim linker command does not explicitly enforce 16 KB alignment.

This is a release compatibility blocker: the current staged prover does not satisfy the native 16 KB ELF requirement. Google requires support for apps targeting Android 15 or later. See [Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes).

Remediation: rebuild from the source scripts with an appropriate current NDK and explicit 16 KB linker configuration where necessary; do not edit generated libraries. Inspect every `.so` in APKs generated from the final AAB, including JNA dependencies, and exercise proof generation on a 16 KB device or emulator. Add an ELF alignment gate to the release build.

### 3. P1 — Browser search uses an API unavailable on supported Android versions

Locations: `android/core/src/main/kotlin/dev/azwall/core/wallet/Browsing.kt:39`; `PaymentRequest.kt:25`; `android/app/build.gradle.kts:17`.

The app advertises API 30 support. `URLEncoder.encode(String, Charset)` and `URLDecoder.decode(String, Charset)` require API 33, and core library desugaring is not configured. On Android 11/12/12L, typing a search can throw `NoSuchMethodError`. The QR decoder wraps its call in `runCatching`, so it can silently retain percent-encoded parameters instead of decoding them.

Confirmed by `:core:lintRelease`, with five NewApi errors in total. The other three are generated UniFFI Cleaner references: those are guarded by a reflective availability check with a JNA fallback, so they are not evidence of the same runtime defect. Address their lint diagnostics through generator configuration or a narrowly justified source configuration, without editing generated bindings.

Remediation: use the compatible encoding-name overloads and add Android API 30–32 device coverage for browser search and percent-encoded payment requests. See [Android URLEncoder reference](https://developer.android.com/reference/java/net/URLEncoder).

### 4. P1 — Privacy policy is absent from the app's navigation

Location: `android/app/src/main/kotlin/dev/azwall/app/ui/settings/SettingsScreen.kt:156`.

The repository contains `docs/privacy.md`, but no privacy-policy link or policy text was found in Android app resources or source. The About section only shows versions. A policy hosted outside the app does not provide an accessible in-app policy entry by itself.

Google requires a privacy-policy link in Play Console and policy text or a link within the app. See [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).

Remediation: add a clearly labelled policy entry, preferably accessible before creating a wallet too; verify the public policy URL and its accessibility. The Play Console configuration was not available for inspection.

### 5. P1 — The release bundle advertises x86_64 without the wallet native libraries

Locations: `android/app/build.gradle.kts:22`, `:159`; generated `android/app/build/outputs/bundle/release/app-release.aab`.

The completed release AAB contains both ARM64 and x86_64 libraries from dependencies. However, `libazwall_ffi.so` and `libbb_v6.0.0.so` exist only under ARM64. The global ABI filters allow x86_64, while the release input check validates ARM64 only. An x86_64 installation therefore lacks the native wallet backend required at boot; packaging can succeed without a usable wallet for that ABI.

Confirmed by listing every native library in the AAB. This was an artifact inspection, not a test on an x86_64 device.

Remediation: restrict release and staging variants to ARM64 if that is the supported distribution target, leaving x86_64 to debug builds with its native libraries staged. Alternatively build and validate all required libraries for every advertised ABI. Add a release check comparing the ABI sets of dependency libraries, the Rust FFI and the prover.

### 6. P2 — Local financial metadata is stored as application-readable plaintext despite the policy's encryption claim

Locations: `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletStore.kt:186`, `Snapshot.kt:15`; `docs/privacy.md:16`.

The PXE databases and signing-key vault are encrypted, but `wallet-<network>.json` stores balances, account addresses, counterparties and activity as ordinary JSON. Locking the wallet does not encrypt that file. Android sandboxing and device encryption offer protection, and backups are disabled; this is not a demonstrated remote key theft. It nevertheless weakens the app-level privacy boundary and contradicts the unqualified claim that balances and history are in an encrypted store.

Remediation: encrypt the native metadata store with a key protected by the vault, or accurately describe this storage distinction in the policy and explicitly accept the threat-model limitation. Test both locking and disk contents using synthetic data.

### 7. P2 — Browser renderer termination can crash the whole wallet

Location: `android/app/src/main/kotlin/dev/azwall/app/ui/browser/BrowserTab.kt:137`.

The hidden PXE WebView handles `onRenderProcessGone`, but the browser tab's WebViewClient does not. Android can terminate WebView renderers under memory pressure; a wallet generating native proofs is particularly exposed to memory pressure. Android lint flags this missing handler in the browser. The analogous core warning is a false positive because AssetClient implements the callback.

Remediation: handle renderer termination for every browser tab, reject the page's approvals and sessions, dispose of the dead WebView and offer reload. Test forced renderer termination and recovery during a pending dApp request.

### 8. P2 — Wallet metadata writes can silently fail at the atomic rename

Location: `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletStore.kt:186`.

`save()` updates in-memory state, writes a temporary file, then ignores the Boolean returned by `renameTo(file)`. A failed rename therefore looks successful until restart, when old metadata reappears. This affects transaction tracking, snapshots and manually entered claim secrets. Unlike the encrypted vault writer, this writer does not check rename success. The swallowed-error checker does not catch this Boolean failure path.

Remediation: verify replacement success and report failures, publishing the new in-memory state only after persistence succeeds. Add a filesystem-failure regression test. This finding follows directly from the implementation; a device filesystem failure was not injected during this review.

## Validation and release conditions

- `scripts/test-all.sh`: passed with access to the local dependency caches; network-dependent Rust test remained ignored, iOS skipped because Swift is unavailable.
- Rust: 73 tests passed, one live RPC integration test ignored.
- PXE: 14 shared tests and 80 v6 host tests passed; TypeScript checks passed.
- Android: existing test suite passed (debug: 23 app and 125 core tests; additional existing release tests: 5 app and 3 core). These JVM tests use fakes and do not establish real-device Keystore, WebView or native-proof compatibility.
- Additional temporary account-persistence regression: failed as expected, showing the loss described in finding 1.
- `:core:lintRelease`: failed with five errors and one warning, triaged above.
- `:app:lintRelease`: passed with 21 warnings, including browser renderer termination; this does not cancel the core lint failure.
- `./gradlew --quiet test :app:bundleRelease`: passed after removing the temporary reproduction. The resulting AAB is approximately 69 MiB and unsigned (no JAR signature entries); it is not ready to upload until signed.
- All native libraries in the generated AAB were extracted to a temporary audit directory and inspected with `readelf`: Barretenberg is 4 KB aligned; every other included library is 16 KB aligned. x86_64 is incomplete as described in finding 5.

Before distribution, resolve the P1 findings and verify a signed final AAB through Play internal testing: create/restore, imported and derived accounts, background/lock/unlock, a private transfer, shield/unshield, cancellation, network interruption after submission, rejected and accepted dApp transactions, API 30–32 and 16 KB devices. No live transaction, physical-device test, dependency vulnerability database scan or Play Console review was performed here.

Google's [cryptocurrency policy](https://support.google.com/googleplay/android-developer/answer/16329703?hl=en) currently states that non-custodial wallets are outside that specific exchange/software-wallet policy's scope. Financial features declarations and general user-data requirements still need to be completed accurately. The release guide's blanket “no data collected/shared” statement should be checked against the actual third-party RPC flows and Google's definitions before submitting Data safety answers; this audit does not certify those answers.
