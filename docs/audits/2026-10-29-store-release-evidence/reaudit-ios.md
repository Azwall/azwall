<!-- Working notes of one area of the 2026-10-29 re-audit. The consolidated, verified report is ../2026-10-29-store-release-reaudit.md; where the two differ, it prevails. -->

# iOS re-audit of the fixes since `ef3ccb5` (2026-10-29)

Read-only review on Linux of `5c08645`, `b0eb710`, `d70880c`, `b38d2f6`, `341eac5`, `3ee6e59`, `96ff7bb`, `9813055`, `758da46`
(iOS parts). Nothing compiled or run here; "CONFIRMED" = traced end to end in the current code, "PLAUSIBLE" = depends on
platform behaviour (Xcode, Keychain) not exercised here. Paths are relative to the repository root.

## Verdicts on the audited findings

| # | Verdict | One line |
|---|---|---|
| F2 | **closed** (manifest) / **open P3** (encryption key) | Valid manifest with the right categories and reasons; `ITSAppUsesNonExemptEncryption` still undeclared, left as a manual step in `docs/release.md:94-96` |
| F4 | **closed**, with two new holes it opens (N2, N3) | `lock()` forgets and shows `.locked` first; `close` bounded to 15 s |
| F5 | **closed** (silent loss) / passcode check closed with a UX residual | Marker + `VaultLostView` + `startOver`; `has()` still treats a locked Keychain as present |
| F10 | **not closed** (PLAUSIBLE) | The gate lives in a script phase that declares outputs and no inputs: Xcode skips it once `assets/azwall-config.json` exists, so an archive after a Debug build never runs `--public-only` |
| F12 | **closed** | Browser attachment keyed on the session's identity |
| F13 | **closed** for the suspended proof; narrow residual; and it causes N1 (P2) | A failure before `release()` shuts the gate; nothing reopens it for dApp proofs |
| F14 | **closed** (reading; the Mac run is the evidence) | `d70880c` |
| F20 | **closed** | Buttons disabled 500 ms per request, panel recreated per request id |
| iOS P3: switcher cover over sheets (I07) | **closed** | `PrivacyShield` UIWindow at `.alert + 1`, hidden on every return to `.active` |
| iOS P3: background task expiration (I08) | **closed** | `BackgroundTask` ends the task from the expiration handler |
| iOS P3: passcode before create (I09) | **closed** (after the 24 words, not on the welcome screen) | `createWallet` guard |
| iOS P3: welcome privacy link (I10) | **closed** | `OnboardingView.swift:299` |
| iOS P3: `wipe()` ends in onboarding on failure (I12) | **closed** | Stays `.locked`, error shown; WebKit data removal still fire-and-forget (unchanged) |
| iOS P3: BundleServers never stopped, PXE origin reachable from a tab (I11) | **unchanged** | Not addressed (fingerprint only, as before) |
| iOS P3: DiagnosticLog bare hex/base64 | **closed** | `DiagnosticLog.swift:95-97`, tested |
| iOS P3: deployment target of the Rust core | **closed** | `scripts/build-ios.sh:17` = `project.yml:7` = `build-bb-ios.sh:22` (17.0) |
| F11 iOS part (prover hash) | **closed** as a staleness check | Runs every build (no outputs declared), compares the two embedded slices to `scripts/bb-libs.sha256`; the recorded hash is whatever `build-bb-ios.sh` last wrote (provenance comes from the commit pin, not from reproducibility) |

### F2 — privacy manifest

