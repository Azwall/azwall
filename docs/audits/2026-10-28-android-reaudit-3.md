# Android pre-release re-audit 3 — 2026-10-28

Reviewed revision: `4269719fed687da3c98f825ad15bf856a6b924f6`.

Result: the two findings from re-audit 2 are resolved within the reviewed scope. No new release-blocking code defect was identified in this correction review. The inspected AAB is unsigned, so it is not yet an upload-ready artifact. Real-device acceptance testing remains necessary before external distribution.

Scope: review of the changes since `38af7aa`, regression tests for overlapping vault saves and lifecycle transitions, metadata-write behavior, the complete existing test suite, release lint/build, native libraries in the rebuilt AAB and the linked public policy. This is a review of the corrections, not a new exhaustive security certification.

## Previous findings

### R1 — Account loss during Retry and a vault save: resolved

`VaultState` now owns the unlocked vault once for the view model, and every network session uses that same owner. Mutations acquire its shared mutex, derive their update from the latest held contents and publish the saved result back to that owner. A new session no longer retains a separate account-list snapshot. Unlocking also acquires the same mutex before reading the vault. The epoch check prevents a save that completes after locking from repopulating the unlocked state.

The permanent `AccountRaceTest` tests pass:

- `retryWhileSavingDoesNotDropTheAccount`;
- `lockingAndUnlockingWhileSavingDoesNotDropTheAccount`.

Both pause the second account's seal operation, perform the lifecycle transition, finish the save and add a third account. The persisted vault retains all three accounts.

An additional temporary regression used two fake networks and replaced the Retry operation with `vm.switchNetwork("second-network").join()` while the save was paused. It also passed, retaining all three accounts. The temporary test ran two cases with zero failures and was then removed; its source and result XML were retained under `/tmp/azwall-audit3/` for this session. No real keys or live RPC services were used.

### R2 — Non-atomic metadata copy fallback: resolved

`WalletStore.save()` now replaces the destination only using the same-directory temporary-file rename. The `copyTo(..., overwrite = true)` fallback has been removed. A failed rename sets `unsaved` and records a diagnostic error while retaining the in-memory update. The permanent failure-and-recovery test passes. This resolves the copy-interruption risk identified in re-audit 2; power-loss durability of filesystem writes was not tested.

## Automated checks and artifact evidence

| Check | Result |
| --- | --- |
| `scripts/test-all.sh` | Pass; Rust 73 passed, one live RPC test ignored; PXE 94 passed and TypeScript checks passed; Android passed; iOS skipped because Swift is unavailable. |
| Android existing JVM tests | Debug: app 26, core 127; release: app 5, core 3. Zero failures. |
| Additional temporary network-switch checks | Two tests, zero failures. |
| `./gradlew --quiet lintRelease :app:bundleRelease` | Pass. Core: zero errors, one warning; app: zero errors, 17 warnings. |
| Native ABI inventory of rebuilt AAB | ARM64 only; Rust FFI and Barretenberg present. |
| ELF LOAD alignment | All six included native libraries report `0x4000` (16 KB). |
| Public privacy-policy URL | HTTP 200; published page includes Swiss Block Services and the unencrypted-cache disclosure. |
| AAB signature | No JAR signature entries: unsigned. |

Inspected artifact: `android/app/build/outputs/bundle/release/app-release.aab`, approximately 68.9 MiB.

SHA-256: `ba2c1130b0c3b5b2e7789a6b71f97d2d40be0ceb5947dedfefe83e69d6b3614e`.

Included native libraries, all under `base/lib/arm64-v8a/`: `libandroidx.graphics.path.so`, `libazwall_ffi.so`, `libbb_v6.0.0.so`, `libimage_processing_util_jni.so`, `libjnidispatch.so`, `libsurface_util_jni.so`.

The earlier encoder compatibility changes, labelled in-app privacy links, renderer-termination handler and accurate metadata disclosure remain in place. The local metadata cache is still not encrypted by the wallet; the public policy now states that limitation.

## Remaining release work

Configure the upload signature, rebuild the final signed AAB and test the Play-delivered build on real devices. Exercise restoration and imported accounts, Retry/network switching while saving, lock/unlock, dApp rejection/approval, a private transfer, renderer recovery and cancellation. Include Android API 30–32 and a 16 KB device or emulator. Native alignment inspection establishes an ELF property, not end-to-end runtime compatibility.

No Play upload, Play Console declaration review, live-chain transaction, physical-device Keystore test or dependency vulnerability database scan was performed. These remain outside this correction review. Application code was not changed by this re-audit.
