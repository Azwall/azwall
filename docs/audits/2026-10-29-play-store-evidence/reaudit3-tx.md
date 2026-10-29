<!-- Working notes of the transaction-path review of the 2026-10-29 Play Store readiness audit. The consolidated report is ../2026-10-29-play-store-readiness.md; where the two differ, it prevails. -->

# Re-audit 3 — transaction path (Android + shared PXE host) at `4bbcbc9`

Reviewed: `b5fee97` (gate refuses a retired host's late proof, host retired only while unreleased, FAILED session keeps
its reason), `854ebd9` (wallet send id in `txSubmitting`, UNCERTAIN state), `e0643b5` (decoded values through `oneLine`).
Context: `docs/audits/2026-10-29-open-testing-readiness.md` (N1–N5) and `docs/audits/2026-10-29-final-open-test-reaudit.md`
(FO-01; the file named "google-play-open-test-reaudit.md" does not exist, the final re-audit is the one at `aa64f39`).
Read-only on the repository; the two temporary tests described in §4 were added and deleted; `git status --short -- android`
is empty at the end.

## 1. ProofGate end to end

Design at HEAD (`ProofGate.kt:13-55`): one process-wide object with two flags, `cancelled` and `released`. There is **no
generation or token**: a proof is bound to its host by the *prove handler closure*, not by the gate. `AndroidPxeLauncher.launch`
(`AndroidPxeLauncher.kt:32-43`) builds one handler per host with `alive = { started?.channel?.closed != true }`, where
`started` is that launch's `PxeHost`; the handler calls `ProofGate.check()` before proving and
`ProofGate.release(hostAlive = alive())` after (`:49-53`).

- `check()`: throws `CancelledByUser` when `cancelled`.
- `release(hostAlive)`: `if (!hostAlive) throw CancelledByUser()` **before** reading the gate, then `check()`, then
  `released = true`. A retired host's late proof therefore neither sets `released` nor needs the gate to be cancelled.
- `cancel()`: false once released; else sets `cancelled`. `reset()`: clears both.

Retirement (`Transactions.kt:204-217`): an unanswered call (`PxeException.unanswered`, the 20-min channel deadline,
`PxeChannel.kt:69-73`) with the proof still unreleased → `ProofGate.cancel()` true → `session.retireHost(STUCK)`
(`WalletSession.kt:264-270`: `running = null`, `host.stop()` = `PxeHost.destroy()` → `channel.close(...)`, `scope.cancel()`,
`webView.destroy()`), then `ProofGate.reset()`. So the gate is **open** for the next host (N1), and the old host's handler
sees `alive() == false` for ever (`PxeChannel.closed`). Retry = `AppViewModel.restart()` → `close()` + new `WalletSession`
+ new launch → a new handler closure; the gate object is shared but was reset.

Interleavings of the retired host H1's late proof (H2 = the host after Retry):

| Late proof of H1 lands… | What happens | Sent behind a Failed row? | New tx marked "sending"? | dApp proof on H2 refused? |
|---|---|---|---|---|
| before Retry | `release(false)` throws; gate untouched (reset) | no | n/a | n/a (no H2) |
| while H2 starts | same; H2's handler is another closure | no | no | no |
| after H2's first wallet send started (`proveAndSend` did `reset()`) | H1 refused by `hostAlive=false`; H2's proof passes `check()` | no | no (`released` untouched) — probe 2 | — |
| after a dApp approval on H2 (gate already reset) | dApp proof: `check()` ok, `release(true)`; H1 refused | no | — | **no** (N1 closed; `aTransactionLeftUnansweredIsNeverSentLater` asserts it) |
| user cancels the new tx while H1's proof still runs | `cancel()` → `cancelled`; H2's proof refused by `check()`, wound down, `reset()`; H1 refused by `hostAlive` regardless | no | no | after `windDown` reset: no |
| two retirements in a row | H2 retired the same way; gate reset again; H1 and H2 both refused by their own `alive()` — probe 2 | no | no | no |

In practice H1's handler rarely reaches `release` at all: `destroy()` cancels the handler's scope, so `withContext(Default)`
throws `CancellationException` when the native proof returns and the reply goes to a destroyed WebView (posted after
`webView.destroy()`, FIFO on the main looper). Both paths refuse the proof.

Residual race in the handler (not reportable): `alive()` is evaluated before the synchronized `release`; a retirement
landing between the two makes `release(true)` set `released` on a gate nobody is proving on. The host is already destroyed
(nothing can be sent), and the next wallet send resets the gate; a dApp proof meanwhile would simply set `released` again.
Harmless.