`ios/Azwall/PrivacyInfo.xcprivacy`: well-formed plist (comment between DOCTYPE and root is legal), `NSPrivacyTracking` false,
empty `NSPrivacyTrackingDomains` and `NSPrivacyCollectedDataTypes`, `NSPrivacyAccessedAPICategoryUserDefaults` → `CA92.1`
(the app's own defaults: `AppContainer.swift:170-172,188-191`, `@AppStorage`), `NSPrivacyAccessedAPICategoryFileTimestamp`
→ `C617.1` (files in the app container: CRS, sealed stores, `WalletStore`). It sits in the `Azwall` source folder, which
XcodeGen adds to Copy Bundle Resources (the file must end up at the app bundle root: check the archive once). No
third-party SwiftPM package is linked (`ios/*/Package.swift`: local targets only), so no SDK manifest is needed.
`grep` of `ios/`, `scripts/`, `rust/` finds no `systemUptime`, `mach_absolute_time`, `statfs`, `volumeAvailableCapacity`,
`activeInputModes`. Rust's `Instant::now` (`rust/azwall-prover/src/engine.rs:88`, `bindings/mod.rs:104-120`) is
`clock_gettime(CLOCK_UPTIME_RAW)` on Apple targets, which is not on Apple's symbol list; modern libc++'s `steady_clock`
likewise. The manifest comment and `docs/release.md:92-93` say `nm -u` was run on the built binaries, which is the right
check; it cannot be repeated here.

Open: `ITSAppUsesNonExemptEncryption` is absent from `ios/project.yml` (`info.properties` at :81-94). `docs/release.md:94-96`
turns it into a manual step ("answer it, then record the answer"). Not a rejection, a stop at every upload until recorded;
the audit's F2 fix asked for the key. P3, as before.

### F4 — lock at once

`AppModel.swift:176-182`: `rejectAll()`, `vaultState.forget()`, `phase = .locked`, then `await closing?.close()`.
`WalletSession.close()` (`:217-224`): `closed = true`, `pxe = nil`, event loop cancelled, `client.close()` through
`PxeChannel` whose `defaultTimeout("close")` is now 15 s (`PxeChannel.swift:46-49`), then `stop()` regardless.
`LifecycleTests.lockingDoesNotWaitForThePageToAnswer` pins the ordering with a `close` that sleeps an hour (it bypasses
`PxeChannel`, so the 15 s bound is only asserted as a constant by `closingIsBoundedToSeconds`; the `!model.hasRecoveryPhrase`
assertion is vacuous, the vault has no phrase). `switchNetwork`/`restart` keep "close then start" but are now bounded.

Questions asked:
- A dApp approval answered during the 15 s: `rejectAll()` runs first and `MainView` is gone, so nothing pending can be
  answered. A request the host raises *after* `rejectAll()` is another matter: N3.
- `close()` racing `start()`: `restart()` and `switchNetwork()` await `close()` fully, no overlap. `lock()` followed by an
  early `unlock()` does overlap (N2): two `PxeHost`s on the same bundle. They share one `BundleServer`
  (`PxeHost.swift:65-75` reuses the listening one; `stop()` at `:53-61` never stops it), so the old host cannot stop the
  new one; the PXE stores on iOS are in memory (`native_store.ts:71-73`, ephemeral kv-stores) with sealed copies in
  files written atomically (`PxeStoreFiles.swift:20-23`), so two pages racing on the same `<name>.sealed` leave a consistent
  older or newer snapshot, never a torn one; the cost is a re-sync.

### F5 — vault erased by iOS

- Detection: `boot()` (`AppModel.swift:80`) `exists ? .locked : walletCreated ? .vaultLost : .onboarding`. The marker is set
  after `vault.create` succeeded (`:94`) and cleared after a successful `wipe()`/`startOver()` (`:193,215`).
- Reinstall: `forgetEarlierInstall` (`AppContainer.swift:153-165`) wipes a leftover Keychain vault when neither the
  `azwall.installed` marker nor the data directory exists; `azwall.walletCreated` lives in the same UserDefaults, gone with
  the app, so the result is `.onboarding`, consistent. iCloud restore to a new device: defaults restored (both markers
  true), `wallet/` excluded from backup, Keychain `ThisDeviceOnly` absent → no wipe, `.vaultLost` whose text names that
  case. Restore to the same device: Keychain restored → `.locked`.
- Locked Keychain: `KeychainVaultSlots.has()` (`Keychain.swift:21-29`) returns true on `errSecInteractionNotAllowed`, which
  is what a `.userPresence` item answers to a no-interaction query, and what any data-protection item answers while the
  device is locked; a foreground launch cannot happen before first unlock after boot. So the new `.vaultLost` fallback is
  not reached by a merely locked vault. The only way a present vault reads as absent is a Keychain status other than
  success/interaction-not-allowed/not-found (e.g. `errSecNotAvailable`), and then "Start over" deletes it: N4.
- Restore flow: `VaultLostView` → confirmation alert → `startOver()` → `vaultState.erase { deps.wipe() }` (Keychain delete
  tolerates not-found, `azwall.network` removed, WebKit stores cleared in the background, `wallet/` removed) → marker cleared
  → `.onboarding`. The button says "Restore with my recovery phrase" but lands on the welcome screen (Create / Restore):
  one more tap than promised, and "Create a new wallet" is offered alongside. Leftovers: the store directory is removed, so
  no stale history or sealed store reaches the new wallet; the diagnostics log file path is inside the removed directory
  (recreated by the next write, as for `wipe()` today).
- Passcode: `deviceSecure` = `LAContext().canEvaluatePolicy(.deviceOwnerAuthentication)` (`AppContainer.swift:193`), the
  policy that is false with no passcode; message "Set a passcode in your iPhone's settings first…" (`AppModel.swift:28`).
  Checked in `createWallet` (`:91`), i.e. after the 24 words were shown and verified, not on the welcome screen as the audit
  suggested; actionable, and the onboarding state survives a trip to Settings unless iOS kills the app. UX residual only.
- Wipe failure: `wipe()` (`:203-217`) stays `.locked` and shows the error; `LifecycleTests.aRemovalThatFailsSaysSoAndStaysLocked`.
  Note the partial case: `vault.wipe()` succeeded and `removeItem(storeDirectory)` threw → `.locked` with a vault that is
  gone ("no vault" on Unlock); the next launch shows `.vaultLost` with the "iOS erased them" text, which is wrong for that
  case, and Start over retries the removal. Recovers, misleads. P3 wording.
- Passcode removed while the app is alive in `.locked`: Unlock answers "no vault" forever; `boot()` runs once, so
  `.vaultLost` only appears after a relaunch. P3.

### F10 — Release build refuses `.env` endpoints — NOT CLOSED (PLAUSIBLE)

`ios/project.yml:68-80`: the `--public-only` flag is set when `$CONFIGURATION = Release` (correct for Product > Archive, and
the `[ … ] && …` list is safe under `set -e`). But the phase declares `outputFiles` (`assets/azwall-config.json`,
`assets/pxe`) and no input files, with XcodeGen's default `basedOnDependencyAnalysis: true`. Xcode runs such a phase only
while an output is missing: that is the standard way to make a script run once, and it is what keeps `pnpm build` from
running on every build here. Scenario: the developer builds Debug (the phase generates `assets/azwall-config.json` from a
keyed `.env`), then archives: the outputs exist, the phase is skipped, the gate never runs, the keyed config ships in the
IPA, exactly F10. `docs/release.md:84-87` relies on the gate. The Android gate is a Gradle task dependency and always runs.
Fix: `basedOnDependencyAnalysis: false` on that phase (or drop `outputFiles` and skip `pnpm build` by hand inside the
script), plus a check phase after Copy Bundle Resources that fails a Release build whose bundled config carries any
non-registry endpoint. Verify on the Mac: Debug build with a keyed `.env`, then Archive, and read the archive's
`azwall-config.json`.

### F12 — browser follows the session a Retry starts

`MainView.swift:59` `.task(id: ObjectIdentifier(session))`. `restart()` holds the old session in `current` until the new
one is open (`AppModel.swift:142-147`), so both are alive when SwiftUI compares the ids: no address reuse. A UUID on
`WalletSession` would not depend on that. Closed.

### F13 — a failed transaction keeps the proof gate shut

`Transactions.swift:162-171`: an error with `!gate.released` and not cancelled → `gate.cancel()` and the gate is left shut.
Suspended-proof scenario: on resume the 20 min sleep fires at once while the Rust proof still needs seconds, so
`released` is false, the gate is cancelled, the late `release()` throws, nothing is sent, "Failed" is true. Closed for that.
Residual: `followSubmission` (`WalletSession.swift:305-316`) still matches `.proving` items only, so a timeout landing in
the few milliseconds between `release()` and the page's `txSubmitting` still yields a sent transaction shown Failed (needs
the suspension to start inside that window). Narrow; mention only. The real cost of the change is N1.

