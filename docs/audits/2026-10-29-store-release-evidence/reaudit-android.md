<!-- Working notes of one area of the 2026-10-29 re-audit. The consolidated, verified report is ../2026-10-29-store-release-reaudit.md; where the two differ, it prevails. -->

# Android re-audit of the 2026-10-28 store-release fixes — 2026-10-29

Revision read: `01267c0` (HEAD of `main`). Android commits since the audited `ef3ccb5`: `758da46`, `9813055`, `20b03b2`,
`96ff7bb`, `3ee6e59`, `9a0eed8` (`git log --oneline ef3ccb5..HEAD -- android`). Read-only; the two temporary probe tests
written for this re-audit were deleted after their run (`git status --short -- android` is empty). One untracked file,
`pxe/host/test/zz_reaudit_tmp.test.ts`, is in the tree and is not mine (never touched `pxe/`): left alone, reported.

Checks run on this revision:

| Check | Result |
|---|---|
| `./gradlew --quiet :core:testDebugUnitTest --rerun` (forced, XML dated 11:06 today) | **core 140 tests, 0 failures** (134 at `ef3ccb5` + LostKeyTest 2, DappLinkTest 2, SubmissionTest 1, BrowsingTest 1) |
| `./gradlew --quiet :app:testDebugUnitTest --rerun` (forced, XML 11:04 today) | **app 31 tests, 0 failures** (28 + SignTest 2, AutoLockTest 1) |
| `./gradlew --quiet :app:lintRelease` | exit 0, **0 errors, 17 warnings** (UseKtx ×5, IconLauncherShape ×5, ObsoleteSdkInt, NotShrinkingResources, NewerVersionAvailable, MonochromeLauncherIcon, GradleDependency, DataExtractionRules, AndroidGradlePluginVersion): the same set as yesterday |
| `scripts/check-limits.py`, `scripts/check-swallowed.py` | both pass |
| Probe tests (7, deleted afterwards) | outputs quoted below as PROBE n |

Note: a plain `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest` was UP-TO-DATE (reports dated 22:10 yesterday); the
counts above come from forced re-runs.

## Verdicts

### F6 — Android regenerates a missing Keystore key — **closed** (with one UX hole, N3)

- `open()` no longer generates: `KeystoreSecretBox.kt:58` and `PromptedSecretBox.kt:141` init the cipher with
  `keyToOpen(existing())`; `keyToOpen` (`KeystoreSecretBox.kt:31`) throws `VaultKeyLost` on null. `key()` (= `existing() ?: generate()`)
  is now called from `seal()` only (`KeystoreSecretBox.kt:49,79`; `PromptedSecretBox.kt:131,161`), so a **fresh vault still
  generates its key at creation** (`Vault.create` → `write` → `seal`). A key left by a previous install (Keystore entries are
  per-UID and cleared on uninstall; the rare leftover is harmless) would be reused by `seal`, consistently, and `open` is never
  reached without a vault file.
- A key under the alias that is not the one that sealed the vault (replaced) → `AEADBadTagException` → `VaultKeyLost` via
  `openingWith` (`KeystoreSecretBox.kt:37-41`). The `.previous` fallback of `Vault.unlock()` (`Vault.kt:72-78`) still applies:
  `VaultKeyLost` is neither `AuthenticationCancelled` nor `AuthenticationRequired`, so an upgrade whose new key fails goes back to
  the legacy vault. Good. `PromptedSecretBox.open` checks the key *before* the prompt (`:141-142`): no pointless biometric.
- `AppViewModel.unlock()` (`AppViewModel.kt:142-152`): `VaultKeyLost` → `KeyLost` phase; any other exception that is not a
  decline sets `unlockFailed`; success clears it. `PromptAuthorizer.authorize` (`PromptAuthorizer.kt:58-69`) maps **every**
  BiometricPrompt error (lockout, HW unavailable, timeout, user cancel) to `AuthenticationCancelled`, so a lockout does not
  show the way out. What does: `UnrecoverableKeyException` from `getKey` (OEM variant of an invalidated key), Keystore
  `IllegalBlockSizeException`/`KeyStoreException`, a corrupt file (`require` "corrupt vault"), a JSON decode error, an
  `IOException`. Those are exactly the "wedged" cases F6 asked a way out for, but they include transient ones (N3).