**Verdict: N1 closed, OT-01 closed** (the gate itself refuses the retired host's proof and opens for the next host's;
the archived OT-01 test passes once adapted to the new design, §4).

## 2. The UNCERTAIN state (854ebd9)

Path (`Transactions.kt:204-209`): unanswered **and** `ProofGate.released.value` → `reset()`, throw `UncertainSubmission`.
`track()` (`:164-167`) records `draft.copy(error = UNCERTAIN)` (status stays PROVING), rethrows; `finally` clears
`_proving`. The host is **not** retired (the page may still send; nothing can stop that).

- **Never Failed**: yes, in the session (`anUncertainSendSaysSoAfterARestart`, `aReleasedButUnansweredSendStaysUncertain…`).
- **Retry waits**: `track()` `:148` refuses any wallet send while a non-dApp PROVING row carries `error == UNCERTAIN`
  (every account's rows), with the UNCERTAIN text as the error → snackbar. dApp sends are not blocked (they queue behind
  the stuck JS send in `SenderGuard`). An uncertain `DEPLOY_ACCOUNT` row also keeps `activating` true (`IN_FLIGHT`).
- **Late hash**: `followSubmissions` (`:276-296`) matches by `told.id` among PROVING rows without hash; an UNCERTAIN row
  becomes PENDING with the hash and is `confirm`ed (test `aReleasedButUnansweredSend…`).
- **What the UI shows meanwhile**: the overlay is gone (`_proving = null`); the snackbar shows the UNCERTAIN text once;
  the activity row shows the chip "Proving" (`Components.kt:87`) and its detail sheet "Being proven on this phone" with
  **no error line** (`ActivityDetails.kt:12` shows `error` only when FAILED; nothing in `android/app` references
  UNCERTAIN). A send attempt is refused with the text. Cosmetic P3: the row says "proving" while it is "sent, maybe".
- **Bound**: there is no time bound inside the session; the wait ends by lock/unlock or restart (`WalletStore.failInterrupted`
  `:146-158`: `error == UNCERTAIN` → FAILED keeping the UNCERTAIN text, hash null → not in `unsettled()`, not followed),
  or by the background auto-lock: `isSending` (`WalletSession.kt:180-181`) ignores a wallet row (only dApp rows and
  `proving != null` count), so the 5-min background lock closes the session while the page may be mid-send (probe 3).
  After that the row reads "Failed" in the list, the explanation only in the detail sheet, and nothing prevents a re-send
  (see NF-3).
- **`failInterrupted()` on restart**: `txHash != null` → PENDING (followed); `error == UNCERTAIN` → FAILED, text kept;
  else FAILED "interrupted before the proof was sent". Correct for the designed state; see NF-1 for the state the design
  does not produce on purpose (uncertain row whose hash was told and dropped).
- **Wallet send id**: `track()` `:161` uses `session.client.labelled(draft.id)` (`PxeClient.kt:127`: every call of that
  client carries `op`). Host: `createHandlers` wraps **every** handler (`api.ts:119-121`): a string `op` with an open wallet
  runs the handler inside `wallet.labelled(op, …)` (`wallet.ts:152-160`), which sets `#nextLabel`; the first `sendTx`
  consumes it (`:162-166`) and passes it to `sendLabelled` → `SenderGuard.sending(from, send, label)`; the label is set
  as `#label` when the send's turn comes, and `confirmSubmissions`' `submitting` callback emits
  `txSubmitting {from, txHash, id: label ?? null}` (`wallet.ts:112-117`, `open.ts:218`). Since proving happens inside
  `sendTx` and sends are serialised, the label is captured before the queue and cannot drift to the next send. Extra `op`
  keys are ignored by the handlers (`str`/`hex` readers). dApp path: `rawSend` → `sendLabelled(tx.id)` directly
  (`raw_call.ts:40-43`), never through the `sendTx` override, so it does not consume a wallet label.
  **Collision**: wallet ids are `UUID.randomUUID()`, dApp ids `crypto.randomUUID()` (both v4): no practical collision; and
  `followSubmissions` matches the id only among PROVING rows without a hash.
- **`followSubmissions` rules now**: with `id` → that row only; without `id` (older host, or `wallet` null at wrap time) →
  non-dApp PROVING rows without hash of `from` (or any account when `from` is null), `singleOrNull`. A row already FAILED or
  PENDING never matches: the hash is dropped silently (no log) — relevant to NF-2.
- Coverage gap (P3): no host-side test exercises `op` → `labelled` → `txSubmitting.id` (`pxe/host/test` has none); the
  Kotlin tests fake the event.

**Verdict on FO-01 / "uncertain"**: closed for the ordering FO-01 described (timeout **before** `txSubmitting`); **regressed
for the opposite ordering** (hash told, then timeout), see NF-1.