### F14 — tests compile

`d70880c`: `waitForHeldWrite()` is a synchronous wrapper called inside `Task.detached` (Swift 6 accepts it), `try await
whileSending?()`. The new test code reads as Swift 6-clean (MainActor suites, `@unchecked Sendable` fakes, no force unwraps).
The Mac run is the evidence.

### F20 — tap delay

`ApprovalPanel.swift:155,166-170`: both buttons disabled until `armedFor == request.id`, set 500 ms after the panel's
`.task(id: request.id)` starts; `MainView.swift:39` gives the panel `.id(request.id)`, so each request gets a fresh panel
(state reset, details sheet dismissed), and a request cannot be answered through a sheet opened for the previous one. The
cancelled task's late `armedFor = <old id>` can never equal the new id. Not bypassable from the panel; a user waits half a
second. The details sheet's buttons carry no delay, but the sheet is reached by a deliberate tap and is tied to one request.

### Switcher cover, background task, Swift 6

`PrivacyShield` (`AzwallApp.swift:60-79`): a `UIWindow` at `.alert + 1` created on any phase other than `.active`, hidden
and released on `.active` (`:45`); `.onChange(of: scenePhase)` fires on each transition, including the `.inactive` round
trip of a Face ID prompt, whose system UI is hosted outside the app's windows and unaffected by the cover. Single scene
(no `UIApplicationSupportsMultipleScenes`). No stuck-cover path found. `BackgroundTask` (`:81-95`): the expiration handler
runs on the main thread, `assumeIsolated` is correct whether or not the SDK already marks the closure `@MainActor`; a
MainActor class is Sendable so the weak capture is fine; `end()` is idempotent. No new force unwraps in the changed code.

