# Independent Android pre-release audit

Date: 2026-10-28. Reviewed commit: `4269719fed687da3c98f825ad15bf856a6b924f6`.

**Recommendation: hold the external Google Play test release until the P1 findings are fixed and their regression tests pass.** This review found three P1 and three P2 issues. No production source was changed. Reproduction tests were run temporarily, then archived as text so they do not leave the normal suite failing.

The review starts from the current implementation and new failure scenarios. Previous audit conclusions are not evidence of safety. This is a source, local test and build review, not a third-party certification or proof that every vulnerability has been found.

## Scope and evidence

The main trust boundaries examined were vault persistence and migration, account authorization in the PXE/dApp gateway, transaction submission and cancellation, and Android release packaging. Android platform behavior was inspected in source; the reproductions use fake backends, secret boxes and node responses. They do not use real keys or send transactions.

| Check | Result |
| --- | --- |
| `scripts/test-all.sh` before adding reproductions | Passed: repository checks, Rust (73 passed, one live test ignored), PXE (14 shared and 80 host tests), TypeScript checks and Android JVM tests |
| `./gradlew --quiet lintRelease :app:bundleRelease` in `android/` | Passed |
| New adversarial tests | Six failed safety assertions: three PXE tests and three JVM tests |
| `scripts/test-all.sh` after removing temporary tests | Passed again; tracked production and test sources unchanged |
| iOS tests | Skipped: no Swift toolchain |
| Live RPC / real-device / Play installation | Not performed in this audit |
| Production dependency audit | Nine advisory records: seven high, two moderate; runtime reachability not established |

Archived evidence is in [2026-10-28-independent-evidence](/home/eddy/Projects/azwall/docs/audits/2026-10-28-independent-evidence). Failing assertions describe the desired safety property, not setup failures. Specific limitations are stated with each finding.

## Findings

### A01 — P1: migration fallback overwrites newer account keys

Location: [Vault.kt:74](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/vault/Vault.kt:74), with snapshot creation at line 117.

After migrating a legacy vault, `.previous` retains the pre-migration contents until a successful unlock with the new key. Adding or importing an account in that same unlocked session updates the current vault but not the snapshot. A subsequent non-authentication exception while opening the new vault causes `unlock()` to rename the old snapshot over the current file.

Reproduction: create a legacy vault with one account, upgrade it, save a second imported account, then inject a transient `ProviderException` on new-key reads. The fallback succeeds, but after removing the injected failure the persisted vault contains only one account: `expected:<2> but was:<1>`.

Impact: a successfully saved imported key can disappear. If the user no longer has its separate backup, access can be lost; an imported key is not recoverable from the wallet's phrase. This is conditional on legacy migration and a later read failure, not every fresh installation.

Remediation: do not destructively replace newer contents with a stale migration snapshot. Validate the new key before accepting later mutations, or implement a recovery scheme that preserves every newer write. Preserve the current encrypted file on transient failures and make any recovery loss explicit.

Evidence: `IndependentVaultRollbackTest.kt.txt` and `vault-rollback-result.xml`. The cryptographic provider error is injected; no physical Keystore failure was induced.

### A02 — P1: concurrent sends broaden authorization to another account

Location: [senders.ts:20](/home/eddy/Projects/azwall/pxe/host/src/senders.ts:20), integrated at [wallet.ts:120](/home/eddy/Projects/azwall/pxe/host/src/wallet.ts:120).

`SenderGuard.check()` accepts any account appearing in the global list of active senders. While a send from B is pending, a send from A can therefore request an authorization on behalf of B and pass the guard. The pinned EmbeddedWallet implementation converts simulation offchain effects into `createAuthWit(authRequest.onBehalfOf, ...)` calls, so this check protects an actual automatic signing path.

Reproduction: hold `sending(B)` open, start `sending(A)`, then call `check(B)` from A's callback. The expected rejection does not occur. The gateway's `SigningGate` protects approval per session, ends before execution, and does not serialize all sends. `GasCache.send()` adds no serialization either.

Impact: the guard no longer enforces the account boundary of an individual approved transaction. A malicious contract requesting another local account's authorization could benefit from overlapping sends, provided the relevant simulation/proof path succeeds. **The guard bypass is confirmed; an end-to-end malicious contract transaction or theft is not demonstrated.** P1 reflects the signing boundary at risk, not a claim of proven theft.

