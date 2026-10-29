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

## Re-audit at `5bc258a` (evening, second pass)

One code commit since `4bbcbc9`: `106ec68`, which addresses NF-1, NF-2 and NF-3 as recommended (the generic catch reads
the stored hash first, `unsent()` keeps "uncertain" only when no hash came, the unanswered branch is decided by
`cancel()` alone, an uncertain row holds the auto-lock and reads "Unknown: it may have been sent", three regression
tests). The rest is branding and the welcome screen's layout.

| Check | Result |
|---|---|
| archived `ReauditProbeTest` (NF-1 probes, retired-host interleavings) | the two NF-1 probes and the interleaving probe pass; the fourth probe fails because it asserts the previous behaviour ("the auto-lock does not wait for an uncertain row"), which NF-3's fix reversed on purpose |
| archived `FinalOpenAuditTest` (adapted) and `OpenTestAuditTest` (v2) | 2 of 2 and 2 of 2 pass |
| `scripts/test-all.sh` | pass (PXE 14 + 100; Rust 73; Android suites) |
| `./gradlew lintRelease :app:bundleRelease` | pass; `app-release.aab` 71,956,141 bytes, `com.sbss.azwall` 0.8.0 (800), **still unsigned** |
| Pixel 3 (Android 12, arm64), staging `com.sbss.azwall.staging` installed at 16:56 from `5bc258a`, driven over adb with the owner authenticating at each prompt | **Passed so far**: launch and welcome screen (`pixel3-welcome.png`); create a wallet: 24 words shown under `FLAG_SECURE` (a screenshot of that window comes back empty), checkbox, the three-word check (words 10, 14, 23), the per-operation Keystore prompt "Save your wallet" (fingerprint or pattern); lock screen and unlock prompt; PXE opened on testnet, node reached ("its fee is sponsored on this network", Settings: node 6.0.0, block 10885, RPC shown as its host only, prover Barretenberg 6.0.0); account activation cancelled during the proof (overlay with Cancel, row "Cancelled · Failed", nothing sent); real activation: "Confirm the transaction" prompt, proof in about 20 s on the phone, "Activation sent", row Pending; `am force-stop` while pending, relaunch, unlock prompt required (system log: biometric session 17:23:28, authenticated 17:23:30), row reconciled to Confirmed within seconds; Settings "Remove wallet from this device" after a confirmation dialog and the prompt; restore from the 24 words: "Secure your wallet" prompt, same address `0x0e8a…0e4d`, activation read back from the chain. No crash, no PXE warning or error in logcat. Then: "Create a token" (Test Token, TST, fee by the sponsor): proof in about 25 s, "Token creation sent", confirmed in block 10898; auto-lock: the app sent to the background for 5 min 25 s locked by itself with its process alive, and asked the unlock prompt on return; mint of 1,000 TST privately to the account (a "100" typed in the recipient field by mistake was refused: "an Aztec address is 0x followed by 64 hexadecimal characters"): proof in about 20 s, "Mint sent", confirmed in block 10921, balance 1,000 TST private; Account 2 added from the phrase after the vault-save prompt (`0x21d7…e052`); private transfer of 250 TST from Account 1 to Account 2 (address copied with "Copy address", pasted): the review sheet showed the amount, the full recipient address, "Amount, sender and recipient stay hidden", the network and "Fee: None, paid by the network's sponsor"; "Confirm the transaction" prompt, proof in about 20 s, "Sent". Confirmed in block 10953 ("Sent privately · to 0x21d7…e052 · −250 TST · Confirmed"); balances read back: Account 1 750 TST private, Account 2 250 TST private, 0 public on both. Not exercised: a dApp connect and approval (no Aztec dApp with the wallet-sdk protocol was tried). |

Observation (P3, not a regression): the 24 words are readable through the accessibility tree (that is how this run read
them), as on every Android wallet that does not mark the grid `importantForAccessibility = no`; doing so would hide the
words from screen readers too, so decide with that trade-off in mind. Leaving the welcome flow with the back gesture
keeps the process alive in the background on the phrase screen (secure-flagged, no vault yet): harmless.

**NF-1, NF-2, NF-3: closed. Device checklist passed** on a Pixel 3 (Android 12, arm64) with the staging build of
`5bc258a`: create, phrase check, Keystore prompt, lock and unlock, auto-lock in the background, PXE on testnet,
cancel during a proof, account activation (proof, broadcast, pending, reconciliation after `am force-stop`),
remove and restore from the phrase (same address, activation read from the chain), token creation, private mint,
a second phrase-derived account, a private transfer with its review sheet, and the balances of both accounts after
the block. Every signing step asked the fingerprint; no crash, no PXE warning in logcat. Not exercised: a dApp
connect and approval, and mainnet.

Observations from the run (P3, not blocking): an error from the Rust core reaches the screen with its field name
("detail=this is not a valid recovery phrase: …", from the uniffi error's string form); the back gesture leaves the
onboarding screens (the in-app arrow returns to the welcome screen) and sends the home screen to the background;
the 24 words are readable through the accessibility tree.

**Verdict: the code is ready for the Google Play open test.** What remains is the publisher's: the upload signature
(`android/keystore.properties`, then `:app:bundleRelease` and a check that the bundle is signed with a version code
never uploaded), the Play Console items (organisation account, financial-features declaration, Data safety, privacy
policy URL, listing, content rating, pre-launch report), and the decision whether the open test exposes mainnet.
