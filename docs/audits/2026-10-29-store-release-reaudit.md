# Store-release re-audit — 2026-10-29

Reviewed revision: `01267c0` (working tree clean), fifteen commits after the audited `ef3ccb5`. Evidence and the
detailed per-area notes: [2026-10-29-store-release-evidence](2026-10-29-store-release-evidence/). The audit this one
re-checks: [2026-10-28-store-release-audit.md](2026-10-28-store-release-audit.md).

**Decision: not yet.** Both P1s of yesterday are closed and eight of the eleven P2s with them, but two new P2s came with the
fixes and one P2 is not closed:

- **R1 (both platforms).** The fix for F13 leaves the proof gate shut after *any* wallet send that fails before its proof
  (a wrong recipient, "balance too low", a node error), and nothing reopens it for dApp transactions: every dApp
  transaction the user then approves and confirms with biometrics is refused as "You cancelled the transaction; nothing
  was sent" until the wallet's next own transaction. Reproduced with a probe test on the Android fakes; same code on iOS.
- **R2 (iOS).** The `--public-only` gate added for F10 is in the right build phase, but that phase does not stop when the
  gate refuses, and Xcode skips the phase altogether once its outputs exist (reproduced on the Mac): a stale
  `assets/azwall-config.json` from the last Debug build, API key included, ships in the archive.