Remediation: bind implicit authorization to the exact send context, or serialize the entire authorization-producing send operation per wallet. Explicit user-approved authorizations must remain distinct from simulation-generated requests. Test concurrent gateway calls with two accounts, in addition to the unit guard test.

Evidence: the first test in `independent_dapp_sender.test.ts.txt` and `pxe-reproductions.log`.

### A03 — P1: an uncertain submission loses its hash and becomes a failure

Location: [submit.ts:24](/home/eddy/Projects/azwall/pxe/host/src/submit.ts:24), [Transactions.kt:146](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:146), [WalletStore.kt:137](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/WalletStore.kt:137).

When the node accepts a transaction but its response is lost, `confirmSubmissions()` knows the transaction hash. It polls three times, separated by two-second waits. If all receipt reads also fail or remain unknown, it rethrows the original error without preserving that hash in the result. The native send path records a failed activity without a hash. `unsettled()` cannot reconcile it because it only includes entries with a hash. The dApp send path likewise emits a failed event without the hash.

Reproduction: a fake node accepts the transaction and throws a connection-reset error; its first three receipt reads are offline, then the fourth returns `PROPOSED`. The wrapper still throws the original error without the known hash. The assertion requiring the hash fails. An error field is only one possible transport design; the required property is durable retention and reconciliation of the uncertain transaction.

Impact: an accepted transaction may later settle while the wallet reports failure. A user retry can send a second transaction and pay twice. Increasing the polling timeout alone does not remove the ambiguity.

Remediation: persist the hash before broadcasting and represent uncertain submission as pending/unknown until reconciled. Carry that state through the bridge, activity store and dApp events. Test response loss, receipt-service outage, delayed visibility and process death after broadcast.

Evidence: `independent_tx_submit.test.ts.txt` and `pxe-reproductions.log`. Network behavior is simulated; no live transaction was submitted.

### A04 — P2: deleting the wallet can race with a save and recreate the vault

Location: [AppViewModel.kt:234](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt:234), [VaultState.kt:44](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/vault/VaultState.kt:44).

`wipe()` locks the UI and calls `deps.wipe()` outside the mutex that serializes vault changes. `forget()` prevents an old operation from repopulating the unlocked memory state, but explicitly allows that operation to finish writing the file. It does not coordinate deletion.

Reproduction: pause the seal operation for an account import, wipe the wallet and verify the vault file is gone, then release the pending save. The phase remains `Onboarding`, but the file exists again: `the removed vault was recreated`.

Impact: deletion is not final and onboarding can encounter an unexpected existing vault. On a real device the deleted Keystore key may leave the late ciphertext unreadable; this test does **not** prove that deleted private keys remain decryptable. The confirmed defect is the persistence/deletion race.

Remediation: route deletion through the same vault synchronization boundary, drain or cancel pending writes, and prevent stale operations from committing after a completed wipe. Apply the same rule to reset/recovery entry points.

Evidence: `IndependentVaultWipeTest.kt.txt` and `vault-wipe-result.xml`. This test uses fake `AppDeps` and a non-cryptographic `SecretBox`.

### A05 — P2: a token balance change hides other calls to the same token

Location: [calls.ts:136](/home/eddy/Projects/azwall/pxe/host/src/dapp/calls.ts:136), used by [gateway.ts:248](/home/eddy/Projects/azwall/pxe/host/src/dapp/gateway.ts:248).

`otherCallLines()` suppresses every call whose destination is a token appearing in the balance-change summary. This also suppresses non-transfer operations against that token, rather than only the transfer already represented by the amount.

Reproduction: a payload has `transfer_in_public` and `set_admin` to the same address, and that address appears in the simulated balance changes. The supplementary call summary is empty. The assertion requiring `set_admin` fails.

Impact: the main approval summary can show a small transfer while omitting a materially different operation in the same transaction. Full call details remain available; this is an incomplete primary summary, not removal of the underlying request or bypass of capability validation. The test uses synthetic calls and no registered artifact; the address-based suppression is independent of the resolved function.

Remediation: preserve calls unless their verified semantics are fully represented by the displayed balance changes. At minimum, retain administrative, authorization and unknown calls even when they target a token already listed.