- The way out (`LockScreen`, `Onboarding.kt:234`): a `TextButton` shown only when `failed`, disabled while `busy`, so it cannot
  be tapped while an unlock is in flight; `cannotUnlock()` (`AppViewModel.kt:171-173`) only moves to `KeyLost` when
  `unlockFailed` and not unlocked. `KeyLostScreen` (`Onboarding.kt:199-221`) needs a tap plus an `AlertDialog` "Start over?"
  that says the wallet is removed and needs the phrase. `startOver()` (`:179-183`) checks the phase and runs
  `vaultState.erase(deps::wipe)` **under the vault mutex** (`VaultState.kt:209-212`), so a save still in flight lands first and
  is then deleted: no race. **No authentication is asked** (by design: the key may be gone), and the wipe is irreversible
  without the phrase (`AppContainer.wipe`, `AppContainer.kt:304-314`: vault, both Keystore aliases, the store dir with the
  history and the diagnostic log, prefs, WebStorage). Equivalent to uninstalling, which anyone holding the unlocked phone can
  do already: not a security regression.
- Tests: `LostKeyTest` tests the two helpers in isolation (the Keystore path is instrumented-only; the wiring is three
  one-line call sites, read above): adequate for the JVM. `SignTest.anUnlockThatKeepsFailingOffersTheWayToRestore` and
  `decliningTheUnlockOffersNothingMore` cover the view-model state machine. Still unverified on a device: a real removal of the
  screen lock (whether `getKey` returns null, throws, or the key is reported invalidated); every branch now ends in the same
  place, so the device check is confirmation, not risk.

### F15 — a dApp transaction in flight unprotected — **partially closed**

- Closed: the background auto-lock and the foreground check wait for a dApp transaction (`WalletSession.sending`/`isSending`,
  `WalletSession.kt:171-177`; `AppViewModel.kt:216,226`).
- Not closed: `lock()` (`AppViewModel.kt:239-245`, reached from the drawer `WalletScreens.kt:93` and Settings `:244`),
  `switchNetwork()` (`:185-192`), `restart()` (`:195-200`) and `onCleared()` (`:233-237`) have no guard; a dApp transaction
  still has no overlay, so the back gesture on Home or the browser start page finishes the activity mid-proof; and
  `WalletSession.close()` (`:256-264`) still sets `running.value = null` and cancels the scope before the PXE's `close()` runs,
  so a `txSubmitting`/`dappTx` emitted in that window is dropped and the row becomes "interrupted before the proof was sent"
  at the next open although the node may have it. Same scenario as yesterday's F15 (approve a dApp transaction, tap "Lock
  wallet" in the seconds after the proof left).
- Tests: `AutoLockTest` covers the auto-lock part only. No test closes/locks between `release()` and `txSubmitting`.

### F16 — auto-lock skipped, not re-armed, while proving — **closed for the wallet's own transaction, regressed for dApps** (N2)

