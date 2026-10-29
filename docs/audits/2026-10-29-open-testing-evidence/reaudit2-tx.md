<!-- Working notes of one area of the 2026-10-29 open-testing readiness review. The consolidated report is ../2026-10-29-open-testing-readiness.md; where the two differ, it prevails. -->

# Re-audit 2 — transaction path (R1, OT-01, OT-02) at `b72d37b`

Reviewed: `35e48aa`, `2902145`, `1e2cfa4`, `b72d37b` on top of the re-audit of 2026-10-29 (R1–R10) and the open-test audit
(OT-01..03). Method: end-to-end trace of the Android, iOS and PXE-host code named in the brief; the archived reproduction
(`OpenTestAuditTest.kt.txt`) run twice against `b72d37b` (as archived, then adapted to the event shape the host now
emits, plus one labelled probe); the new tests read. Repository untouched (`git status --short` empty after the runs);
the adapted test and its JUnit XML are kept in this scratchpad (`OpenTestAuditTest.adapted.kt.txt`,
`OpenTestAuditTest.run2.xml`).

## Verdicts

| Finding | Verdict | One line |
|---|---|---|
| R1 (gate shut after any failed send) | **closed** on both platforms | Only a `PxeException/PxeError(unanswered = true)` — set solely by the channel's timeout — keeps the gate shut; every other error resets it. Residual N1 (the unanswered case itself leaves the gate shut across a Retry). |
| OT-01 (next send reopens the gate for an older timed-out proof) | **closed** for the reported scenario | `cancel()` precedes `retireHost()`; a retired host's proof is handed back to nobody (`alive()`/`isClosed`, and on Android the host scope's cancellation as well); a FAILED session refuses `track`. Residuals N2, N3 (both PLAUSIBLE, tiny or contrived windows). |
| OT-02 (queued dApp sends lose the hash) | **closed** | The gateway's `crypto.randomUUID()` id is carried by `SenderGuard.label` only during that send's turn, told in `txSubmitting.id`, and matched by row id on both apps; the wallet's own rows are matched by `kind != DAPP` so a dApp row of the same account cannot absorb or block them. Residual: no durable acknowledgement (pre-existing, P3). |

### R1 — closed

- Android `Transactions.kt:192`: `if (!cancelled && e is PxeException && e.unanswered && !ProofGate.released.value)`; iOS
  `Transactions.swift:172` same. `unanswered` is set in exactly one place per platform: `PxeChannel.kt:73` (the
  `withTimeout` branch of `call`) and `PxeChannel.swift:176` (`expire`). A host-reported error (`settle`,
  `PxeChannel.kt:112` / `.swift:219`) and a closed channel (`PxeChannel.kt:67,102` / `.swift:157,206`) are plain errors
  → line 199 / 179 `reset()`. `PxeClient.transfer` (`PxeClient.kt:204-208,229`; `.swift:238-243,263`) rethrows the
  channel's exception unchanged, so the flag reaches `proveAndSend`.
- Tests: `SubmissionTest.aSendThePageRefusedLetsTheNextDappProofThrough` (`SubmissionTest.kt:170-179`) and the Swift twin
  (`SubmissionTests.swift:63-73`) exercise exactly the probe of the re-audit (plain error, then `check()`+`release()`).

### OT-01 — closed for the reported scenario

Chain, Android (iOS in brackets):

1. Timeout → `ProofGate.cancel()` **then** `session.retireHost(STUCK)` (`Transactions.kt:193,196`; [`Transactions.swift:173,176`]).
   Because the cancel comes first, every `release()` that runs after it throws `CancelledByUser` (`ProofGate.kt:44-48`,
   [`ProofGate.swift:44-49`]) whatever the host's state; the only `release()` that can succeed is one that ran *before*
   the cancel, and then `released == true` and the branch is not taken at all (line 192 / 172).
2. `retireHost` (`WalletSession.kt:264-270`; [`WalletSession.swift:326-334`]) nulls the host, stops it, sets
   `Phase.FAILED` + `error = STUCK`. `stop()` → `PxeHost.destroy()` (`PxeHost.kt:48-52`): `channel.close()` (so
   `channel.closed == true`, `PxeChannel.kt:96`), `scope.cancel()` of the host scope, `webView.destroy()` posted to Main.
   [iOS `PxeHost.stop()` (`PxeHost.swift:53-61`): `channel.close()`, message handler removed, page replaced by `""`.]
