# Google Play open-test release audit — 29 October 2026

Reviewed commit: `290214515b6ab77cc0e0d0ac7933a8d92b9e7473` (initial working tree clean).

Target: **Google Play open testing**, as clarified by the owner during this review. This is not a production rollout or an internal-only test. The artifact exposes both testnet and mainnet; an open testing track does not restrict its users to test funds.

**Decision: hold open testing.** Two transaction edge cases were reproduced, and a backup instruction contradicts the key-only wallet behavior. Fix these before exposing the wallet to an unrestricted audience. Separately, the inspected AAB is unsigned and has not been exercised on an ARM64 phone or delivered through Play.

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| OT-01 | P1 | A new send reopens the gate for an earlier timed-out proof | Failing JVM reproduction and native/bridge call-path review |
| OT-02 | P2 | Queued dApp transactions from the same account can lose the first broadcast hash | Failing JVM reproduction, including store reload |
| OT-03 | P2 | Adding an account to a key-only wallet promises phrase recovery but creates an independent random key | UI and recovery source; existing random-key unit test |

Priorities describe remediation urgency. OT-01 is conditional on an unanswered send and a late native proof; this review does not demonstrate theft by an unapproved transaction. No production source was modified, no wallet was accessed, and no transaction was broadcast. Temporary reproduction tests were archived and removed from the normal test directories.

## Scope and limits

This review examined Android vault lifecycle and migration, authentication, backup and recovery, native/PXE messaging, transaction submission/cancellation/history, dApp origin and capability enforcement, approval summaries, the native prover boundary, dependency advisories, the actual release bundle, public RPC compatibility and Google Play open-test requirements. Current source was inspected before comparing prior audit conclusions.

This is a local source/test/build audit, not a formal audit of Aztec, Noir or Barretenberg cryptography. There was no physical-device test, full malicious-contract exploit, full-chain send/receive round trip, memory-forensics exercise, or authenticated Play Console inspection. iOS is outside this Android release decision.

Evidence: [audit evidence directory](/home/eddy/Projects/azwall/docs/audits/2026-10-29-google-play-open-test-evidence).

## OT-01 — P1: the next send allows an older timed-out proof through

Locations: [Transactions.kt:179](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:179), [Transactions.kt:190](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:190), [ProofGate.kt](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/ProofGate.kt), [AndroidPxeLauncher.kt:47](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/AndroidPxeLauncher.kt:47).

The previous correction closes `ProofGate` after an unanswered send when its proof has not been released. However, the next native transaction unconditionally calls `ProofGate.reset()` before issuing its PXE request. The gate is global and carries no transaction identity. The old native proof handler can therefore finish after that reset, call `release()`, and return its proof to the old send.

The bridge timeout does not cancel that native handler. `PxeChannel.call()` stops waiting and removes its pending response, while `serve()` runs handlers in a separate host scope. The JavaScript sender queue is not a remedy: the new native caller resets the gate before its JavaScript send gets a turn. That can let the old proof finish and free the queue by sending the very transaction already shown as failed.

Reproduction:

1. Inject `PxeException(unanswered = true)` before a transfer releases its proof, modelling a still-running native proof after the caller's timeout.
2. Verify that the first call failed and the gate is closed.
3. Start another transfer and hold its PXE response, modelling a send waiting behind the first in JavaScript.
4. Complete the old proof's `ProofGate.release()` operation. Expected: rejection; observed: no exception.

The archived assertion fails with `expected ... CancelledByUser to be thrown, but nothing was thrown`. This reproduces the application state error with fakes; it does not run a physical 20-minute native proof or broadcast either transfer.

Impact: a transfer the wallet labelled failed may still be sent after the user retries, risking duplicate payment and incorrect hash attribution. The ordinary cancel-after-release case is now protected; this is the timeout/retry case left by the shared gate.

Recommended correction: give every send/proof a stable operation ID or generation and retain cancellation for that specific operation until its native handler ends. Do not let a new operation clear the previous one's cancellation. Alternatively, retire the old host and drain the outstanding native work before permitting a new send. Add a regression that finishes the first proof only after the retry starts; the current test finishes it before retrying and misses this ordering.

## OT-02 — P2: queued dApp sends lose the pre-broadcast hash

