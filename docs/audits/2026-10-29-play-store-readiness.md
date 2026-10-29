# Google Play readiness audit — 2026-10-29 (evening)

Reviewed revision: `4bbcbc9` (working tree clean but for untracked icon drafts at the repository root).
Scope: the Android app and the shared PXE host, for the Google Play open-testing track. Evidence and the
transaction-path notes: [2026-10-29-play-store-evidence](2026-10-29-play-store-evidence/). Earlier reviews of the day:
[open-testing readiness](2026-10-29-open-testing-readiness.md), [final open-test re-audit](2026-10-29-final-open-test-reaudit.md)
by another reviewer (its FO-01 is re-checked here).

## Decision

**Not yet: one P2 regression to fix first, then the three publication steps.** Everything found by the five audits so far
is closed on Android (table below), including FO-01, which the last commit addresses. That same commit (`854ebd9`)
introduces NF-1: when the hash of a transaction has already been recorded and the call then times out, the row is
rewritten without its hash, stays "uncertain" until the wallet is locked, and ends Failed, unfollowed and
re-sendable: a transaction the node very probably has can be paid twice. The fix is a few lines in `Transactions.kt`
and comes with a ready probe test. After it: sign the bundle, run the staging build on the Pixel 3, complete the Play
Console items.

## Status of the findings, Android

| Finding | Status |
|---|---|
| F1 crafted Token artifact (P1) | closed |
| F6 missing Keystore key (P2) | closed |
| F7, F8 sent transaction shown Failed, `{receipt}` hash (P2) | closed |
| F9 panel spoofing (P2) | closed, including decoded argument values and struct field names (`e0643b5`) |
| F11 prover provenance (P2) | closed (commit pin and hash record; Gradle itself still checks no hash, P3) |
| F13, OT-01 late proof after a timeout (P2, P1) | closed: an unanswered call before the proof left retires the host, the gate refuses a retired host's proof by itself (`ProofGate.release(hostAlive)`), then reopens for the next host; archived OT-01 test passes once adapted to the new refusal point |
| OT-02 queued dApp sends lose the hash (P2) | closed: the dApp row's id travels with the hash; archived test passes |
| OT-03 key-only wallet promises phrase recovery (P2) | closed |
| FO-01 timeout after the proof left, before the hash (P2) | closed: the row is kept "uncertain", never Failed; a retry is refused with that text; the late hash, told with the wallet row's id, makes it pending and followed. Archived reproduction adapted to the new rule: 2 of 2 pass (`FinalOpenAuditTest.adapted.kt.txt`, its XML) |
| R1 gate shut after any failed send (P2) | closed |
| N1 gate cancelled after Retry (P3) | closed (reset after retirement) |
| N2 `cancel()` return ignored (P3) | closed; see NF-2 for the remaining window |
| N5 STUCK message overwritten (P3) | closed |
| R3, R4, R5, R9, R10 (P3) | closed |
| F15 lock, switch, restart during a dApp proof; F18 global gate; F19 registrations; F21 implicit authorizations; F22 renderer process (P3) | open, unchanged; none blocks an open test |

## New this round

### NF-1 — P2 — CONFIRMED — A hash already recorded is erased when the call then times out

Location: `android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:164-167`:

```kotlin
} catch (e: UncertainSubmission) {
    session.recordActivity(draft.copy(error = UNCERTAIN))
    throw e
}
```