## NEW findings

### N1 — P2 — CONFIRMED — Every dApp transaction is refused after a wallet transaction fails before its proof

`Transactions.swift:166-168` leaves `ProofGate.shared` cancelled whenever the wallet's own send throws before
`release()`. That is not only the timeout the comment has in mind: `token.ts:116-129` runs `checkRecipient` and the private
simulation and fee estimation before `createChonkProof` (`prover.ts:34`), so an insufficient balance, a bad recipient, an
unreachable node or any PXE error is a failure "before the proof was handed over". Nothing reopens the gate for dApps:
`reset()` is called only in `proveAndSend` (`:155,156,170,181`), while the dApp path only calls `check()` and `release()`
(`AppContainer.swift:119,122`). The gate is a process-wide singleton, so this survives lock/unlock and network switches.
Scenario: a transfer fails in simulation ("not enough private balance"); from then on every dApp request approved with
Face ID ends in `dappTx failed: "You cancelled the transaction; nothing was sent."` until the user runs another wallet
transaction. `SubmissionTests.aTransactionThatFailedBeforeItsProofLeftIsNeverSentLater` enshrines the behaviour and never
tries a dApp proof afterwards. Same change on Android (`3ee6e59`, `Transactions.kt:189-192`, reset only in `Transactions.kt`,
`AndroidPxeLauncher.kt:47` checks). Fix: hold the gate shut only when the page's call may still be running (the
`PxeChannel` "no answer from the wallet engine" expiry, `PxeChannel.swift:82-85`), `reset()` on every other error; or key
the gate to the item it protects (F18) so a dApp proof never meets a wallet transaction's cancel. Test: wallet send fails
with a plain `PxeError`, then a dApp `prove` must pass.

### N2 — P3 — CONFIRMED — Unlock during the lock's close window: two hosts, and a wipe that deletes the vault under a live session