Locations: [Transactions.kt:252](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:252), [gateway.ts:335](/home/eddy/Projects/azwall/pxe/host/src/dapp/gateway.ts:335), [wallet.ts:112](/home/eddy/Projects/azwall/pxe/host/src/wallet.ts:112), [WalletStore.kt:147](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/WalletStore.kt:147).

The gateway emits a `dappTx` event with a unique ID and `proving` status before entering the serialized send queue. More than one approved request for the same account can therefore have a `PROVING` row while only the first is executing. For example, two sites can have their approvals prepared before the first send begins.

The pre-submission event only contains sender and hash. `followSubmissions()` looks for a single matching row; if two rows match, `singleOrNull()` discards the hash. A later `dappTx: sent` normally repairs the first row, but a process exit before that event leaves it without the hash. On restart, `failInterrupted()` marks it failed rather than following a transaction the node may already have received.

Reproduction: deliver two `dappTx: proving` events for `addr-abab`, then `txSubmitting` with hash `0xaccepted`; stop the session, reopen `WalletStore`, and apply restart recovery. The first row's hash is `null` instead of `0xaccepted`. Its expected pending state cannot be recovered.

Impact: a broadcast payment may disappear from reconciliation and be presented as interrupted/failed, inviting a duplicate payment. Serializing sends fixes the cross-account authorization issue but does not serialize the creation of these history rows.

Recommended correction: propagate the unique transaction ID from approval/draft through the send queue and submission event. Persist `{operationId, hash}` before broadcasting and acknowledge that persistence back to the PXE. Do not infer identity from sender or queue order. A durable acknowledgement also covers the smaller gap between emitting the current asynchronous event and writing it on the native side.

Evidence: `queuedDappSendsMustKeepTheBroadcastHashAcrossRestart` in the archived test and its XML failure. Broadcast and process death are modelled at the event/store boundary; no live dApp transaction was sent.

## OT-03 — P2: key-only wallets create accounts without the promised recovery path

Locations: [Sheets.kt:135](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/ui/sheets/Sheets.kt:135), [Recovery.kt:31](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/vault/Recovery.kt:31), [WalletSession.kt:362](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:362).

The add-account form always tells the user that leaving the key empty creates the next account of their recovery phrase. A wallet restored from a signing key has no phrase. For that wallet, `Recovery.nextAccount()` instead generates an unrelated random signing key. The form has no phrase-state input and does not require or explain a separate backup before the new account becomes usable.

Restoring the original signing key and adding another account will generate a different random key; it will not restore this second account. The implementation is intentional and covered by `RecoveryTest.walletsWithoutPhraseGetRandomKeys`; the defect is the recovery promise and missing backup step in the product flow. A manual key export exists in Settings, but this creation flow does not direct the user to it.

Impact: a user relying on the backup that restored the original wallet can permanently lose access to later accounts after losing the device. This does not affect deterministic accounts created from an actual recovery phrase.

Recommended correction: make the form distinguish phrase-derived accounts from independent random-key accounts. For key-only wallets, require a separate backup acknowledgement/export before funding the generated account, or disable random account creation until an explicit recovery scheme is established. Do not change existing derivation rules, which would alter restored addresses.

## Status of the six findings from the independent audit of 28 October

| Previous finding | Current assessment |
| --- | --- |
| A01: stale migration rollback deletes newer keys | Original scenario corrected: ordinary vault writes retire the stale snapshot; upgrade keeps the same contents. Existing migration tests pass. |
| A02: concurrent senders broaden authorization | Original guard bypass corrected: `SenderGuard` serializes sends and checks one sender. This audit found no remaining cross-account bypass in that path. |
| A03: ambiguous submission loses the hash | Single-send handling improved by pre-submission events and `Told`; not fully closed for queued dApp sends (OT-02), and event emission is not a durable acknowledgement. |
| A04: wipe races with vault mutation | Original race corrected: `VaultState.erase()` waits for the mutation mutex and is used by wipe/start-over. |
| A05: token delta hides unrelated calls | Original blanket token-address suppression removed; administrative and unverified calls remain visible. Verified balance functions can still be represented by their balance changes. |
| A06: cancel promises nothing was sent after broadcast | Ordinary after-release cancellation is refused and the overlay says sending. Timeout/retry still needs operation-bound proof cancellation (OT-01). |