3. The old prove handler, when Rust returns: `if (!alive()) throw` (`AndroidPxeLauncher.kt:54`, `alive = { started?.channel?.closed != true }`,
   line 36). On Android there is a second, independent guard: the handler runs in the host scope (`PxeChannel.kt:120`,
   scope from `PxeHost.kt:63`), so after `scope.cancel()` its `withContext(Dispatchers.Default) { prove }` (line 51)
   completes a cancelled job and resumes with `CancellationException` before line 54 is reached; `runCatching` in `serve`
   turns it into an error reply, which is posted to Main *after* `webView.destroy()` and so reaches no page.
   [iOS: `Task.detached` is not cancelled; `if let channel = started.channel, await channel.isClosed { throw }`
   (`AppContainer.swift:125`) is the only guard; `started.channel` is set at line 133, right after `PxeHost.start` returned —
   `prove` cannot arrive before, since the session calls `open` only after `launch` returned (`WalletSession.swift:165-171`)
   and the PXE proves only inside a send.]
4. The next transaction on the same session is refused before `reset()`: `track` `check(phase != FAILED)`
   (`Transactions.kt:144`; [`.swift:121`]). `proveAndSend`'s `reset()` (line 181 / 161) is only reached from `track`.
   A Retry (`AppViewModel.restart()`, `AppViewModel.kt:213-218`; [`AppModel.restart()`, `AppModel.swift:141-147`])
   closes the session and builds a new `WalletSession` with a new host; the old handler then meets step 3.
5. Rust serialises proofs (`manager.rs:55-59`, `Mutex<Option<ProverEngine>>` held for the whole `prove`), so a new
   transaction's proof after Retry waits for the old one to end; it never runs concurrently with it (see N4 for the cost).

Reproduction (`aNewSendMustNotReleaseTheProofOfATimedOutSend`) passes as archived: the second `send` is refused by
`track` (FAILED) and the old `release()` throws `CancelledByUser`.

### OT-02 — closed

- Id origin: `gateway.ts:338` `crypto.randomUUID()`; the site's arguments are parsed by the Wallet schema
  (`raw_call.ts:41`) and the label is a separate parameter of `sendLabelled` (`wallet.ts:152-155`); a site cannot set or
  spoof it. Every send path (`api.ts:151`, `token.ts:127,136,144`, `api.ts:177` via aztec.js `send`) goes through the
  overridden `sendTx` → `sendLabelled(undefined, …)` → `SenderGuard.sending` (`senders.ts:23-37`), so `label`/`current`
  are set for exactly the send whose turn it is and cleared in `finally`. `confirmSubmissions`' wrapper calls
  `submitting(current, hash, label)` from inside `node.sendTx`, i.e. inside that turn (`submit.ts:97-99`, `wallet.ts:112-117`).
- Event: `open.ts:218` `{ from, txHash, id: id ?? null }`. Apps: `Transactions.kt:264-267` / `WalletSession.swift:314-319`
  match `told.id == row.id` among PROVING rows without a hash when an id is present; without one, only non-DAPP rows of
  the sender. The dApp row's id *is* the event's id (`Dapps.kt:232-240`, `Dapps.swift:232`).
- Wallet rows next to a dApp row of the same account: the wallet's `txSubmitting` has `id: null` → the dApp row is
  excluded by `kind != DAPP`; one wallet row at most is PROVING (`track`, line 142). A `txSubmitting` for a row that is no
  longer PROVING (or arrives before `dappTx proving` was applied) is dropped by design; the following `dappTx sent/failed`
  carries the hash (`Dapps.kt:247-253`). The only loss window is a process death between those two events, the
  "no durable acknowledgement" residual the previous audit already listed (P3, unchanged).
- Reproduction (`queuedDappSendsMustKeepTheBroadcastHashAcrossRestart`): fails as archived **only because the archived
  event has no `id`** (the fix deliberately refuses to guess among dApp rows); with `"id":"first"` as the host now emits,
  it passes, including the restart (`failInterrupted` keeps the first row PENDING with `0xaccepted`).

## The brief's adversarial questions, answered

1. **`alive()` vs `release()` race (host closed in between).** Benign: the retire is always preceded by `cancel()`, so
   a `release()` after the close attempt throws. The only interleaving that lets a proof through is `release()` *before*
   the timeout's `released` read, in which case nothing is retired (pre-existing narrow residual of F13: the row shows
   Failed while `txSubmitting` may land a moment later). The sub-case `release()` between the `released` read and
   `cancel()` is N2.