`lock()` is not under `action`, so `busy` stays false while `close()` waits up to 15 s; `LockView` enables Unlock on
`!model.busy` (`OnboardingView.swift:333`). `unlock()` then starts a second `PxeHost` for the same bundle while the first is
still closing (harmless beyond a store re-sync, see F4 above). Worse in `wipe()` (`AppModel.swift:203-217`): Remove →
Face ID → `lock()` shows the lock screen → the user taps Unlock within the window → a new session opens → `wipe()` resumes,
`erase` deletes the Keychain and `wallet/` → `.onboarding`. The new session is never closed: its page keeps the keys and
goes on saving sealed stores under the same file names a wallet created next would use (the new page sets the unreadable
copy aside and starts empty, then gets clobbered again at the orphan's next save, until the process dies). No funds at
risk; keys linger in a page nobody owns and the new wallet re-syncs repeatedly. Fix: keep `busy = true` for the whole of
`wipe()`, and have `unlock()`/`start()` await a close still in flight (keep the closing task on the model).

### N3 — P3 — CONFIRMED — A dApp request raised while the host closes shows up in the next session

`rejectAll()` runs before `close()`; a gateway mid-simulation at that moment asks `approve` seconds later, and
`DappApprovals.ask` (`Dapps.swift:78-85`) queues it whatever the phase. `PxeHost.stop()` → `PxeChannel.close()`
(`PxeChannel.swift:104-113`) fails app→page calls only; the handler task awaiting `ask` is never resumed, so the request
stays in `pending` until answered. The next `MainView` (after any unlock, minutes or hours later) shows it as
`pending.first` (`MainView.swift:35`), with its origin, "Approve and prove" and a Face ID prompt; `selectPage(request.tab)`
finds no tab. Approving reaches a dead page (nothing is sent: the old page is unloaded at the latest 15 s after the lock,
before any proof could finish), so the effect is a phantom request answered for nothing, which a user can mistake for a
request of the page they just opened. Same hole in `restart()`/`switchNetwork()` (pre-existing, now more visible). Fix:
`rejectAll()` once `close()` returned (in `lock`, `restart`, `switchNetwork`) or in `start()` before `.unlocked`; better,
bind each request to the launch that asked it.

### N4 — P3 — PLAUSIBLE — "Start over" deletes a vault the Keychain merely failed to report

`boot()` maps any `has()` false to `.vaultLost` when the marker is set. `has()` is false for every status other than
success/`errSecInteractionNotAllowed` (`Keychain.swift:28`), including transient ones such as `errSecNotAvailable`. Before
the fix a transient false led to onboarding, where `vault.create` re-checked `exists()` and refused ("a vault already
exists", `Vault.swift:57`); now it leads to a screen whose only action is `startOver()` → `SecItemDelete` of a vault that
may be back. The confirmation alert says the right thing, which is the mitigation. No foreground path to such a status
is known (apps do not run before first unlock), hence PLAUSIBLE. Hardening: `startOver()` re-checks `deps.vault.exists()`
and goes `.locked` if the vault is there.

### N5 — P3 — PLAUSIBLE — iOS auto-lock ignores a dApp transaction in flight (parity with `96ff7bb`)

Android's `isSending` now covers a dApp item being proven; iOS `onForeground`/`backgroundLock` still test
`session.proving == nil` only (`AppModel.swift:161,171`), and `close()` cancels the event loop before `client.close()`
(`WalletSession.swift:221-222`), so a `txSubmitting` the page emits during the close window is dropped and the item ends
"interrupted before the proof was sent" though it may be mined (the audit's F15, on iOS). Needs a > 5 min absence with
the page between `release()` and `txSubmitting` at suspension time: narrow. Port the `isSending` guard and let the event
loop run until `stop()`.

## Tests

- `LifecycleTests`: meaningful for the lock ordering, the vault-lost phases, the passcode guard, the marker and the failed
  removal (see the two weaknesses under F4). `LifecycleDeps.approvals` returns a new `DappApprovals` on each access and
  `storeDirectory` a new temp path on each access: fine for these tests, surprising for the next ones.
- `SubmissionTests`: the F7 test now asserts a followed send; the F13 test asserts the refused late proof and that the next
  wallet send works, but not that a dApp proof still passes (N1).
- `FeatureTests.aDappTransactionKeepsTheHashToldBeforeSending`: exercises `DappTx.applied` and `confirm` through the fake;
  meaningful. `SiteNameTests`: fine.
- `DiagnosticLogTests`: covers the new cuts and a plain sentence.

## Anything else

- `.vaultLost` wording and the "Restore with my recovery phrase" button landing on the welcome screen: see F5.
- Nothing new sends without consent, leaks a secret or loses the wallet beyond N2's orphan page; nothing new on the
  App Store side beyond the undeclared encryption key and F10's gate not running.
