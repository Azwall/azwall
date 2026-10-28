# Store-release audit (App Store and Google Play) — 2026-10-28

Reviewed revision: `ef3ccb5` (working tree clean). Evidence and the detailed per-area notes:
[2026-10-28-store-release-evidence](2026-10-28-store-release-evidence/).

**Decision: do not submit this revision to the App Store or to Google Play.** One P1 lets a dApp make the user approve
a transaction other than the one that runs; one P1 makes every App Store upload fail; thirteen P2 findings cover wallet
lock-out, a double payment, display spoofing of the approval panel, a key leak in an iOS archive and the provenance of
the native prover. None of the P1/P2 findings needs a redesign: each is a bounded change with a regression test.

The questions asked: security flaws, wallet loss, predictable recovery phrases, spending without the user's knowledge,
and any bug that could compromise the wallet. Short answers:

| Question | Answer |
|---|---|
| Is the recovery phrase predictable? | **No.** 256 bits from the OS random generator (`getrandom`), BIP-39 encoded, derivation domain-separated and frozen by test vectors. Verified in `rust/azwall-core/src/mnemonic.rs`; no other code path generates a phrase. Details in "Randomness and key derivation". |
| Can the wallet be lost? | The three persistence races found by the earlier audits (A01, A04, R1) are fixed and covered by passing regression tests on both platforms. Two new lock-out paths remain: Android after the Keystore key disappears (F6), iOS after the passcode is removed or a restore to a new device (F5). Funds stay recoverable from the phrase; the app offers no path and says nothing. |
| Can money leave without informed consent? | **Yes, with a dApp**: a site can plant a crafted artifact for the standard Token class and a later transfer is shown reversed (F1). Three display spoofs compound it (F9). Two findings turn a sent transaction into "Failed" and invite a second payment (F7, F8). The wallet's own transfer path is sound. |
| Store submission | iOS: no privacy manifest (F2, certain rejection), export-compliance key missing, organisation enrolment to confirm (F3). Android: packaging checks from the earlier audits pass; the upload key is not configured in this checkout. |

## Scope and method

Four independent area reviews (dApp gateway and transaction path; Android app; iOS app; Rust core, prover, build and
supply chain) were run against the current source, each told not to repeat the earlier audits' findings unless still
open, and to verify the fixes landed since (`4269719`, `746dcd7`, `68ed766`, `88f866d`, `0b5d1be`, `ef3ccb5`). Their notes are
archived as evidence. Every P1 and P2 below was then re-read in the code by the consolidating reviewer; the status says
what was established:

- **CONFIRMED**: the path was traced end to end in the current code (and unit-tested where stated);
- **PLAUSIBLE**: the premise depends on platform behaviour that could not be exercised here (no device, no Mac).

Checks run on this revision: `scripts/test-all.sh` (Rust 73 passed, 1 live test ignored; PXE 14 shared and 87 host tests;
Android JVM core 134 and app 28; iOS skipped there, no Swift toolchain on the Linux machine), then on a Mac with Xcode
27.1 / Swift 6.4: `swift build` of AzwallKit (clean), `swift test` (**does not compile at `ef3ccb5`**, F14; 111 tests in 28
suites pass once the two test files are fixed), `scripts/build-ios.sh` (Rust core XCFramework rebuilt), an Xcode build of
the app for the iPhone simulator (succeeded) and a launch in the simulator (reaches the welcome screen and stays alive;
`ios-simulator-launch.png` in the evidence), `cargo clippy --all-targets -D warnings` (clean),
`cargo audit` (2 advisories, F17), `pnpm audit --prod` (8 advisories, none in the shipped bundle), `readelf` on the
staged arm64 libraries (PIE, full RELRO, NX, 16 KB alignment, two exports), a git-history scan for committed secrets
(none: the only keyed URLs are test placeholders), `azwall-config --public-only` (passes; `.env` sets no endpoint).

Not done: physical-device runs (Keystore and Keychain behaviour, proving memory, 16 KB devices), a live transaction, an
Xcode build of today's two iOS commits, Play Console and App Store Connect configuration, fuzzing of Barretenberg's
deserialisers. The architecture page still says iOS was never compiled: that line is stale (`ea994b2`, 2026-10-21,
built with Xcode 27.1 and ran in the simulator), but no run on a physical iPhone is evidenced.

## Findings