The later fixes for a failed pre-proof call poisoning future dApp proofs, site-name formatting, lost-key recovery and the bounded background-lock wait are present. Passing their existing tests is not evidence that every lifecycle interleaving is safe.

## Verification performed

| Check | Result |
| --- | --- |
| `scripts/test-all.sh` | Passed: size/error-handling checks, Rust, PXE, TypeScript and Android JVM suites |
| Rust tests | 73 passed; the configured live test remains ignored by the default suite |
| PXE tests | 14 shared + 98 host tests passed |
| Android debug JVM suites after removing audit tests | Core: 141 passed; app: 32 passed |
| Additional adversarial JVM tests | Two tests executed; both failed their safety assertions as described above |
| `cargo clippy --workspace --all-targets -- -D warnings` | Passed |
| `cargo audit --json` | Zero vulnerability records; one unmaintained warning for `basic-toml` in the lockfile. It does not appear in the default `cargo tree -i basic-toml` graph. |
| `pnpm --dir pxe audit --prod --json` | Eleven advisory records: seven high, three moderate, one low |
| `./gradlew --quiet lintRelease :app:bundleRelease` | Passed; app lint: 17 warnings, core lint: one warning, no errors |
| Two public `node_getNodeInfo` calls | Both responded HTTP 200 with Aztec 6.0.0 and the configured chain ID, rollup version and Fee Juice portal |
| Public privacy page | HTTP 200; 29 October policy and publisher are present; obsolete endpoint-setting claim absent |

The first attempts at pnpm/Gradle were restricted by the sandbox; the successful executions used the required local cache access. An initial public RPC request with Python's default user agent returned an HTTP error; the repeated read with an explicit audit user agent succeeded on both nodes. Neither result is a full on-device connectivity test.

The npm records affect `systeminformation`, OpenTelemetry packages and `uuid`, predominantly through upstream dependencies. The previous `zod` advisory no longer appears after the override to 4.6.6. These counts are dependency-graph results, not eleven demonstrated Android exploits. Node-only telemetry and affected API reachability must be distinguished from code shipped to the WebView before assigning wallet severity. The structured advisory evidence includes dependency paths and patched ranges. No unrequested dependency upgrades were applied.

Lint's update suggestions concern Gradle 9.8.2, WebKit 1.18.0 and serialization 1.12.0. Other warnings concern resources/icons, Kotlin style and backup declarations. `allowBackup=false` is present; the Android 12 extraction rules also exclude all storage domains. The core renderer warning was checked against the implemented `AssetClient.onRenderProcessGone`; it is not treated as a confirmed missing handler.

## Actual release artifact

Artifact: [app-release.aab](/home/eddy/Projects/azwall/android/app/build/outputs/bundle/release/app-release.aab).