- `onBackground()` (`AppViewModel.kt:211-218`) now waits `session.sending.first { !it }` then locks: a wallet proof that runs
  when the timer fires no longer leaves the wallet open for good (the wallet's `proving` is cleared in `track`'s `finally`,
  bounded by the PXE call's 20-minute timeout, `PxeChannel.kt:133-136`). It does not lock in the middle of a proof.
- But the wait is unbounded and a DAPP row stays PROVING until the gateway's `sent`/`failed` event, which comes only after
  the site's `wait` (`gateway.ts:339-352`) and never if the host dies: see N2 (PROBE 1, PROBE 2).
- `AutoLockTest.theAutoLockWaitsForADappTransactionOnItsWayThenLocks` is meaningful (background, timer, still unlocked, then
  locks on `failed`); the wallet's-own-proof case of F16 proper is not tested but follows the same flow.

### F22 — PXE web view shares the renderer with dApp pages — **not closed**

No change: no `android:process` on any component, no `setProfile`, no `:pxe` service (grep of the manifest and sources);
nothing in `docs/` documents the limitation either. Still the hardening item it was (P3).

### F13 (Android side) — a failure before the proof left keeps the gate shut — **closed as written, with a P2 regression** (N1)

- `Transactions.proveAndSend` (`Transactions.kt:184-195`): a failure that is neither a cancel nor after `released` calls
  `ProofGate.cancel()` and rethrows; the late proof then hits `ProofGate.check()` in the prove handler
  (`AndroidPxeLauncher.kt:47`) and is refused. `track()` (`:154-163`) records FAILED (no hash can exist before release, since
  `txSubmitting` is emitted inside the `node.sendTx` wrapper, `submit.ts:51`). The next wallet transaction reopens the gate
  (`:179`), so it never refuses the next legitimate *wallet* proof. `settle()`/`winding` are untouched (the new path sets no
  `winding`), which is precisely why dApp proofs are refused: N1.
- Residual, pre-existing (yesterday's F18 remark "key the gate to the item"): the gate is global, not bound to the item. If
  the user starts transaction B before A's late proof arrives, B's `reset()` reopens the gate, A's proof passes, A is sent,
  and `followSubmissions` (`:256-258`) writes A's hash on B's PROVING row (B's own `txSubmitting` then matches nothing).
  Window: the seconds between the app resuming and A's proof finishing. PLAUSIBLE, narrow, unchanged by the fix.
- `SubmissionTest.aTransactionThatFailedBeforeItsProofLeftIsNeverSentLater` is meaningful for the wallet case; its assertion
  `assertThrows(CancelledByUser) { ProofGate.release() }` is in fact the global state N1 describes.

### F7/F8 (Android side, `758da46`) — **closed**

- `DappTx.applyTo` (`Dapps.kt:247-254`): a hash known from `txSubmitting` is never dropped; `sent` without a hash in the
  result keeps the told one and is PENDING; `failed` with a hash is PENDING and followed (the gateway now reports such a case
  as `sent` itself, `gateway.ts:344-353`, so this branch is belt-and-braces). `followActivity` (`:186-189`) reads the stored
  item first and launches `confirm` for PENDING.
- `Transactions.track` (`:154-163`): a wallet send that fails after `txSubmitting` returns PENDING with the hash and is
  followed; the exception goes to the diagnostic log. One consequence, by design: a transfer the node *rejected* (the PXE
  retries `getTxReceipt` three times and rethrows only on three DROPPED answers, `submit.ts:55-73`) now shows "Pending" and
  then "dropped by the network" instead of the node's reason, which only the log keeps. Cosmetic.
- `DappLinkTest` +2 and `SubmissionTest.aSendThatFailsAfterTheHashWasToldIsFollowed`: meaningful, end to end on the fakes.

### F9 (Android side, `9813055`) — registrable domain first — **closed, with a public-suffix gap** (N5)

`SiteName.domainOf` (`Browsing.kt:180-186`): strips scheme, path, port, trailing dot, lowercases; IPv4 and single labels
whole; `co.uk`-style second levels under a two-letter TLD. `ApprovalPanel.Header` (`ApprovalPanel.kt:323-326`) shows the domain
bold, middle-ellipsized, and the full host below it when different. PROBE 7: `app.aztec.network.wallet-session-verify.evil-host.io`
→ `evil-host.io`; `aztec.network.evil.io` → `evil.io`; IDN stays punycode (`xn--80ak6aa92e.com`: no homograph, unreadable but
safe); `localhost:5173` → `localhost` with the port on the host line; `foo.bar.co` → `bar.co`; `[::1]` → `[` (debug-only,
cosmetic); `user@evil.io` kept whole (a WebView origin never carries userinfo: unreachable). The gap: shared-hosting suffixes
(N5). `BrowsingTest` +1 covers 7 cases, none of them a shared suffix.

### F20 (Android side, `96ff7bb`) — tap delay — **closed**

`ApprovalPanel.kt:292-296,310-311`: `armed` is `remember(request.id)`, set after 500 ms by a `LaunchedEffect(request.id)`;
both buttons are `enabled = armed`, so a new request (new id) re-arms. The details sheet's buttons are not delayed but are only
reachable through a tap on "View all details" of an already-shown panel. Not JVM-testable (Compose); read.

### DiagnosticLog redaction (yesterday's Android P3 bullet, `9a0eed8`) — **closed for what it set out to do**

`DiagnosticLog.kt:90-95`: bare 38+ hex runs between word boundaries and 40+ base64 runs are cut. PROBE 6 (every line below
is in/out of `redact`):

- `key ab…(64)` → `key ababab…abab`; `0X`-prefixed and `sk_`-prefixed keys are cut too (by the base64 rule);
  `txHash=ef…;` → `txHash=efefef…efef;`; the kotlinx `JSON input:` snippet keeps its `0x` key cut.
- Kept intact: UUIDs, stack frames (`WalletSession$open$2.invokeSuspend(WalletSession.kt:201)`), Aztec assertion texts, exception
  class names, the store's own messages, "no answer from the wallet engine after 20 min". The log stays useful.
- Not cut: a 64-hex key written in groups with spaces or dashes (no current path writes one that way; the only grouped
  input is what the user types, normalised before use), URL-safe base64 (`-`/`_` break the run), BIP-39 words (yesterday's
  suggestion, not implemented; no path logs the phrase), and the **userinfo of a URL** (`https://user:SECRETPASS@lb.drpc.org`
  is kept: the URL rule keeps everything up to the first `/`). A release build refuses `.env` endpoints, so the last one is
  unreachable in the store build; hygiene for debug builds (N6).
- A 40+ run of letters and digits is cut (`Supercalifragilistic…1234567890ABCDEFGHIJ` → `Superc…`): long identifiers without
  dots or underscores would be, which is rare in this log. `DiagnosticLogTest` +3 assertions: fine.

### `wipe()` not transactional (yesterday's Android P3 bullet) — **not addressed**

`AppContainer.wipe` (`AppContainer.kt:304-314`) is unchanged: no WebView HTTP cache or form data cleared; a process death
between `vault.wipe()` and `storeDir.deleteRecursively()` still leaves the plaintext store. Unchanged P3.

## New findings

### N1 — P2 — CONFIRMED — Every dApp proof is refused after any wallet transaction that failed before proving (regression of `3ee6e59`)

Locations: `Transactions.kt:189-192` (any exception of `send()` with `released == false` and no cancel → `ProofGate.cancel()`);
`AndroidPxeLauncher.kt:47-50` (`ProofGate.check()` then `release()` for **every** proof, dApp ones included);
`Transactions.kt:179,193,208` are the only `ProofGate.reset()` calls in the app (grep); `Dapps.kt:117-128`
(`DappApprovals.answer` calls `settle()`, which joins `winding`, null on this path, so it returns at once).

The condition fires not only on the 20-minute timeout it was written for but on **every** failure inside the PXE call before
the proof is released: the common ones are simulation errors ("Balance too low", an account not deployed, an authwit missing),
a gas estimate the node refuses, the host stopping. The gate then stays shut, and nothing but the next *wallet* transaction
(or a process restart) reopens it: not a lock, not a network switch, not Retry. The next dApp transaction the user approves
and confirms with their fingerprint reaches the prove handler, `check()` throws, the PXE's `sendTx` fails, the gateway emits
`dappTx failed` with the text "You cancelled the transaction; nothing was sent." (`gateway.ts:349`) and tells the site the
same. The activity row and the site both say the user cancelled; retrying gives the same.

PROBE 5 (fake PXE, `whileProving` throwing "Assertion failed: Balance too low"): `wallet send failed with: PxeException`, then
`settle()` returned, then `ProofGate.check()` → `CancelledByUser: You cancelled the transaction; nothing was sent.`

Scenario: the user tries a transfer with too little balance (Failed, correct), opens a dApp and approves a swap: "You
cancelled the transaction". Nothing is lost, nothing is sent; dApps are unusable until the user manages a wallet transaction.

Fix: keep the gate shut only for the failure it targets (the call timed out: `PxeException` whose message is the channel's
"no answer from the wallet engine", or better a dedicated exception type from `PxeChannel.call`), and reset it on every
other failure as before; and bind the gate to its owner (the PXE could tag `prove` calls with the originating call, wallet
or dApp, so a dApp's proof is never judged by a wallet transaction's gate). Regression test: a wallet send failing at
simulation, then `ProofGate.check()` must not throw; and the reverse for a timeout.

### N2 — P3 — CONFIRMED — The background auto-lock can now wait for good on a dApp transaction (regression of `96ff7bb` for that case)

Locations: `WalletSession.kt:176-177` (`isSending`: any DAPP row with status PROVING), `AppViewModel.kt:216` (unbounded
`first { !it }`), `Dapps.kt:186-189` (the row leaves PROVING only on a `sent`/`failed` event), `gateway.ts:339-352` (`sent` is
emitted after `rawCall(sendTx)` returns, i.e. after the site's `wait`), `WalletSession.kt:224-230` (`watchHost`: the host's
death sets `phase = FAILED` and touches no row).

- (a) The PXE host dies while a dApp transaction is proving (the system ending the renderer under memory pressure in the
  background is exactly when): no event ever comes, the row stays PROVING, `isSending` stays true, the wallet never locks.
  PROBE 1: `dappTx proving`, then the `closed` event, `onBackground()`, 6 h later: `phase = Unlocked, isSending = true`. At
  `ef3ccb5` the same situation locked (only the wallet's own `proving` was checked).
- (b) The row stays PROVING after `txSubmitting` told its hash, until the site's `wait` returns (300 s by default, whatever
  the site asks for). PROBE 2: hash told, still PROVING, 1 h in the background: `Unlocked`. Locking at that point would be
  safe: `failInterrupted` turns a PROVING row with a hash into PENDING and follows it (`WalletStore.kt:146-151`).

Fix: `isSending` false when `state.phase == FAILED`; a DAPP row with a hash does not block (the proof left); and bound the
wait (lock anyway after the long-call timeout). Test: PROBE 1 as a regression test.

### N3 — P3 — CONFIRMED — The lock screen's way out is offered without a failed unlock after a start-over, and after a single transient failure with the wrong explanation

Locations: `AppViewModel.kt:149,152` (`unlockFailed` set on failure, cleared on success only; never by `startOver()`,
`wipe()`, `createWallet()`), `Onboarding.kt:204-206` (the KeyLost text asserts the screen lock was removed), `:179-183`
(`startOver` wipes without authentication).

PROBE 4: a broken box → `unlock` fails → `cannotUnlock()` → `startOver()` → `wiped = true, phase = Onboarding`,
`unlockFailed = true` still. The next wallet created in that process shows "Can't unlock? Restore with my recovery phrase"
on its very first lock screen; the tap leads to a screen saying Android erased the key, and "Start over" removes that healthy
vault without a prompt. Separately, one transient Keystore error (a stale auth token wrapped in `IllegalBlockSizeException`,
a Keystore daemon hiccup, both known on OEM devices) shows the same option and the same (false) explanation; a user who
follows it wipes a vault that would have opened on retry. Funds stay recoverable from the phrase, by construction of
onboarding; a wallet imported from a signing key without a backup would be lost, which the dialog does say.

Fix: clear `unlockFailed` in `startOver`, `wipe` and `createWallet`; word the KeyLost screen for the `cannotUnlock` case
("the wallet could not be unlocked: <reason>; try again, or restore") or require two consecutive failures; when
`deps.deviceSecure` is true, ask for the device credential (a BiometricPrompt without CryptoObject) before the wipe.

### N4 — P3 — CONFIRMED — `isSending` only sees the selected account's rows

`WalletSession.kt:177` reads `state.activity`, which `recordActivity` (`:457-460`) fills for the selected account only. A
dApp transaction approved for account A, then the account sheet switched to B (reachable: no overlay for dApp transactions),
then the app backgrounded: PROBE 3 (`dappTx proving` for `addr-other`): `isSending = false`, locked after 6 minutes, mid-proof.
Pre-existing behaviour the fix does not reach; the consequence is yesterday's F15 window. Fix: read `store.proving()`.

### N5 — P3 — PLAUSIBLE — Shared-hosting suffixes read as the domain

`Browsing.kt:183-185` takes the last two labels unless a two-letter TLD with a known second level. PROBE 7: `evil.github.io`
→ **github.io**, `azwall.github.io` → **github.io** (the project's own documentation host), `app.vercel.app` → **vercel.app**,
`s3.amazonaws.com` → **amazonaws.com**. On such suffixes the bold line is the part every tenant shares and the attacker chooses
the part shown only on the small host line, middle-ellipsized if long. The two shipped dApp hosts (`aztec.palmyr.io`,
`aztecscan.xyz`) are on their own domains, so no shipped site is imitable this way today. Fix: a short list of multi-tenant
suffixes (`github.io`, `vercel.app`, `netlify.app`, `pages.dev`, `web.app`, `workers.dev`, `herokuapp.com`, `amazonaws.com`,
`cloudfront.net`, `azurewebsites.net`, `appspot.com`) that take three labels, with a test. `[::1]` → `[` is cosmetic (debug only).

### N6 — P3 — PLAUSIBLE — Redaction leaves a URL's userinfo, grouped hex and URL-safe base64

See the DiagnosticLog verdict above (PROBE 6). None is reachable by a current log path in a release build (`.env` endpoints
refused; keys never grouped; phrase never logged). Cheap: drop `[^@/\s]*@` from the kept host in the URL rule; add `-`/`_` to
the base64 class.

## Tests: assessment

| Test | Meaningful? |
|---|---|
| `LostKeyTest` (2) | Unit tests of `keyToOpen`/`openingWith` only; the Keystore itself is instrumented-only. Adequate given the three one-line call sites. |
| `SignTest` +2 | Yes: the `unlockFailed` → `cannotUnlock` → `KeyLost` path and the decline case. Misses the reset after start-over (N3). |
| `AutoLockTest` (app, 1) | Yes for the dApp case; the wallet's own proof and the host-death case (N2) are not covered. |
| `SubmissionTest` +1 (and 1 rewritten) | Yes for the wallet case; the rewritten one now asserts PENDING then CONFIRMED on a failure after the hash. The new one's `assertThrows { ProofGate.release() }` documents the state behind N1 without testing a dApp. |
| `DappLinkTest` +2 | Yes: hash kept without one in the result; failed-after-told followed to CONFIRMED. |
| `BrowsingTest` +1 | Yes, 7 cases; no shared-suffix case (N5). |
| `DiagnosticLogTest` +3 assertions | Yes, including a "words stay words" line. |

## Other observations (no finding)

- `3ee6e59`'s gate also does not bind a late proof to its item (see F13 above): pre-existing, window narrowed, not closed.
- `AppViewModel.unlock` catches `Exception`, which includes `CancellationException`: a cancelled unlock (screen gone) would set
  `unlockFailed`; harmless since the view model is gone with it.
- `seal()` of a fresh vault with a *permanently invalidated* key left under the alias (only after a partial wipe) throws
  `VaultKeyLost` from onboarding with no way to proceed: pre-existing, needs a partial wipe first.
- Nothing new on Android sends without consent or leaks a secret: every signing path still goes through `sign()`/`answer()`
  with the owner's biometric; the new UI states show no secret; the redaction got stricter, not looser.