`draft` carries no hash, and `WalletStore.upsert` replaces the row. The PXE tells the hash (`txSubmitting`, with the
row's id) just before `node.sendTx`; the whole network call comes after it. So the common ordering of a hang is: hash
recorded on the row by `followSubmissions`, then the 20-minute deadline, then `UncertainSubmission`, whose catch
rewrites the row with `txHash = null`. The hash never comes again. The row stays "uncertain" (every wallet send
refused) until a lock and unlock, where `failInterrupted` turns it into Failed, without a hash: not in `unsettled()`,
not followed, and a new send is allowed. The transaction the node very probably has is paid twice. Reproduced on the
fakes (`ReauditProbeTest.kt.txt`, `run5-probes.xml`: `status=PROVING txHash=null`, then after a restart
`FAILED txHash=null unsettled=0`). At `b5fee97` the same inputs went through the generic path, which reads the stored
hash, and became PENDING with the hash. The FO-01 ordering (timeout between the proof leaving and the hash, a window
of milliseconds) is now handled; this one, the whole network call, is the likelier of the two.

Fix: in that catch, read `session.store.item(draft.id)?.txHash` first; with a hash, record PENDING with it and
`confirm` it; without, record `(session.store.item(draft.id) ?: draft).copy(error = UNCERTAIN)`. Add probe 1 of the
archived file as a test, with its restart variant.

### NF-2 — P3 — PLAUSIBLE — A proof released between the two reads of the gate

`Transactions.kt:206-219`: a `release()` landing between the `released.value` read (false) and `cancel()` (now false)
takes neither branch: the generic path records Failed with the timeout text while the host stays alive and the page
sends the transaction; its `txSubmitting` matches no PROVING row and is dropped. Instruction-sized window on a
20-minute timeout. Decide from `cancel()` alone: stopped → retire and rethrow; not stopped → uncertain.

### NF-3 — P3 — by design — An uncertain row does not hold the auto-lock

`WalletSession.kt:180-181`: the uncertain row does not count as sending, so five minutes in the background close the
page mid-send; at the next unlock the row reads "Failed" (the uncertain text only in its details), is not followed, and
a re-send is allowed. The documented exit ("locking and unlocking the wallet ends the wait") has the F7 shape. Count the
row in `isSending` within the ten-minute bound, and show such a row as "Unknown" rather than "Failed".

Also: icon drafts keep appearing untracked at the repository root (`azwall-icon-A*.png`, `azwall-pyramid-minimal.png`);
move them to `branding/` or delete them before the release commit. The `alive()` lambda of `AndroidPxeLauncher` has no
test (P3 coverage).

## Checks run on `4bbcbc9`

| Check | Result |
|---|---|
| `scripts/test-all.sh` | Rust 73 passed, 1 live test ignored; PXE 14 shared + 100 host; Android JVM suites pass |
| `./gradlew lintRelease :app:bundleRelease` | pass; `app-release.aab` 72,405,990 bytes, SHA-256 `c6a6a561a3e8fd00…`, package `com.sbss.azwall` 0.8.0 (800), **unsigned** (no `android/keystore.properties`), six native libraries all `arm64-v8a`, bundled config names the registry's public hosts only |
| `dev.azwall.core.wallet.*` forced re-run | 103 tests, 0 failures |
| archived `OpenTestAuditTest` (OT-01, OT-02) | 2 of 2 pass once the event carries `id` and the refusal is asserted at `release(hostAlive = false)` |
| archived `FinalOpenAuditTest` (FO-01), adapted to the uncertain rule | 2 of 2 pass |
| probe tests for NF-1 | fail as predicted (the defect), archived |
| Pixel 3 (Android 12, arm64) over USB | still on its lock screen: the staging build installed at 12:42 (`dev.azwall.staging`, from `b72d37b`) started without error; nothing past the launch was exercised, and the current revision changes the application id to `com.sbss.azwall`, so the staging build must be reinstalled as `com.sbss.azwall.staging` |

Not done: the device flows (phone locked), Play Console, a signed bundle.

## Store listing and policy

`store/play/README.md`: name "Azwall: Aztec Wallet", short description without a privacy claim, full description
consistent with the app (every transaction confirmed, the site's real domain shown, no analytics), "early test version"
warning, Data safety proposal declaring addresses and transactions as shared with the RPC providers; privacy policy
without the obsolete endpoint-setting claim and saying the providers' logs are theirs. Images at Play's sizes. The
app's identity is now the publisher's (`com.sbss.azwall`): keep it; it cannot change after the first upload.

## Before opening the track

1. **Fix NF-1** (and NF-2 in the same lines), add the probe as a test, re-run `scripts/test-all.sh`.
2. **Sign**: create the upload key and `android/keystore.properties` (`docs/release.md`), rebuild
   `:app:bundleRelease`, check the bundle carries a signature and a version code never uploaded.
3. **Pixel 3**: unlock the phone, `./gradlew :app:installStaging`, then with the owner's finger at each prompt: create
   and restore, fingerprint and PIN unlock, private transfer on testnet, shield and unshield, activation and fees, dApp
   connect, reject and approve, cancel before the proof leaves, kill the app during a send and reopen, Retry after a
   stopped engine, approve a dApp transaction right after a failed transfer. Record the revision and results.
4. **Play Console**: organisation account, financial-features declaration, Data safety, privacy-policy URL, listing,
   content rating, pre-launch report. Decide whether the open test exposes mainnet or ships testnet only.