2. **Retired host, Rust proof still running, Retry + new transaction.** The new `reset()` cannot help the old handler:
   on Android it dies in `withContext` (host scope cancelled) and would be stopped by `alive()` anyway; on iOS by
   `isClosed`. The Rust mutex makes the new proof wait for the old one (N4).
3. **`retireHost` vs `close()` / `lock()` / `switchNetwork()` / `restart()`.** Android: all run on Main (`WalletSession`
   parent = `viewModelScope`, `AppViewModel.kt:352`) and `retireHost` does not suspend, so no interleaving; whichever
   runs first nulls `running`, the other returns early (`WalletSession.kt:265,277`). A double `destroy()` is idempotent
   (`channel.close` guarded, `WebView.destroy` tolerated). `watchHost` (`WalletSession.kt:228-234`) sees the CLOSED event
   of the retired host and writes "the PXE host was stopped": under `Main.immediate` it is resumed undispatched inside
   `tryEmit`, i.e. before `retireHost` writes STUCK, so STUCK wins (N5 notes the fragility). Pending approvals of the
   retired host vanish on their own: the `approve` handler coroutine is cancelled with the host scope and
   `DappApprovals.ask`'s `finally` removes the request (`Dapps.kt:102-107`). The dApp pages see a dead gateway until
   Retry; their relays are wrapped (`BrowserTabs.kt:42`, `BrowserHost.kt:90`, `action` catch `AppViewModel.kt:371-381`),
   so the new `IllegalStateException("the wallet is not open")` from `client` (`WalletSession.kt:187`) is logged, not
   fatal. iOS: `retireHost` is async; `close()` and it both null `pxe` first and return at the guard if the other won
   (`WalletSession.swift:219-220,327-328`); `eventLoop.cancel()` before `stop()` keeps `hostStopped` from overwriting
   STUCK. Pending approvals stay listed until Retry's `rejectAll()` (`AppModel.swift:143`); answering one only writes to a
   blank page.
4. **dApp send timeouts.** None apply. `dappMessage` returns at once (`api.ts:161`: `receive` is synchronous), the
   gateway runs `sendTx` asynchronously and answers through `dapp` events; the host→native `prove` has no timeout
   (`bridge.ts:80-86`, `prover.ts:34`). So a dApp send is never "unanswered", never retires the host, and never leaves a
   proof "sendable behind a failure": its row is driven by `dappTx` events, not by a call result. The one way a dApp proof
   interacts with the retire logic is through the global gate (N3).
5. **`track`'s FAILED check and a phase flip after it.** The only suspension between the check and `reset()` is
   `winding?.join()` (`Transactions.kt:180`; [`.swift:158`]); a flip there can only come from `watchHost`/`hostStopped`
   (host died), after which `send()` fails with a closed-channel error → `reset()` and a Failed row; `retireHost` of the
   same session cannot run concurrently (`_proving` guard). A flip from a *previous* session's retire is harmless because
   that host's handler is stopped by `alive()`/`isClosed`.
6. **Label spoofing / mismatch / stale rows / wallet rows** — see OT-02 above.
7. **iOS `StartedHost` before the first `prove`** — see OT-01 step 3. Note: a nil `started.channel` makes the check
   fail *open* (`if let … { throw }`); unreachable today, but `guard let … else { throw }` would be the safe form.

## Reproduction runs (archived test)

Command: `cd android && ./gradlew --quiet :core:testDebugUnitTest --rerun-tasks --tests dev.azwall.core.wallet.OpenTestAuditTest`

| Run | File | `aNewSendMustNotReleaseTheProofOfATimedOutSend` (OT-01) | `queuedDappSendsMustKeepTheBroadcastHashAcrossRestart` (OT-02) | probe |
|---|---|---|---|---|
| 1 | as archived, byte for byte | **pass** | **fail**: `expected:<0xaccepted> but was:<null>` — the archived `txSubmitting` has no `id` | — |
| 2 | adapted (below) | **pass** | **pass** | **fail** (N1, as predicted) |

