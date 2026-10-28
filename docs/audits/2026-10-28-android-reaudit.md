# Android pre-release re-audit — 2026-10-28

Reviewed revision: `38af7aa9d550eb3e70fe5f9eb1d0f4e69169ff95`.

Decision: retain the release hold for external testing because an imported account can still be lost when Retry overlaps a vault write. The sequential scenario from the first audit is fixed. Native packaging, Android API compatibility and privacy-policy access now pass the checks performed here.

Scope: review of the four correction commits since `f99b78b`, targeted lifecycle regression testing, the full existing suite, release lint, release AAB generation and inspection, and the public privacy-policy URL. No application source fixes or publication were performed.

## Remaining findings

### R1. P1 — Retry can still copy stale vault state while an account is being saved

Locations:

- `android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt:176` — `restart()` closes the current session and reads `current.vaultContents` immediately.
- `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:337` — this getter does not wait for the vault write mutex.
- `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:340` — the mutex belongs to one session; the new session gets a different mutex.
- `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:348` — the session's contents are updated only after the IO operation completes.

The fix correctly preserves accounts whose saves finished before Retry. It does not coordinate Retry with a save still running in the view model's action coroutine. Closing the session cancels its own scope, not that action. Thus Retry can capture the old account list while the previous session is saving a new account; the new session subsequently overwrites the vault using that old list. The Retry gesture is not guarded by `busy` (`WalletScreens.kt:180`, `HomeScreen.kt:109`).

Confirmed using a deterministic temporary JVM regression with synthetic keys and a fake engine failure:

1. Unlock account `first`.
2. Begin importing `second`; pause its `SecretBox.seal` operation with a latch, representing a pending authenticated IO write.
3. Run and finish `vm.restart()` while the write is paused.
4. Release the write and await completion. Read the vault to confirm it contains two accounts.
5. Import `third` through the new session and read the vault again.
6. Expected `[first, second, third]`; actual `[first, third]`.

The new regression fails at step 6. It is preserved in [2026-10-28-account-race.kt.txt](2026-10-28-account-race.kt.txt); the temporary executable source was removed after testing. This is a controlled concurrency reproduction, not a timed interaction reproduced on a physical phone. An imported key absent from the recovery phrase can be lost permanently if its separate backup is unavailable.

Required correction: coordinate lifecycle transitions and all vault mutations through a shared authoritative owner/lock. Retry and network switching must await any in-flight write and snapshot the resulting state atomically; prevent old-session actions from overwriting newer state. Guarding UI buttons alone is insufficient. Cover the race in a permanent regression, along with locking/unlocking during a save. The existing sequential regression must continue to pass.

### R2. P2 — The metadata writer's fallback sacrifices atomic replacement

Location: `android/core/src/main/kotlin/dev/azwall/core/wallet/WalletStore.kt:198`.

The original ignored-rename error is now reported to the diagnostic log, which satisfies that aspect of the first finding. However, the new fallback uses `tmp.copyTo(file, overwrite = true)` when rename fails. This can replace the existing file before a full copy succeeds. An IO failure or process termination during that fallback can leave an incomplete destination and destroy the last valid metadata file. Loading then starts over with empty metadata after setting the broken file aside.

This concerns financial metadata, not the separately encrypted signing-key vault. It was identified by code inspection; a partial-copy filesystem fault was not injected. The added failure test forces both rename and copy to fail before a partial copy, so it does not cover this case.

Recommended correction: preserve the previous valid destination on every failed replacement; use a suitable atomic-file abstraction or retain a recoverable backup. Keep diagnostic reporting, and add a test that fails partway through replacement. `unsaved` currently has no UI consumer; the diagnostic log is the only notification, which is permitted by the repository rules.

## Disposition of the original eight findings

| Original finding | Re-audit result |
| --- | --- |
| 1. Account lost after Retry | Sequential regression fixed and passing; concurrent variant remains, reproduced as R1. |
| 2. Barretenberg 4 KB alignment | Fixed in source build script and generated AAB: every included native library has 16 KB LOAD alignment. |
| 3. API 33 encoders on API 30 | Fixed: compatible encoding-name overloads; core and app release lint pass. Device coverage on API 30–32 remains outstanding. |
| 4. Missing in-app policy | Fixed: labelled links on the welcome screen and Settings. The linked public page returned HTTP 200 and contains the revised cache disclosure. |
| 5. Incomplete x86_64 release | Fixed in the inspected AAB: only ARM64 is included, with Rust FFI and Barretenberg present. |
| 6. Encryption claim inconsistent with local cache | Disclosure corrected locally and on the public page. The cache remains unencrypted by the wallet: the privacy limitation remains, but the incorrect claim is resolved. |
| 7. Browser renderer termination | Callback, page rejection, WebView replacement and Compose generation update are implemented. No physical-device renderer-termination test was performed. |
| 8. Ignored metadata rename failure | Failure now logged and tested; new non-atomic fallback risk is R2. |

## Validation

- `scripts/test-all.sh`: passed on the reviewed revision before the temporary race test. Rust: 73 passed, one network-dependent test ignored. PXE: 14 shared and 80 host tests passed; TypeScript checks passed. Android tests passed. iOS skipped because no Swift toolchain is installed.
- `./gradlew --quiet lintRelease :app:bundleRelease`: passed. Core lint has zero errors and one warning. The generated UniFFI NewApi suppression is scoped to generated sources; its reflective Cleaner fallback was reviewed in the first audit.
- Temporary race regression: one test, one expected failure; persisted account list loses `second` as described in R1.
- Generated AAB native inventory: six libraries, all ARM64, all LOAD alignments `0x4000`: `libandroidx.graphics.path.so`, `libazwall_ffi.so`, `libbb_v6.0.0.so`, `libimage_processing_util_jni.so`, `libjnidispatch.so`, `libsurface_util_jni.so`.
- The generated AAB has no JAR signature entries and remains unsigned. Supply the upload signing configuration before submission; no private signing configuration was read during this review.
- Public policy checked at `https://azwall.github.io/azwall/privacy`: HTTP 200, including the unencrypted-cache explanation.

Before external distribution: fix R1, address R2, sign the final AAB and run the wallet flows on real Android devices, including API 30–32 and a 16 KB environment. Exercise account recovery, imports, Retry during saving, private transfers, dApp approvals/rejections, renderer termination, cancellation and uncertain submission outcomes. Play Console declarations, dependency vulnerability databases, live-chain behavior and physical-device Keystore behavior were not verified in this re-audit.