- **F10** is therefore still open; F9 is partially closed (a site's own artifact names bypass the new sanitiser).

Everything else holds up well: the shipped-artifact defence (F1) is wired through every reader; the hash told before
sending is kept through every failure path (F7, F8); a missing or replaced Keystore key leads to the restore screen
(F6); iOS locks at once (F4), says when iOS erased the vault (F5), has its privacy manifest (F2) and compiles with
its tests (F14); the prover's commit is pinned (F11); the RustSec advisories are gone (F17).

## Scope and method

Each of the 23 findings of 2026-10-28 was re-checked against the current code by the consolidating reviewer, and four
area reviews (dApp gateway and transaction path; Android; iOS; Rust, build and supply chain) were run on the same
revision with the brief "does the fix close the finding, can it be bypassed, what did it break". Their notes are
archived. Status words: **closed** (the scenario can no longer happen), **partial** (narrowed, a path remains),
**open** (unchanged or the fix does not hold), **regressed** (the fix broke something else; the new finding is named).
New findings carry CONFIRMED (traced end to end, or reproduced) or PLAUSIBLE (a premise not exercised here).

Checks run on `01267c0`:

| Where | Check | Result |
|---|---|---|
| Linux | `scripts/test-all.sh` | Rust 73 passed, 1 live test ignored; PXE 14 shared + 97 host; Android JVM core 140, app 31 (forced re-run), 0 failures |
| Linux | `cargo audit`, `cargo clippy --all-targets -D warnings`, `check-limits.py`, `check-swallowed.py` | 0 vulnerabilities (one allowed unmaintained warning behind the optional `bindgen` feature); clippy clean; limits pass |
| Linux | `./gradlew :app:lintRelease` | 0 errors, 17 warnings (unchanged set) |
| Linux | `pnpm --dir pxe/v6 test` | 25 files, 97 tests pass |
| Mac (Xcode 27.1, Swift 6.4) | `plutil -lint ios/Azwall/PrivacyInfo.xcprivacy` | OK |
| Mac | `scripts/build-ios.sh`, `swift test` in AzwallKit | XCFramework rebuilt; 121 tests in 30 suites pass (+2 platform tests) |
| Mac | `xcodebuild` Debug for the iPhone simulator, install, launch | BUILD SUCCEEDED, no "built for newer iOS" warning any more, `PrivacyInfo.xcprivacy` inside the built app, the app launches to the welcome screen (now with its privacy-policy link; `ios-simulator-launch.png`) |

| Mac | second `xcodebuild` on the same tree | the "Resolve network config" phase did not run (outputs declared, no inputs): R2 |

Not done: physical devices, a live transaction, a Release archive on the Mac, Play Console and App Store Connect.

## Yesterday's findings

| # | Sev | Status | Evidence and residue |
|---|---|---|---|
| F1 | P1 | **closed** | `pxe/host/src/shipped.ts`; `wallet.ts` `artifactAt` prefers the shipped artifact by class id, `registerContract`/`registerContractClass` replace a site's artifact of a shipped class before storing; `token.ts` and `gateway.ts:#noteRegistration/#noteClass` follow. Residual P3 (PLAUSIBLE): a token of a class the wallet does not ship is still displayed through the site's artifact (its labels and storage layout), marked as the site's. |
| F2 | P1 | **closed** (manifest) | `ios/Azwall/PrivacyInfo.xcprivacy`: valid, UserDefaults `CA92.1`, FileTimestamp `C617.1`, no other required-reason API found in `ios/` or the Rust core; present in the built app. `ITSAppUsesNonExemptEncryption` is still absent from `project.yml` (left as a manual step in `docs/release.md`). |
| F3 | P1 | **documented** | `docs/release.md` now states the organisation requirement; not verifiable from the repository. |
| F4 | P2 | **closed** | `AppModel.lock()` rejects approvals, forgets the vault and shows `.locked` before awaiting `close`; `close` bounded to 15 s (`PxeChannel.swift`). Opens R6 and R7. |
| F5 | P2 | **closed** | `walletCreated` marker set after `vault.create`, cleared after a successful wipe or start-over; `boot()` shows `VaultLostView` when the marker is set and the vault is gone; reinstall wipe and restore-to-another-device traced consistent; a merely locked Keychain still reads as present. Passcode checked before creating (`NoPasscode`), after the 24 words were shown (UX residual). |
| F6 | P2 | **closed** | `keyToOpen(existing())` on `open()` in both boxes, `AEADBadTagException` → `VaultKeyLost`; only `seal()` may generate; `.previous` fallback intact; lock screen offers the restore path after a non-decline failure, with a confirmation. Opens R4. |
| F7 | P2 | **closed** | `submit.ts` `Told` attaches the hash told before sending to a later failure; `gateway.ts:#send` reports such a failure as `sent`; `Dapps.kt`/`Dapps.swift` turn `failed`-with-hash into PENDING and follow it; the wallet's own path does the same (`Transactions.kt`, `.swift`). Tests meaningful. |
| F8 | P2 | **closed** | `describe.ts` reads `receipt.txHash`; a stored hash is never overwritten; `sent` with a hash is PENDING, then confirmed from a receipt. |
| F9 | P2 | **partial** | Closed: registrable domain first with the host under it (`SiteName` on both apps; shared-hosting suffixes such as `github.io` collapse, see R3), site strings through `oneLine` (controls, bidi and line breaks out, capped), unfollowed tokens marked on balance lines. Open: artifact-derived names of a site's own class and the capability lines bypass `oneLine` (R3). |
| F10 | P2 | **open** | The gate is in the "Resolve network config" phase for `Release`, but the phase does not fail when the gate refuses (R2). |
| F11 | P2 | **mostly closed** | `scripts/bb-commits` pins the full commit and `bb-sources.sh` refuses any other HEAD, on a fresh clone and on an existing checkout; `scripts/bb-libs.sha256` records each library and `build-android.sh` and the Xcode phase refuse one that differs. Residue (P3): Gradle itself checks nothing (a stale `jniLibs` copy ships; `build-android.sh` deletes the staged prover before the check, so a failed check leaves a build that still bundles without a prover); the iOS phase accepts a partial record; the record is consistency rather than provenance (both build scripts rewrite it); libdeflate inside the checkout is still fetched by tag; a dirty tree at the right commit builds. |
| F12 | P2 | **closed** | `MainView` attaches the browser keyed on the session's identity. |
| F13 | P2 | **closed, regressed** | A failure before the proof was handed over shuts the gate, so a late proof is refused rather than sent behind a failure. But nothing reopens it for dApp proofs: R1. Narrow residual: a timeout landing between `release()` and `txSubmitting` still shows Failed for a sent transaction. |
| F14 | P2 | **closed** | `d70880c`; 121 Swift tests pass on the Mac. |
| F15 | P3 | **partial** | Background auto-lock and foreground check now wait for a dApp transaction (Android). `lock()`, `switchNetwork()`, `restart()`, the back gesture and `close()` dropping `pxeEvents` before the PXE's `close` are unchanged; iOS has no `isSending` at all (R5). |
| F16 | P3 | **closed for the wallet's own proof; regressed for dApps** | The background lock now waits for `sending` then locks, instead of giving up; the wait is unbounded (R5). |
| F17 | P3 | **closed** | `rustls-webpki 0.103.16`, `webpki-roots 1.0.11`; `cargo audit` clean. |
| F18 | P3 | **partial** | `profileTx` is forced to `skipProofGeneration: true` (`policy.ts`). The gate is still one global flag, not bound to its item (see R1's fix). |
| F19 | P3 | **open** | Unchanged. |
| F20 | P3 | **closed** | Reject/Approve disabled for 500 ms after each new request on both apps; the details sheet's buttons need a deliberate tap first. |
| F21 | P3 | **open** | Unchanged. |
| F22 | P3 | **open** | Unchanged, not documented either. |
| F23 | P3 | **mixed** | Closed: fee line says who pays (`effects.ts` `payerOf`); switcher cover over sheets (`PrivacyShield` window, hidden on every `.active`, Face ID unaffected); background task with an expiration handler; welcome privacy link; `wipe()` stays locked on failure; diagnostic log cuts bare hex and base64 (the log stays readable; residual gaps: URL userinfo, grouped hex, URL-safe base64, recovery words, none reachable by a current log path); `IPHONEOS_DEPLOYMENT_TARGET=17.0`; `uniffi`'s generator behind a `bindgen` feature (122 crates instead of 177); `redirects(0)`; `http` only to this machine (R9). Unchanged: node strings unbounded and RPC `id` unchecked; FFI strings not zeroised; 1 GiB request cap; CRS nits; `findByName` gate wiring; BundleServers never stopped and the PXE origin reachable from a tab on iOS; WebKit data removal fire-and-forget on wipe; `submit.ts` believing a DROPPED read 2–6 s after a lost answer; Confirmed-at-PROPOSED never re-read; the gas-cache fast path skipping implicit authorizations; an explicit site `createAuthWit` refused during another account's send; `test-all.sh` and `release.md` without an audit step. |

## New findings

### R1 — P2 — CONFIRMED — After any failed wallet send, every dApp transaction is refused as "cancelled"

Locations: `android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:184-195` (`proveAndSend`: on an error
with `!cancelled && !ProofGate.released`, `ProofGate.cancel()` then rethrow); `ios/AzwallKit/Sources/AzwallKit/Transactions.swift:161-172`
(same); `ProofGate.reset()` is called only from `proveAndSend` (`Transactions.kt:179,193,208`; `Transactions.swift:155,156,170,181`);
every proof, a dApp's included, passes `ProofGate.check()` first (`android/app/.../AndroidPxeLauncher.kt:47`,
`ios/Azwall/AppContainer.swift:119`); `DappApprovals.answer`'s `settle()` only waits for a cancelled wallet proof.

The guard was meant for a call that timed out while the page was frozen (F13), but it fires on every error thrown before
the proof: `checkRecipient` and the simulation run before `prove` (`token.ts`), so a wrong recipient, "balance too low" or
a node error leaves `cancelled = true`. The next dApp transaction the user approves and confirms with biometrics is
refused at `check()`, the gateway reports `dappTx failed` "You cancelled the transaction; nothing was sent", the site is
told the same, and the activity shows Failed — until the user sends a wallet transaction of their own (which resets the
gate) or restarts the process. The Android area review reproduced it on the fakes (its probe 5); the new tests retry
through the wallet path, which resets the gate, so they miss it.

Fix: keep the gate shut only when the failure is the channel's timeout (the call may still be running), reset it on any
other error; better, bind the gate to the transaction it protects (a token per `prove` request, F18) so a dApp proof is
never judged by the wallet's state. Test: a wallet send failing with a plain PXE error, then a dApp `prove` must pass.

### R2 — P2 — CONFIRMED — The iOS `--public-only` gate cannot stop an archive, and does not even run

Location: `ios/project.yml:68-80`, phase "Resolve network config". The phase has no `set -e`; after
`cargo run … azwall-config … --public-only` refuses (`azwall-config.rs` fails at the check, before writing), the script
goes on to `pnpm install && pnpm build`, whose exit status is the phase's: the phase passes, and the
`assets/azwall-config.json` left by the previous Debug build, keyed URL included, is bundled into the archive. The other
phases of the same file end their checks with `|| { …; exit 1; }`; this one does not. Worse, the phase declares
`outputFiles` and no `inputFiles` with dependency analysis on, so Xcode skips it once the outputs exist: on a second
build on the Mac, only the three check phases ran and `assets/azwall-config.json` kept its timestamp
(`mac-phase-skip.log` in the evidence). After any build, an archive therefore bundles whatever config the last run that
executed the phase left, and the gate never runs.

Fix: `set -euo pipefail` at the top of the phase, delete `assets/azwall-config.json` before generating it, set
`basedOnDependencyAnalysis: false` on the phase (or drop `outputFiles`), and add a Release-only check phase that fails if
the bundled config holds a non-registry host. Verify with an archive on the Mac after a Debug build with a keyed `.env`.

### R3 — P3 — CONFIRMED — The approval sanitiser misses artifact-derived names and capability lines

Locations: `pxe/host/src/dapp/calls.ts:137-146` (`fn.name`, `p.name`, `artifact.name` of a site-registered class reach
`summary()` unsanitised; `summary` caps the arguments only), `gateway.ts` capability summary (`String(c.type)`),
`grants.ts` pattern and name lines. A site's own contract can name a function `claim\n• You receive 1,000 USDC`; the
area review's probe rendered it as a forged bullet followed by `(as this site names it)` on a later line. Run every
artifact-derived and capability string through `oneLine` too. Also from F9: `SiteName.domainOf` collapses shared-hosting
suffixes (`evil.github.io` and `azwall.github.io` both read **github.io**); a small multi-tenant suffix list, or the
public suffix list, would fix it; `[::1]` shows as `[` (debug only).

### R4 — P3 — CONFIRMED — Android: the "can't unlock" flag outlives the wallet it was set for

Location: `android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt` (`_unlockFailed` set on a non-decline failure,
cleared only by a successful unlock; never by `startOver()`, `wipe()` or `createWallet()`). After a start-over, the next
wallet's first lock screen already offers "Can't unlock? Restore with my recovery phrase", whose KeyLost screen says
Android erased the key and wipes without authentication (after a confirmation). The same text shows after one transient
Keystore error. Clear the flag on start-over, wipe and create; word the screen for "could not unlock" rather than "the
key was erased" when the cause is unknown.

### R5 — P3 — CONFIRMED — The new auto-lock wait is unbounded and sees one account only (Android); iOS has none

Locations: `AppViewModel.kt:onBackground` (`session.sending.first { !it }` then `lock()`), `WalletSession.kt:isSending`
(the wallet's `proving`, or a DAPP row PROVING in the *selected account's* activity). (a) A PXE host that dies during
a dApp proof, or a site whose `wait` never returns, leaves the row PROVING: the wallet never locks in the background (the
area review's probe: six hours, still unlocked; `ef3ccb5` locked here). (b) A dApp transaction proving for an account the
user switched away from is invisible: the lock fires mid-proof. Fix: not sending once the session is FAILED, a hashed row
does not block, bound the wait (ten minutes), and read `store.proving()` rather than the selected account's list. iOS
still checks `session.proving == nil` only (`AppModel.swift:161,171`) and `close()` cancels its event loop before the
PXE's `close`, so the iOS side of F15 is unchanged.

### R6 — P3 — CONFIRMED — iOS: Unlock and Remove are live during the 15 s close

Location: `AppModel.swift` `lock()` runs outside `action`, so `busy` is false while the old session closes; `LockView`'s
Unlock starts a second `PxeHost` on the same bundle (shared `BundleServer`, in-memory stores with atomic sealed copies:
a re-sync at worst). In `wipe()` the same tap opens a new session that `erase` then deletes the vault under: onboarding
over an orphan page holding the keys, never closed, clobbering the next wallet's sealed stores until the process dies.
Fix: `busy = true` for the whole `wipe()`; `unlock()`/`start()` await a close in flight.

### R7 — P3 — CONFIRMED — iOS: an approval raised after `rejectAll()` survives into the next unlock

Location: `DappApprovals.ask` queues a request whatever the phase; `PxeChannel.close` never resumes handler-side
continuations. A gateway mid-simulation at lock time asks for approval after `rejectAll()`; the request shows as
`pending.first` in the next `MainView`, asks for Face ID, and nothing can be sent (the page is unloaded). Same in
`restart` and `switchNetwork`. Fix: `rejectAll()` after `close()` returns (or in `start()`), or bind requests to the
launch they came from.

### R8 — P3 — PLAUSIBLE — iOS: any unexpected Keychain status now leads to a screen whose only action deletes

Location: `Keychain.swift:has()` treats success and interaction-not-allowed as present; any other status
(`errSecNotAvailable`, for instance) reads as absent, which `boot()` now maps to `.vaultLost`, whose only action removes
the Keychain items. No foreground path to such a status is known; hardening: `startOver()` re-checks `vault.exists()` and
goes `.locked` if the vault is there after all.

### R9 — P3 — CONFIRMED — `is_http_url` accepts `http://localhost:1@node.example/`

Location: `rust/azwall-core/src/network.rs:240-250` takes the host as the text before the first `:` of the authority;
`http://localhost:1@node.example/`, `http://127.0.0.1:x@node.example` and `http://[::1]:8080@node.example/` pass while a
URL parser sends them to `node.example` in clear. Input is developer-controlled (`networks.json`, `.env`), so this is a
lint weakness; refuse `@` in the authority.

### R10 — P3 — CONFIRMED — Privacy policy claims a setting the apps do not have

Location: `docs/privacy.md:48`: "You can replace the default Aztec node and Ethereum endpoint with your own". No such
setting exists in either app; endpoints come from `.env` at build time, which store builds refuse. Remove the sentence or
add the setting. The Play Data safety proposal (`store/play/README.md`) is otherwise consistent with the flows and
conservative (addresses and transactions declared as collected and shared with dRPC and the Ethereum endpoints, https
only); consider "Personal info › User IDs" for account addresses as well, say in the node row of the policy that account
addresses reach the node, and in the deletion answer that third-party RPC logs cannot be deleted by the developer.

## Before submitting

1. R1 on both platforms, with the test named above; then re-run the dApp flows (approve after a failed transfer).
2. R2: make the Xcode phase fail loudly and never be skipped, then archive on the Mac after a Debug build with a keyed
   `.env` and inspect the bundled config.
3. R3 and R4 (small), R5–R7 (lifecycle), R10 (one sentence); F3 to confirm with Apple; `ITSAppUsesNonExemptEncryption` in
   `project.yml` once the export-compliance answer is settled.
4. Then the device checklist of 2026-10-28, plus: approve a dApp transaction right after a failed wallet transfer;
   remove the screen lock or passcode with a wallet present; lock during a sync; Retry then connect a dApp; leave the
   app for 25 minutes mid-transfer; tap Unlock during the lock's close on iOS.