Adaptations in run 2 (the fakes' signatures had not changed; the *event shape* had): (a) `"id":"first"` added to the
`txSubmitting` event, as `open.ts:218` now emits; (b) the `session()` helper creates the vault only when it does not
exist yet, so it can be called twice (the archived helper called `vault.create` unconditionally); (c) one added test,
`probe_aDappProofAfterRetryIsNotRefusedAsCancelled`: unanswered send → `Phase.FAILED` → `close()` → a second session
opened → `ProofGate.check()` must not throw. Result: `CancelledByUser: You cancelled the transaction; nothing was sent.`
The file was deleted afterwards; `git status --short` shows nothing under `android/`.

## Tests of the four commits — do they cover the scenarios?

- `SubmissionTest.aTransactionLeftUnansweredIsNeverSentLater` (`SubmissionTest.kt:152-167`) and the Swift twin
  (`SubmissionTests.swift:46-60`): meaningful for the core side — `stop()` called, FAILED + STUCK, a retry on the same
  session refused, a late `release()` refused, nothing sent. Not covered: the *Retry* path (new session, new host) with the
  old handler finishing afterwards — the piece that actually closes OT-01 (`alive()` on Android, `isClosed` on iOS) lives
  in the app modules (`AndroidPxeLauncher.kt`, `AppContainer.swift`) and has **no test on either platform**; the fakes
  release the gate themselves (`WalletFakes.kt:86-92`). Also uncovered: the gate's state after Retry (N1, found by the probe).
- `aSendThePageRefusedLetsTheNextDappProofThrough` (both platforms): meaningful, matches R1's probe.
- `queuedDappSendsKeepTheirHashAcrossARestart` (`SubmissionTest.kt:186-203`) / `queuedDappSendsKeepTheirHash`
  (`SubmissionTests.swift:76-88`): meaningful on the app side (restart covered on Android; iOS covers the match only, the
  store's `failInterrupted` separately). Host side: `dapp_safety.test.ts:104-118` tests `SenderGuard.label` in isolation
  only. Nothing spans `gateway.#send → rawSend → sendLabelled → submitting(label) → open.ts event`; a renamed field on
  either side would pass every suite (N5).
- `DappsTest`/`FeatureTests` updated to send `id` with `txSubmitting`: consistent with the new contract.
- `calls.test.ts`: changed by `9749bd8` (R3 sanitiser), not by these commits; unrelated to R1/OT-01/OT-02.
- `LifecycleTest.retryStartsANewSession` (`LifecycleTest.kt:109-117`): only asserts a new session object; does not
  retire a host or send. `AutoLockTest` (`:109-138`): R5's bounded wait; consistent with `isSending` treating a FAILED
  session as not sending (`WalletSession.kt:180-181`), so a retired session does not hold the lock.

## NEW findings

### N1 — P3 — CONFIRMED — After an unanswered send and a Retry, every dApp proof is refused as "cancelled" (R1's symptom, in the retire path)

`Transactions.kt:193` (`ProofGate.cancel()`) is never undone on this path: `track`'s `finally` does not reset, the
session is FAILED so no `proveAndSend` of its own will, and the new session after Retry (`AppViewModel.restart()`) starts
with the same process-global `ProofGate` object. The new host's prove handler begins with `ProofGate.check()`
(`AndroidPxeLauncher.kt:50`), so the first dApp transaction the user approves and confirms is refused, the gateway reports
`dappTx failed` "You cancelled the transaction; nothing was sent" and the site is told the same — until the user sends a
wallet transaction of their own (`reset()` at `Transactions.kt:181`) or the process restarts. iOS identical:
`gate.cancel()` at `Transactions.swift:173`, `ProofGate.shared` injected into every session (`WalletSession.swift:137`),
`ProofGate.shared.check()` at `AppContainer.swift:121`. Reproduced by the run-2 probe. Rated P3 rather than R1's P2 because
it needs the 20-minute unanswered precondition. Fix: once `retireHost` has returned the old host can no longer hand a proof
back (`alive()`/`isClosed`), so the gate can be reset there (or in `WalletSession.open()` of the next session); better,
bind the gate to the `prove` request (F18). Test: the probe above.

### N2 — P3 — PLAUSIBLE — `cancel()`'s answer is ignored, so a proof released in the same instant gets its host killed mid-send

`Transactions.kt:192-196` reads `ProofGate.released.value`, then calls `ProofGate.cancel()` and `retireHost()` without
looking at `cancel()`'s return. If `release()` (`ProofGate.kt:44-48`, synchronized) lands between that read and the
`cancel()` call, `cancel()` returns false (nothing is cancelled), yet the host is destroyed while the page is assembling
and posting the transaction whose proof it just received; the row is Failed, the node may or may not have it, and the
user re-sends. Same on iOS (`Transactions.swift:172-176`), where `retireHost` also cancels the event loop first, so a
`txSubmitting` arriving during the stop is never applied. The window is the few instructions between the read and the
lock, against a proof that must finish at that very moment: theoretical, but the fix is one line — take the branch only
if `ProofGate.cancel()` returned true, else `reset()` and rethrow as today.

### N3 — P3 — PLAUSIBLE — A dApp proof of another account marks the global gate `released`, so the retire never fires and the old late-proof path is back

The retire condition relies on `ProofGate.released` meaning "*this* transaction's proof left" (`Transactions.kt:192`),
but the gate is one process-global flag set by *any* proof (`AndroidPxeLauncher.kt:56`). `track` only refuses to start
while a dApp row of the **selected** account is PROVING (`Transactions.kt:148`, `state.activity` is the selected
account's, `WalletSession.kt:475`); a site whose capabilities were granted under account B keeps acting as B after the
user switches to A (`gateway.ts:199,381`), and its row is invisible to the check. Sequence: B's dApp send is queued ahead
in `SenderGuard`; the user sends from A (`reset()`); B's proof is released (`released = true`, nothing resets it until the
next `proveAndSend`); A's `transfer` call then times out at 20 min while A's own proof is still in Rust → the condition
sees `released == true`, does `reset()` and marks A's row Failed; A's proof is later released on the live host, the PXE
sends it, `txSubmitting{from: A, id: null}` finds no PROVING row (`Transactions.kt:264-267`) → sent behind a row shown
Failed, hash lost, re-send invites a double payment. Needs a second account's dApp session plus a 20-minute call: contrived,
not exercised here. Same blind spot lets a user Cancel land on B's dApp proof (the comment at `Transactions.kt:147` promises
the opposite). Fix: F18 (gate per request), or at least make the `track` check and `isSending` read `store.proving()`
across accounts as R5's fix did for the lock.

### N4 — P3 — PLAUSIBLE — A stuck native proof poisons every Retry, twenty minutes at a time

`manager.rs:55-59` holds the prover mutex for the whole proof; `prepare_prover`/`activate` take the same lock. After a
retire, the old host's `prove` may still be blocked in Rust (the very case the retire exists for). On Retry the new
session's `prepareProver` and the next transaction's proof wait behind it; if the old proof never returns, the new
`transfer` also times out after 20 min (`PxeChannel.kt:143`), retires the new host and shows STUCK again, with "tap Retry"
as the only advice — a loop until the process is killed, two `Dispatchers.Default` threads blocked meanwhile. Liveness
only: nothing is sent. Worth a wording ("restart the app") or a `try_lock` with a clear error when the prover is busy.

### N5 — P3 — PLAUSIBLE — Coverage and messaging gaps around the fix

- No test on either platform exercises the handler-side guard (`alive()` / `isClosed`) that is the actual OT-01 close;
  no host test spans `sendLabelled` → `submitting(label)` → the `txSubmitting` event shape consumed by the apps.
- The STUCK text can be replaced: on Android `watchHost` writes "the PXE host was stopped" for the retired host's CLOSED
  event — it loses only because `Main.immediate` resumes it undispatched inside `retireHost` (a dispatching Main, or a
  test dispatcher, would make it win); any later `refresh()` on the failed session writes "the wallet is not open"
  (`WalletSession.kt:322`) over it. The activity row itself carries "transfer: no answer … after 20 min", not the advice.
- iOS `AppContainer.swift:125` fails open when `started.channel` is nil (unreachable today).

## Pre-existing, unchanged by these commits (for completeness, not re-rated)

- A cancelled transaction whose proof is still in Rust, then a lock/auto-lock: `close()` cancels `windDown`, whose
  `finally { ProofGate.reset() }` (`Transactions.kt:214`; [`.swift:190`]) reopens the gate while the host is still alive
  for the PXE's `close` grace (`WalletSession.kt:277`; 15 s on iOS). A proof finishing in that window passes `alive()`
  and is released; the page may send a transaction shown "Cancelled". Seconds-wide, needs the proof to end exactly then.
- A timeout landing between `release()` and `txSubmitting` still shows Failed for a sent transaction (F13 residual).
- `txSubmitting` is told, not acknowledged, before the node call (OT-02's durable-ack recommendation not taken).

## What stays true

Nothing in the four commits lets a transaction be sent twice or behind a failure on the paths they target: the old
proof is refused by the cancel, then by the stopped host, then (Android) by the cancelled scope; a FAILED session cannot
reopen the gate; the dApp hash goes to the row its id names and never to a wallet row. The wallet still locks after a
retire (`isSending` is false for a FAILED session; `proving` is nil). The residuals above are all either rare
(20-minute unanswered call) or window-sized, and N1 is a denial, not a send.