- Package `dev.azwall`, version `0.8.0`, version code `800`, minimum API 30, target API 37, not debuggable.
- SHA-256: `553f560a595966f1ca8ca32cb92d65f2edf47d73477e3a3fd104f6bfbce1b217`.
- Size: 72,263,208 bytes, about 68.9 MiB. This is the bundle size, not the final Play device download size.
- **Unsigned:** no upload signature is present. An upload-signed bundle is required before submission. [Android app signing](https://developer.android.com/studio/publish/app-signing).
- Six native libraries, all ARM64. Every inspected ELF load segment has 16,384-byte alignment. This static result does not replace generated-APK alignment checks and execution on a 16 KB device. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- The packaged Barretenberg hash matches `scripts/bb-libs.sha256`; all three packaged CRS hashes match `scripts/crs.sha256`.
- Both bundled RPC configurations exactly use the public registry defaults, including Ethereum endpoints. No `.env` endpoint was printed or included in this report.
- Bundled PXE files/configuration match their local generated outputs; the content script is present. No JavaScript source maps were found; the AAB contains the normal R8 mapping metadata.
- Backup is disabled. The merged permissions are Internet, network state, camera, biometric/fingerprint and the generated private receiver permission. Exported components are the launcher activity and AndroidX's profile installer receiver protected by `android.permission.DUMP`.
- The icon is 512×512, feature graphic 1024×500, and seven listing screenshots are 1080×2160. This verifies dimensions, not Google's final listing approval.

Only an x86_64 16 KB emulator was connected. The release is ARM64-only, so the audit did not install it there or claim successful wallet/prover execution. No physical ARM64 device was available for this run.

## Google Play open-test readiness

Official guidance was checked on 29 October 2026. Open testing makes the app discoverable and lets anyone join; it requires a listing ready for public visibility. [Google's testing tracks](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en).

| Requirement | Assessment |
| --- | --- |
| Target API | Target 37 satisfies the current minimum 36 for new phone apps/updates. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) |
| Signing / version code | Local bundle unsigned. Play signing configuration and whether code 800 was already used are unknown. |
| Developer account | Verify the organization account for Swiss Block Services Sàrl and completed identity checks. Google requires organization registration for cryptocurrency software wallet providers; this is not proven by the publisher text in the repository. [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/10788890?hl=en) |
| Open-track access | Not inspected. For a qualifying new personal account, open testing requires production access after the applicable closed-test process; do not apply this condition blindly to an organization account. [Account-specific testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) |
| Financial declaration | The draft correctly identifies a cryptocurrency wallet. Submission and accepted answers in Console are unverified. Google's country-specific cryptocurrency exchanges/software wallets page explicitly excludes non-custodial wallets from that specific policy's scope; it is not a blanket exemption from other policies or local law. [Wallet policy](https://support.google.com/googleplay/android-developer/answer/16329703?hl=en-en) |
| Data safety | Required for open testing. The draft declares financial data sent to RPC providers; the open-web browsing exception supports distinguishing ordinary browsing from the wallet's own RPC traffic. Final data categories, provider processing and submitted answers remain the publisher's responsibility. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) |
| Privacy policy | The linked public page is accessible and current. [Published privacy policy](https://azwall.github.io/azwall/privacy) |
| Listing / contact / rating | English and French drafts and image dimensions checked. Actual uploaded screenshots, verified contact/feedback email, countries, rating and audience declarations were not inspected in Console. |
| Device and Play delivery | No signed Play installation, pre-launch report or physical-device proof/recovery test in this audit. |

The draft Data safety table is substantially more accurate than a blanket “no data collected”: not running a developer server does not remove transmissions to the RPC provider. Do not claim third-party logs are deletable by uninstalling the wallet. The current privacy policy correctly distinguishes them.

## Remaining hardening and release work

The following are not additional demonstrated P1/P2 exploits: submission events are asynchronous and unacknowledged; `PxeChannel` uses a finite event buffer; the history has a 200-item cap; lock/network-switch teardown should be tested while sending; the dApp browser and PXE have no explicit process isolation here; and arbitrary connected-site registrations/resource consumption still need stress testing. The Gradle release checks verify core freshness/alignment, but do not themselves enforce the prover checksum manifest, so future packaging changes should strengthen that gate.

Before open testing:

1. Correct OT-01 and OT-02 with operation IDs and tests covering timeout/retry, multiple queued sends, response loss and restart. Keep the existing after-release cancellation protection.
2. Correct OT-03's recovery instructions and require a separate backup for independent generated keys.
3. Exercise a staging build on ARM64: fresh create, phrase restore, imported-key restore, additional-account recovery, biometric and PIN unlock, locked-key recovery, private/public transfer, shielding, dApp approval/rejection, cancellation, network loss, app termination, restart reconciliation and 16 KB runtime behavior. Use a dedicated test wallet.
4. Either validate mainnet-specific fees/activation and lifecycle behavior to the same standard or prepare a clearly testnet-only open-test build. The current artifact allows real funds; a beta label does not change that.
5. Build and verify an upload-signed AAB, confirm a fresh version code, complete the Console checks above, and review the pre-launch report before enabling open testing.

To rerun the two new assertions, copy `OpenTestAuditTest.kt.txt` from the evidence directory to `android/core/src/test/kotlin/dev/azwall/core/wallet/OpenTestAuditTest.kt`, then run `./gradlew --quiet :core:testDebugUnitTest --tests dev.azwall.core.wallet.OpenTestAuditTest` from `android/`. At the audited revision both safety assertions fail. They were removed after evidence capture; the normal JVM suites pass again.