Evidence: the second test in `independent_dapp_sender.test.ts.txt` and `pxe-reproductions.log`.

### A06 — P2: cancellation promises that nothing was sent after broadcast

Location: [Transactions.kt:175](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:175), [ProofGate.kt:28](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/ProofGate.kt:28), [ProvingOverlay.kt:75](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/ui/send/ProvingOverlay.kt:75).

There is a window after the proof gate passes and broadcasting occurs but before the PXE response supplies the hash. Cancelling in this window throws `CancelledByUser`, whose text says “nothing was sent”, and records a failed/cancelled activity. The overlay similarly promises nothing will be sent.

Reproduction: a fake transfer passes `ProofGate.check()`, marks the broadcast as completed, then holds its response. Cancelling produces the misleading “nothing was sent” message; the safety assertion fails.

Impact: the user can treat a real transfer as cancelled and repeat it. `windDown()` already follows a hash arriving later, so the defect is the premature definitive claim and intermediate failed state; it is not a claim that every late response is ignored.

Remediation: distinguish cancellable proving from submission in progress. After broadcast can have begun, say cancellation cannot guarantee non-submission and continue tracking the result. Combine this with A03's durable pending state.

Evidence: `IndependentTxTest.kt.txt` and `transaction-cancel-result.xml`.

## Release packaging and dependency observations

- The built AAB is about 68.9 MiB and contains six native libraries, all under `arm64-v8a`. Their ELF load segments report 16,384-byte alignment. This is a positive static check, not execution on a 16 KB device; [Android's guidance](https://developer.android.com/guide/practices/page-sizes) also calls for runtime validation.
- The inspected AAB has no signature entries. Configure the upload signing key and produce a signed artifact before submission; [Android's signing documentation](https://developer.android.com/studio/publish/app-signing) requires signing an app bundle before uploading it to Play Console. No signing credentials were inspected or created.
- `pnpm --dir pxe audit --prod --json` reports nine advisory records affecting `systeminformation`, OpenTelemetry packages, `uuid` and `zod`. The archived JSON contains package names, severity, advisory links and patched ranges. This dependency-graph result is not evidence that nine exploitable vulnerabilities ship in the Android runtime. Node-only telemetry paths and specific affected API usages need reachability analysis before assigning wallet severity. No Rust advisory scan was completed.
- Play Console declarations, store review acceptance, signed Play delivery, device credential/biometric flows, low-memory behavior and live proof/broadcast/recovery remain unvalidated here. iOS is outside this Android release decision.

## Reproducing the assertions

Copy the archived `.txt` files to the temporary test locations below, removing only the trailing `.txt` suffix. Do not edit the generated `pxe/v6` sources or tests; the normal PXE command synchronizes them.

| Archived source | Temporary destination |
| --- | --- |
| `IndependentVaultRollbackTest.kt.txt` | `android/core/src/test/kotlin/dev/azwall/core/vault/IndependentVaultRollbackTest.kt` |
| `IndependentVaultWipeTest.kt.txt` | `android/app/src/test/kotlin/dev/azwall/app/IndependentVaultWipeTest.kt` |
| `IndependentTxTest.kt.txt` | `android/core/src/test/kotlin/dev/azwall/core/wallet/IndependentTxTest.kt` |
| `independent_dapp_sender.test.ts.txt` | `pxe/host/test/independent_dapp_sender.test.ts` |
| `independent_tx_submit.test.ts.txt` | `pxe/host/test/independent_tx_submit.test.ts` |

From `android/`, run:

```sh
./gradlew --quiet :core:testDebugUnitTest --tests dev.azwall.core.vault.IndependentVaultRollbackTest --tests dev.azwall.core.wallet.IndependentTxTest
./gradlew --quiet :app:testDebugUnitTest --tests dev.azwall.app.IndependentVaultWipeTest
```

From the repository root, run `pnpm --dir pxe/v6 test`. At the audited revision the six assertions above fail. These are audit reproductions, not complete regression suites or full chain exploit demonstrations.

Before external testing, fix A01–A03, address the misleading confirmation states in A05–A06, serialize deletion in A04, and validate the resulting signed build on a real Android device. A passing baseline suite or successful AAB build does not cover these scenarios.