| # | Sev | Status | Area | Finding |
|---|---|---|---|---|
| F1 | P1 | CONFIRMED | dApp | A site plants a crafted Token artifact; later transfers are shown reversed, with no balance change |
| F2 | P1 | CONFIRMED | iOS store | No `PrivacyInfo.xcprivacy`: every App Store upload is rejected (ITMS-91053) |
| F3 | P1 | PLAUSIBLE | iOS store | Wallet apps must come from an Apple developer account enrolled as an organisation |
| F4 | P2 | CONFIRMED | iOS | Lock waits for the PXE page to answer `close` (up to 20 min) before the wallet is locked |
| F5 | P2 | CONFIRMED | iOS | Vault silently gone after passcode removal or a restore to another device: plain onboarding |
| F6 | P2 | PLAUSIBLE | Android | A missing Keystore key is silently regenerated: vault unreadable, lock screen with no way out |
| F7 | P2 | CONFIRMED | dApp | A dApp transaction the node accepted but whose `wait` fails is shown Failed until the next unlock |
| F8 | P2 | CONFIRMED | dApp | The default `sendTx` result shape hides the hash: row Confirmed on a proposed block, hash erased |
| F9 | P2 | CONFIRMED | dApp UI | Approval panel spoofing: origin cut at the end, site strings with line breaks, unmarked token symbols |
| F10 | P2 | CONFIRMED | iOS build | The iOS build phase has no `--public-only` gate: an archive ships `.env` RPC keys |
| F11 | P2 | CONFIRMED | Supply chain | Barretenberg sources pinned by a mutable git tag; no commit or library hash recorded |
| F12 | P2 | CONFIRMED | iOS | After Retry on the same network, the browser keeps the dead session: dApps silently broken |
| F13 | P2 | PLAUSIBLE | iOS | A call timeout counts suspended time: a transfer resumed after 20 min away is failed, then sent |
| F14 | P2 | CONFIRMED | iOS | The iOS test suite at the audited revision does not compile: today's ports were pushed unbuilt |
| F15 | P3 | CONFIRMED | Android | A dApp transaction in flight is not protected from lock, switch, back or auto-lock |
| F16 | P3 | CONFIRMED | Android | Auto-lock is skipped, not re-armed, when a proof is running when the timer fires |
| F17 | P3 | CONFIRMED | Rust | Two RustSec advisories in the TLS stack of the node probe (`rustls-webpki`, `webpki-roots`) |
| F18 | P3 | CONFIRMED | dApp | `profileTx` runs the native prover without approval and disarms the user's own Cancel |
| F19 | P3 | CONFIRMED | dApp | Unapproved, unbounded contract and sender registrations; class metadata fingerprints the wallet |
| F20 | P3 | CONFIRMED | dApp UI | No activation delay on the panel's buttons; connect and capabilities need no biometric |
| F21 | P3 | PLAUSIBLE | dApp | Implicit authorizations the wallet signs at send time are never listed in the approval |
| F22 | P3 | CONFIRMED | Android | PXE web view and dApp pages share one renderer process (keys in the PXE page's heap) |
| F23 | P3 | various | all | Hardening items listed in "Other findings" |

### F1 — P1 — A crafted artifact of the standard Token class changes how later transfers are shown

Locations: `pxe/host/src/dapp/gateway.ts:292-315` (`registerContract` marks the address only; `registerContractClass`
marks the class); `pxe/host/src/token.ts:35-43` (`registerToken` keeps an artifact already registered for the class);
`pxe/host/src/dapp/calls.ts:128-130` (argument labels from `fn.parameters[i].name`); `pxe/host/src/dapp/effects.ts:73-80`
(balance slots from `storageLayout`); pinned PXE `contract_store.js:85-101` (a registration overwrites the stored artifact
unless the in-memory cache has it).

Aztec's contract class id binds selectors (function name and parameter *types*), bytecode and return types; it does not
bind parameter *names* nor the artifact's `storageLayout`. The area review unit-tested that a Token artifact with
`from`/`to` swapped and bogus storage slots has the same class id as the shipped one. `registerContract` and
`registerContractClass` need no approval (`policy.ts:10`, `grants.ts:142-144`), and both shipped networks start with no
token, so the first artifact written for the Token class is whoever registers it first.

Scenario: a site registers a Token instance with the crafted artifact (ordinary dApp behaviour, no panel). The user later
follows the real token; `registerToken` keeps the crafted artifact. Any site proposes
`transfer_in_public(from: user, to: attacker, 1000)`. The simulation finds no note at the crafted slots, so the panel reads
"No change to the balances of the tokens the wallet knows" and lists
`transfer_in_public(to: 0x…user, from: 0x…attacker, …) on Token 0x…`. The user approves and confirms with biometrics; the
real transfer runs. The same applies to private transfers through authorization requests. The wallet's own send path is
unaffected (it executes bytecode), which keeps the planted artifact invisible until a dApp approval.

Fix: never let a site's artifact stand for a class the wallet ships (Token, SponsoredFPC, account classes): compute those
class ids once and prefer the shipped artifact in `artifactAt`/`registerToken`; make a site's registration of a class
already in the store a no-op (`getContractClassMetadata` before the raw call, on the `registerContract` path too) and mark
the class as site-registered there exactly as `#noteClass` does; derive labels and storage slots only from trusted
artifacts. Regression test: register a same-id artifact with swapped names through the gateway, follow the token, approve
a transfer; the review must show the shipped labels and the balance change.

### F2 — P1 — No privacy manifest

Location: `ios/` has no `PrivacyInfo.xcprivacy` and `ios/project.yml` adds none. The app uses `UserDefaults`
(`ios/Azwall/AppContainer.swift:48,64-65,83`, `ios/Azwall/Browser/BrowserTab.swift:75`, `@AppStorage` in SettingsView and
BrowserView), a "required reason" API; the Rust core and Barretenberg also call the `stat` family. Since 1 May 2024 App
Store Connect rejects uploads that use required-reason APIs without a manifest (ITMS-91053).

Fix: add `ios/Azwall/PrivacyInfo.xcprivacy` to the app target (`NSPrivacyTracking: false`, no collected data types,
`NSPrivacyAccessedAPICategoryUserDefaults` with reason `CA92.1`, `NSPrivacyAccessedAPICategoryFileTimestamp` with
`C617.1`; check the final binary for `SystemBootTime` and `DiskSpace` uses), and declare `ITSAppUsesNonExemptEncryption`
(see F23).

### F3 — P1 — Organisation enrolment (to confirm)

App Review Guideline 3.1.5(b)(i): apps that facilitate virtual-currency storage must be offered by developers enrolled as
an organisation. The publisher named in the privacy policy is a company; the enrolment type of the Apple developer account
is not visible in the repository. Confirm before building the submission: an individual account is a certain rejection.

### F4 — P2 — iOS locks only once the PXE page has answered

Locations: `ios/AzwallKit/Sources/AzwallKit/AppModel.swift:159-164` (`lock()` awaits `session.close()` before
`vaultState.forget()` and `phase = .locked`); `WalletSession.swift:217-224` (`close()` awaits `client.close()`);
`PxeChannel.swift:43-47` (`close` is a "long" method: 20-minute timeout). Android locks first and closes in the
background (`android/core/.../wallet/WalletSession.kt:243-251`).

Scenario: the user taps "Lock wallet", or comes back after five minutes away. The page is busy (a sync, the multi-second
gzip and AES of the store copy on iOS, or a wedged promise, the case the timeouts were added for). The wallet stays
`.unlocked`, keys in memory, the main view usable, dApp approvals answerable, until the page answers or 20 minutes pass.

Fix: in `lock()`, reject approvals, forget the vault and set `.locked` first; then close and stop the host in a detached
task with a short bound (`close` for a few seconds, then `stop()` regardless). Same ordering for `switchNetwork` and
`restart`. Test: a fake host whose `close` never returns must leave `phase == .locked` and `vaultState.current == nil` at
once.

### F5 — P2 — The iOS vault disappears without a word

Location: `ios/AzwallKit/Sources/AzwallPlatform/Keychain.swift:46` (`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` +
`.userPresence`); `AppModel.swift:62-72` (`boot()`: no vault → `.onboarding`).

Apple's contract for that accessibility class: the items are deleted when the user removes the passcode, and they are
never restored to another device. After either event `has()` is false for both slots and the app shows the welcome screen
as if it had never been set up. Nothing says the keys were erased or that the recovery phrase restores the wallet; an
account imported from a key without its own backup is lost. The state "a wallet existed, the vault is gone" is detectable
(the wallet directory, `azwall.network` and the install marker are still there), the app just does not look. Android has
the equivalent screen (`AppPhase.KeyLost`).

Fix: keep a "wallet created" marker (set on create, cleared on wipe) and show a screen explaining what happened and
leading to the restore flow when the marker is present and the vault is not; say in onboarding that turning the passcode
off erases the wallet from the phone. Alternatively use `WhenUnlockedThisDeviceOnly` with `.userPresence`: the item then
survives a passcode removal, unreadable until a passcode is set again. Also check for a passcode before onboarding
(`LAContext.canEvaluatePolicy`), as Android checks `deviceSecure` (`AppViewModel.kt:103`): today the user copies and
verifies 24 words, then `SecItemAdd` fails with a raw Keychain status.

### F6 — P2 — Android regenerates a missing Keystore key and the vault becomes unreadable

Locations: `android/core/src/main/kotlin/dev/azwall/core/vault/PromptedSecretBox.kt:57` and `KeystoreSecretBox.kt:59`
(`key()`: `getKey(alias) as? SecretKey ?: generate()`, used by `open()` as well as `seal()`); `AppViewModel.kt:131-145`
(`VaultKeyLost` is the only path to the KeyLost screen, raised solely from `KeyPermanentlyInvalidatedException`);
`ui/AzwallApp.kt:59` (the Locked phase offers Unlock only).

Android's keystore deletes auth-bound keys when the lock screen is removed (`onUserLskfRemoved`), and vendor Keystore
resets after OS updates are a known class of bug. In those cases `getKey` returns null rather than throwing: `open()`
generates a brand-new key under the alias, `doFinal` fails with `AEADBadTagException`, `Vault.unlock()` rethrows it, and
the lock screen shows "Tag mismatch" on every attempt. There is no "remove wallet" on that screen: the only exit is
uninstalling the app (which also deletes the local history and PXE stores) and restoring from the phrase. The existing
tests inject `VaultKeyLost`; no test or device run covers a missing entry.

Fix: a missing key on `open()` is `VaultKeyLost`, never a reason to generate (only `seal()` of a fresh vault may
generate); let the Locked screen reach "Remove wallet" after a non-authentication failure. Verify on a device by removing
the screen lock.

### F7 — P2 — A dApp transaction the node accepted is shown Failed when its `wait` fails

Locations: `pxe/host/src/dapp/gateway.ts:330-346` (`#send`: any throw from the raw `sendTx` → `dappTx failed`); pinned
`base_wallet.js:368-415` (after `aztecNode.sendTx`, `waitForTx` with the site's `wait` options, default 300 s);
`android/core/.../wallet/Dapps.kt:243-247` and `:189` (`failed` → FAILED, hash kept, but `confirm` is launched for PENDING
only); `ios/AzwallKit/.../Dapps.swift:138-147`; reconciliation of FAILED-with-hash items only at the next open
(`Transactions.kt:252-254`, `WalletStore.kt:137-140`).

Scenario: a slow testnet, or a site passing `wait: { timeout: 1 }`. The node accepted the transaction (`txSubmitting`
recorded its hash); the wait throws; the activity row shows Failed; the site is told the call failed and asks the user to
retry; the user approves a second, identical transaction. Both are mined. The wallet's own path has the same in-session
gap when the native call times out after the node accepted.

Fix: once `txSubmitting` fired for an item, a later error is "sent, outcome unknown": record PENDING and `confirm` it,
never FAILED; in the apps, follow at once any FAILED item that carries a hash and no final receipt error. Test: a fake node
that accepts and a `wait` that times out must leave the item pending, then confirmed.

### F8 — P2 — The default dApp `sendTx` result shape hides the hash

Locations: `pxe/host/src/dapp/describe.ts:14-22` (`txHashOf` reads `txHash` and `result` only); pinned
`aztec.js/dest/wallet/wallet.js:365-378` (`sendTx` returns `{ txHash }` or `{ receipt }`, the latter being the SDK default
when `wait` is not `NO_WAIT`); `Dapps.kt:243-245` and `Dapps.swift:142` (`sent` without a hash → CONFIRMED, and the stored
hash is overwritten with null). Unit-tested by the area review: `txHashOf({ receipt: { txHash } })` is `undefined`.

Scenario: any dApp using the SDK defaults. After the wait returns at status PROPOSED (a block the SDK documents as
re-orgable), the row becomes Confirmed with no hash: no explorer link, no reconciliation, and a transaction later dropped
stays Confirmed forever.

Fix: read `receipt.txHash` (better: take the hash from the `txSubmitting` hook the gateway already has), never erase a
stored hash, record `sent` as PENDING and let `confirm` settle it from a receipt.

### F9 — P2 — Approval panel spoofing

Three display weaknesses a phishing dApp would combine:

- **Origin cut at the end.** `android/app/.../browser/ApprovalPanel.kt:77` (`maxLines = 2`, tail ellipsis),
  `ios/Azwall/Browser/ApprovalPanel.swift:39` (`.lineLimit(2)`). Hostnames may be 253 characters:
  `app.aztec.network.wallet-session-verify-<…>.evil-host.io` reads as `app.aztec.network…`. The origin itself comes from the
  web view (good). Fix: show the registrable domain first and in bold, middle-ellipsize the rest.
- **Site strings rendered verbatim.** `gateway.ts:156` and `:360` (`It calls itself "…"`), `:252` (simulation error text),
  `calls.ts:114-122` (an unverified call's `name`, unbounded), rendered as `• $line`. A `name` containing line breaks forges
  extra bullet lines ("You receive 1,000 USDC…") indistinguishable from the wallet's. Fix: strip control characters and
  line breaks, collapse whitespace, cap at about 80 characters, keep the raw text in the details sheet only.
- **Token symbols without provenance.** `effects.ts:64-71` shows a site-registered token's on-chain symbol with 12
  characters of its address, while calls get "(as this site names it)" (`calls.ts:134`). A fake "USDC" minted to the user
  makes "You send 100 USDC / You receive 100 USDC" read as a round trip. Fix: mark tokens the wallet does not follow on the
  balance lines, and show the address in "Found in your apps".

### F10 — P2 — An iOS archive can ship `.env` RPC keys

Location: `ios/project.yml:64-70` runs `azwall-config --out …` for every configuration; nothing on the iOS side runs
`azwall-config --public-only`. Android has the gate (`android/app/build.gradle.kts:144-167`), and `docs/release.md`
documents Android only. A developer archiving with a keyed `AZWALL_RPC_*` in `.env` ships the key in the IPA, readable by
anyone. Fix: run `--public-only` in that build phase when `$CONFIGURATION` is Release, and document the iOS release.

### F11 — P2 — The native prover's sources are pinned by a mutable tag

Location: `scripts/lib/bb-sources.sh:8-21` clones `aztec-packages` at `--branch v$release` and only checks that HEAD
carries the tag; nothing records which commit `v6.0.0` must be (today `0219f11d…`), nor a hash of the resulting
`libbb_v6.0.0.so` / `AzBB_6_0_0.xcframework`. Inside the checkout, libdeflate is fetched by tag too and upstream's
`generate.sh` runs at build time. A moved tag silently changes the prover behind every proof, and a third party cannot
verify the shipped prover from the repository. Fix: record the expected commit per release and fail on a mismatch; record
the sha256 of each built library and have `build-android.sh`/`build-ios.sh` verify it before staging.

### F12 — P2 — After Retry on the same network, iOS dApps are silently broken

Location: `ios/Azwall/Browser/MainView.swift:15-21,58,77-87`: `BrowserTabs` is `@State` and `attachBrowser()` runs on
`.task(id: session.network.id)` only. `restart()` replaces the session without changing the network id, so the browser
keeps the closed session's `DappLink` (weak session, now nil) and every dApp message is dropped without a message until
the user locks and unlocks. Functional, not security; fix by keying the attachment on the session's identity.

### F13 — P2 (PLAUSIBLE) — iOS call timeouts count suspended time

Location: `ios/AzwallKit/Sources/AzwallKit/PxeChannel.swift:66-72` arms `Task.sleep(for:)` on the continuous clock, which
keeps counting while the process is suspended. Scenario: the user confirms a transfer, switches app, comes back 25 minutes
later. The sleep expires at once: `track` records Failed and resets the proof gate; the page resumes, its `prove` call is
accepted (the gate was not cancelled), it emits `txSubmitting` (no proving item left to match) and sends. History says
Failed; a retry pays twice. Fix: when `track` gives up on a transaction whose proof was not released, cancel the gate so the
late proof is refused; let `followSubmission` match a timed-out item. Android's `delay` has the same exposure to the
cached-app freezer and should be checked too.

### F14 — P2 — The iOS test suite at the audited revision does not compile

Locations: `ios/AzwallKit/Tests/AzwallKitTests/SubmissionTests.swift:89,105` (`DispatchSemaphore.wait()` inside a
detached task's closure, which Swift 6 refuses from an async context) and `SessionTests.swift:70` (`await` on a throwing
hook without `try`), both from today's port `88f866d`. `swift build` of the production sources is clean, so the app itself
compiles; but `swift test` fails to build, which means the two iOS commits of the day (`68ed766`, `88f866d`) were pushed
without being run, and the iOS regression tests they describe (A01–A06 ports) had never executed. Fixed in this working
tree (a synchronous helper for the semaphore wait, a `try`): 111 tests in 28 suites then pass on the Mac. Prevention:
`scripts/test-all.sh` runs the Swift tests only where Swift is installed, so a commit from the Linux machine never sees
them; run them on the Mac (or in CI) before pushing anything under `ios/`.

### Other findings (P3)

Android
- F15. A dApp transaction in flight is unprotected: `onBackground`/`onForeground` check only the wallet's own `proving`;
  `lock()`, `switchNetwork()`, `restart()` and the back gesture have no guard; `WalletSession.close()` drops `pxeEvents` before
  the PXE's `close()` runs, so a `txSubmitting` emitted in the second or two after the proof was handed over is lost and the
  item is later marked Failed "interrupted before the proof was sent" though it may be mined
  (`AppViewModel.kt:188-224`, `WalletSession.kt:169,244-252`).
- F16. The five-minute background lock is skipped, not re-armed, when a proof is running when the timer fires
  (`AppViewModel.kt:193-197`): the wallet then stays unlocked in the background.
- F22. The hidden PXE web view (signing keys and store key in its JavaScript heap) shares the single WebView renderer process
  and the default profile with dApp pages; a renderer exploit from a page reaches the keys. A separate `:pxe` process would
  split them; `setProfile` would not. Everything else about the web views checked out (interface and asset loader on the PXE
  view only, browser refuses the PXE origin and every non-http(s) scheme, file and content access off, no mixed content,
  renderer death handled, cleartext dead in release).
- `DiagnosticLog.redact` cuts `0x`-prefixed hex and URL paths only; bare hex, base64 and recovery words would pass. No current
  path logs a key, phrase or store key (verified by grep of the app and the pinned Aztec logs), so this is defence in depth.
- `wipe()` is not transactional (a partial wipe leaves the plaintext metadata and the Keystore key) and leaves WebView HTTP
  cache and form data; the secret-key Copy is unprotected on Android 11–12 (`EXTRA_IS_SENSITIVE` is 13+).

iOS
- The app-switcher cover is an overlay on the root view and does not cover presented sheets: the "Signing key to import"
  field and the account sheet appear in the switcher snapshot (`AzwallApp.swift:41-42`, `Sheets.swift:72`).
- `beginBackgroundTask()` without an expiration handler around the flush (`AzwallApp.swift:46`): a slow flush ends in a
  watchdog kill. Proving is not covered by a background task at all (a brief app switch stalls a 15–30 s proof).
- `ITSAppUsesNonExemptEncryption` is absent from `project.yml`; no privacy-policy link on the welcome screen (Settings has one;
  Android has both); "Create a token" and "Mint" on mainnet may draw a 3.1.5 question from App Review.
- `AppModel.wipe()` ends in `.onboarding` even if `erase` threw (`AppModel.swift:177-178`); WebKit data removal on wipe is
  fire-and-forget; `BundleServer`s are never stopped on lock or wipe and a tab can open the PXE origin (fingerprint only:
  the server serves the public bundle and the bridge accepts the PXE page's main frame only).

dApp gateway
- F18. `profileTx` with `skipProofGeneration: false` runs the native prover without a panel (battery, CPU) and its
  `ProofGate.release()` disables Cancel on the user's own transaction being proven meanwhile (`policy.ts:33-37`,
  `AndroidPxeLauncher.kt:44-52`). Force `skipProofGeneration: true`, and key the gate to the item it protects.
- F19. `registerContract`, `registerContractClass`, `registerSender`, `getContractMetadata`, `getContractClassMetadata` are
  unscoped and unapproved; messages up to 32 MB are accepted; `canRegister` is displayed but not enforced;
  `isArtifactRegistered` tells a site which classes the wallet has used.
- F20. Reject/Approve are enabled the instant the panel appears; connect, capabilities and verify need no biometric, so a
  request timed to a tap on the page's own button obtains a session and simulation scopes unread. Ignore taps for about half a
  second after the panel appears or changes.
- F21. Effects are simulated without the authorizations `EmbeddedWallet.sendTx` then signs for the sender, and those
  implicit authorizations are never listed; list them ("lets <consumer> call <selector> on your behalf").
- The fee line says "paid from your account" whenever a site sets `gasSettings` on a sponsored network although the sponsor
  pays (`gateway.ts:265`); `submit.ts` believes a DROPPED read 2–6 s after a lost answer while the apps wait 15 minutes; a
  Confirmed-at-PROPOSED item is never re-read; the gas-cache fast path skips implicit authorizations and the failure is not
  retried; an explicit site `createAuthWit` is refused while another account's send is in flight.

Rust, build, supply chain
- F17. `cargo audit`: RUSTSEC-2026-0382 (`rustls-webpki 0.103.15`) and RUSTSEC-2026-0381 (`webpki-roots 1.0.9`), the TLS
  stack of the node probe; `cargo update -p rustls-webpki -p webpki-roots@1.0.9` resolves them.
- `uniffi`'s `cli` feature sits in the shipped library's normal dependencies (177 crates, the bindings generator and an
  unmaintained `basic-toml` among them): feature-gate the bindgen binary.
- `ureq` follows redirects (POST → GET, `http` allowed) and `is_http_url` accepts `http://` for registry and `.env` RPC
  URLs: `https_only(true).redirects(0)`, require https except localhost.
- Node-controlled `error.message` and `nodeVersion` reach screens and the diagnostic log unbounded (up to 10 MiB); the
  JSON-RPC `id` is never checked.
- Phrase and signing keys cross the FFI as `String` without zeroisation (hygiene; needs process-memory access).
- The 1 GiB whole-request cap of the prover exceeds a phone's memory (the low-memory killer fires first); 256 MiB would do.
- `scripts/build-ios.sh` sets no `IPHONEOS_DEPLOYMENT_TARGET`, so the Rust core's C and assembly objects (ring) are built for
  the SDK's own version: the Xcode build links them into an app that targets iOS 17.0 with a warning for every such object
  ("built for newer iOS-simulator version (27.1) than being linked (17.0)"). Export `IPHONEOS_DEPLOYMENT_TARGET=17.0` in the
  script, as `build-bb-ios.sh` does for Barretenberg, and verify the device build on an iOS 17 phone.
- `fetch-crs.sh` moves files in place before the sha256 check (both app builds re-check); `srs.rs` accepts an oversized CRS
  (Barretenberg refuses it anyway).
- The Android release gate is wired with `tasks.findByName(...)?.dependsOn` in `afterEvaluate` and would vanish silently if
  AGP renamed the task; `docs/release.md` lacks the iOS procedure, the prover commit and hash verification, and an audit step.
- `pnpm audit --prod`: eight advisories (`systeminformation`, OpenTelemetry, `uuid`), all Node-side telemetry and storage
  code tree-shaken out of the shipped bundle (none of their symbols appears in `assets/pxe/v6`); overrides would be hygiene.

## Randomness and key derivation

- **Phrase**: `rust/azwall-core/src/mnemonic.rs:25-32`: 32 bytes from `getrandom` (the OS generator: `getrandom(2)` on
  Android, `SecRandomCopyBytes`/`getentropy` on iOS), `bip39::Mnemonic::from_entropy_in`, 24 words; the entropy buffer is
  zeroed. Both apps obtain phrases only through this function (`newRecoveryPhrase` in `RustWalletBackend` on both
  platforms); no fake backend is compiled into a production source set.
- **Derivation**: BIP-39 seed (PBKDF2-HMAC-SHA512, 2048 rounds, empty passphrase), then
  `SHA-512(domain ‖ seed ‖ index) mod Grumpkin order`. A 512-bit digest reduced modulo a 254-bit order has negligible bias
  (below 2^-250). The scheme is frozen by test vectors cross-checked outside the code base. It is not BIP-32/44, which the
  design page says; phrases cannot be moved to another Aztec wallet, but a signing key can.
- **Other keys**: the PXE store key and random signing keys of pre-phrase wallets come from `SecureRandom` (Android) and
  `SecRandomCopyBytes` (iOS); the PXE's `newSigningKey` handler (`Fq.random`, WebCrypto) is not used by the apps. The 44
  `Math.random` hits in the shipped bundle are Aztec test fixtures, SQLite WASM's non-crypto source and JSON-RPC request
  ids. AES-GCM IVs of the iOS store copies come from `crypto.getRandomValues`.
- **Vault**: AES-256-GCM under an Android Keystore key bound to one operation per authentication (BiometricPrompt's
  CryptoObject), or an iOS Keychain item behind Face ID, Touch ID or the passcode, never synced or restored to another
  device. The phrase and every key live only in that sealed vault and in memory while unlocked.

## Earlier findings: status

| Finding | Status |
|---|---|
| A01 migration fallback rolls back newer keys | Fixed (`68ed766`); `VaultUpgradeTest` covers it |
| A02 concurrent sends broaden authorization | Fixed (`746dcd7`): every send is queued, `createAuthWit` refuses any other sender; `dapp_safety.test.ts` |
| A03/A06 uncertain submission, cancel after broadcast | Fixed (`88f866d`): hash told before sending, pending instead of failed, overlay honest; `SubmissionTest(s)` on both platforms. Remaining in-session gaps: F7, F8, F15, F13 |
| A04 wipe races a save | Fixed (`68ed766`): erase waits under the vault mutex; iOS `deletingTheWalletWhileSavingLeavesNoVault` |
| A05 balance change hides other calls | Fixed (`746dcd7`): only verified balance functions are hidden; F1 subverts "verified" upstream |
| R1 Retry during a save | Fixed (`4269719`); `AccountRaceTest` |
| R2 non-atomic metadata fallback | Fixed; rename only |
| Pre-release 2–5, 7, 8 (alignment, API 30 encoders, policy link, x86_64, renderer, rename) | Fixed and re-verified in the build and the code |

## What was found sound

Origin and session binding from native code only; review-equals-run canonicalisation of dApp calls; label-versus-selector
check; account confinement and empty address book; `NO_FROM` sends obtain no authorization; biometric confirmation on
every signing path (wallet and dApp) on both platforms; pending panels rejected on lock, restart, network switch and
navigation; recipient and amount validation of the wallet's own transfers with the full address on the review; Fee Juice
deposits bound to their message hash and gated by the node; chain id and rollup version checked twice; the loopback
bundle server (loopback only, GET, prefix check after path standardisation, public files only); the PXE bridge accepting
only its own page's main frame; `FLAG_SECURE` on every Android screen showing a secret, password keyboards without
autocorrect, no secret in saved instance state, onboarding impossible to finish without the phrase shown and three words
checked back; backups and device transfers fully excluded; R8 keeping every serializer; the vault's single owner and
serialised writes; the two-slot Keychain write that always leaves a complete copy; strict `.env` parsing and the Android
public-only gate; the registry's `barretenberg` string reaching `dlopen` only through the compiled release table;
bounds-checked wire parsing with gzip caps; the prover mutex held for the whole proof; the shim exporting two symbols with
PIE, full RELRO, NX and 16 KB alignment; `Cargo.lock` and `pnpm-lock.yaml` with checksums, exact Aztec pins cross-checked
by a test, dependency lifecycle scripts blocked.

## Before submitting

1. Fix F1, F7, F8 and F9 together in the gateway and the two apps, with the regression tests named above.
2. Fix F4, F5, F6, F12 and F13 (lifecycle and lock-out paths), then run the device checklist: remove the screen lock or
   passcode with a wallet present; lock during a sync; Retry then connect a dApp; leave the app for 25 minutes mid-transfer.
3. Add the privacy manifest and the encryption key (F2), confirm the organisation enrolment (F3), add `--public-only` to the
   iOS build phase (F10), pin the Barretenberg commit and library hashes (F11), bump the two RustSec crates (F17).
4. Then the hardening items by cost, F15, F16, F18–F22 first.
5. Real devices before external testers: Android API 30–32 and a 16 KB device, an iPhone with Face ID; create and restore;
   imported and derived accounts; a private transfer; shield and unshield; a dApp connect, reject and approve; renderer
   termination; cancellation; a lost network after submission. No live transaction was sent and no physical device was used
   in this audit.