## 3. N2 and N5

- **N2** (ignored `cancel()` return): fixed — `:210` branches on `ProofGate.cancel()`; a release landing before it makes
  `cancel()` false and the host is **not** retired. But the fix added a `released.value` read at `:206` ahead of it, which
  re-creates a read-then-act window with a worse outcome than the original N2: see NF-2.
- **N5** (STUCK overwritten by refresh): fixed — `WalletSession.refresh` keeps `error` when `phase == FAILED` (`:318`,
  `:323`); tested in `aTransactionLeftUnansweredIsNeverSentLater`. The handler-side guard now lives in
  `ProofGate.release(hostAlive=false)` and is tested; the `alive()` lambda itself (app module) still is not.
  Note (not a finding): `retireHost` calls `host.stop()` **before** recording STUCK; the CLOSED event makes `watchHost`
  record FAILED "the PXE host was stopped". On `Dispatchers.Main.immediate` (viewModelScope) that collector resumes inline
  inside `stop()`, so STUCK, written after, wins; the order would flip if `track()` ever ran off the main thread.
- **e0643b5**: `show()` returns `oneLine(text, 60)` for decoded values and struct field names (`calls.ts:76-80`); test
  `a decoded argument cannot forge lines through a struct field name` (`calls.test.ts:135-141`). R3 residue closed.

## 4. Test runs

1. Clean tree: `./gradlew --quiet :core:testDebugUnitTest --rerun-tasks --tests 'dev.azwall.core.wallet.*'` → **103 tests,
   0 failures, 0 errors** (16 suites; SubmissionTest 10, WalletSessionTest 41). Log: `run1-wallet.log`.
2. Archived `OpenTestAuditTest.kt.txt` copied to `android/core/src/test/kotlin/dev/azwall/core/wallet/OpenTestAuditTest.kt`.
   - **v1, minimal adaptation** (only the `txSubmitting` event carries `"id":"first"`, as the host now emits): **2 tests,
     1 failure**. `queuedDappSendsMustKeepTheBroadcastHashAcrossRestart` passes (OT-02).
     `aNewSendMustNotReleaseTheProofOfATimedOutSend` fails at its line 63 `assertTrue(ProofGate.cancelled)`: at HEAD the
     gate is reset once the host is retired (N1 fix), and the refusal moved to `release(hostAlive = false)`; the archived
     `ProofGate.release()` (default `hostAlive = true`) no longer models the retired host's handler. XML:
     `run2-opentest-v1.xml`.
   - **v2** (two more lines: that assertion replaced by a comment, `ProofGate.release()` → `ProofGate.release(hostAlive =
     false)`): **2 tests, 0 failures**. XML: `run3-opentest-v2.xml`. OT-01's intent (the old proof stays refused after the
     next send starts) holds under the new design.
   - File deleted afterwards.
3. Probe `ReauditProbeTest.kt` (4 tests, scratchpad copy kept, tree copy deleted): probe 1/1b **fail as predicted** (NF-1),
   probe 2 (retired host's late proof during the next host's send; two retirements) passes, probe 3 (uncertain row blocks
   neither dApp proofs nor the auto-lock) passes. XML: `run5-probes.xml` (`run4-probes.xml` was a first, inconclusive
   version of probe 1 whose in-lambda `AssertionError` bypassed `track`'s `catch (e: Exception)`).
4. `git status --short -- android` → empty. (An untracked `docs/audits/2026-10-29-play-store-evidence/` appeared during
   the session; not mine, untouched.)

## 5. New findings

### NF-1 — P2, CONFIRMED: a hash told before the timeout is dropped by the UNCERTAIN path (regression of 854ebd9)

`Transactions.kt:164-167`:
```kotlin
} catch (e: UncertainSubmission) {
    session.recordActivity(draft.copy(error = UNCERTAIN))   // draft.txHash == null: replaces the row, hash and all
    throw e
}
```
Scenario: wallet send; proof released; the PXE emits `txSubmitting {id}` **just before** `node.sendTx` (`submit.ts:53-54`);
`followSubmissions` writes the hash on the PROVING row; `node.sendTx` (an HTTP call with no app-side timeout) or the
three 2-s receipt asks hang until the 20-min channel deadline → `PxeException(unanswered)` with `released == true` →
`UncertainSubmission` → the row is **overwritten without its hash** (PROVING, error UNCERTAIN). The hash never comes again
(it was already told), so: the row stays UNCERTAIN, every wallet send is refused until lock/unlock; after that
`failInterrupted` makes it FAILED with hash null, `unsettled()` ignores it, nothing follows a transaction the node very
probably has, and the user may send it again. Probe 1 (`run5-probes.xml`): `status=PROVING txHash=null`; probe 1b after a
restart: `status=FAILED txHash=null unsettled=0`.

At `b5fee97` the same inputs took the generic path (`cancel()` false → `reset(); throw e` → `store.item(draft.id)?.txHash`)
and the row became PENDING with its hash — the ordering `txSubmitting` → timeout is the **more likely** of the two (the
`release` → `txSubmitting` window is milliseconds; `txSubmitting` → reply is the whole network call), so 854ebd9 fixed the
rarer ordering and broke the common one. Same effect if the event lands between `proveAndSend`'s throw and the upsert.

Fix (a few lines): in the `UncertainSubmission` catch, read the stored row first:
`val hash = session.store.item(draft.id)?.txHash; if (hash != null) { sent = PENDING with hash; confirm } else
recordActivity((store.item(draft.id) ?: draft).copy(error = UNCERTAIN))`. Add the test "a hash told before the timeout is
kept and followed" (probe 1) and its restart variant.

### NF-2 — P3, PLAUSIBLE: a release landing between the `released` read and `cancel()` fails the row while the page sends

`Transactions.kt:206-219`: `if (unanswered && ProofGate.released.value) → UNCERTAIN` then `if (unanswered &&
ProofGate.cancel()) → retire`. If the handler's `release(true)` lands between the read (false) and `cancel()` (now false),
neither branch is taken: `reset(); throw e` → generic catch → hash almost surely null yet (`txSubmitting` follows the
release by milliseconds) → row **FAILED** with the timeout message, host alive, the page sends the transaction, its
`txSubmitting` matches no PROVING row and is dropped silently. Sent behind a Failed row, hash lost, re-send possible.
Window: two adjacent statements, only on a 20-min timeout coinciding with the proof's release — the same size as N2 was,
with a worse outcome. Fix: decide from `cancel()` alone:
`if (unanswered) { val stopped = ProofGate.cancel(); if (stopped) session.retireHost(STUCK); ProofGate.reset(); throw if
(stopped) e else UncertainSubmission(e) }`.

### NF-3 — P3, PLAUSIBLE (by design, user-informed): after a lock the uncertain row is Failed and a re-send is allowed

`WalletStore.kt:153` + `AppViewModel.onBackground` + `WalletSession.kt:180-181`: an uncertain wallet row does not hold the
auto-lock (`isSending` counts dApp rows only), so five minutes in the background close the session while the page may be
mid-send; at the next unlock the row is FAILED (list chip "Failed", the UNCERTAIN text only in the detail sheet), not
followed, and `track()` no longer refuses a wallet send. The text says "locking and unlocking the wallet ends the wait",
so this is the documented exit, but the list shows "Failed" for a transaction that may be mined, which is the F7/FO-01
shape the audits asked to avoid. Suggest: count the uncertain row in `isSending` for the bounded wait (`MAX_SEND_WAIT_MS`
already caps it), and/or render FAILED+UNCERTAIN rows as "Unknown" rather than "Failed".

### Notes (not findings under the given criteria)

- The uncertain row shows "Proving"/"Being proven on this phone" with no explanation once the snackbar is gone (UX).
- No host-side test of `op` → `labelled` → `txSubmitting.id`; the `labelled` wrapper is applied to every handler, including
  reads, harmlessly (`#nextLabel` is cleared in `finally` when still the call's own).
- `retireHost` ordering relative to the CLOSED watcher is dispatcher-dependent (§3).
- N3, N4, F15/F18 (per-request gate, lock/switch during a dApp proof, stuck native proof holding the prover mutex) are
  unchanged and out of this round's scope.

## 6. Summary of verdicts

| Item | Verdict |
|---|---|
| N1 (gate cancelled after Retry) | closed |
| N2 (`cancel()` return ignored) | closed as stated; residual read-then-act window re-created with a worse outcome (NF-2, P3) |
| N5 (STUCK overwritten; guard untested) | closed; `alive()` lambda itself still untested (app module) |
| OT-01 (late proof released by the next send) | closed; archived test passes once adapted to the new design (v2) |
| OT-02 (queued dApp sends lose the hash) | closed; archived test passes with the `id` |
| "uncertain" state (FO-01) | closed for the FO-01 ordering; **regressed for hash-told-then-timeout (NF-1, P2 CONFIRMED)** |
| e0643b5 (R3 residue) | closed |

Recommendation: fix NF-1 (and NF-2 in the same lines) before uploading; both are a few lines in `Transactions.kt` with a
test each from the probe file (`scratchpad/ReauditProbeTest.kt`).
