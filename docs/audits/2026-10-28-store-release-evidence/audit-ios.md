<!-- Working notes of one area of the 2026-10-28 store-release audit. The consolidated, verified report is ../2026-10-28-store-release-audit.md; where the two differ, it prevails. -->

# iOS pre-release (App Store) audit — Azwall

Date: 2026-10-28. Reviewed commit: `88f866d` (HEAD of `main`). Read-only source review on Linux: no Swift toolchain, so
nothing was compiled or run; every "CONFIRMED" below is confirmed by reading the code and documented platform
behaviour, every "PLAUSIBLE" says what is missing. Android counterparts were read for the equivalence checks.

**Recommendation: do not submit yet.** Two certain App Store blockers (privacy manifest; developer enrolment type for a
wallet app), one silent-loss scenario the Android app handles and iOS does not (passcode removal), and three P2
behaviours around locking and long suspensions. No P1 was found in the vault, bridge or browser code itself: the
Keychain two-slot write, the loopback server, the content-world isolation and the proof gate are sound.

## 0. Is "iOS has not been compiled yet" still true?

No — the statement in [docs/architecture.md:99](/home/eddy/Projects/azwall/docs/architecture.md:99) (written 2026-10-18,
commit `75d5d92`) is stale:

- `ea994b2` (2026-10-21) "fix(ios): first compile with Xcode 27.1 / Swift 6.4" — body: "Built on a Mac: Rust core for
  device and simulators, swift test (15 tests pass), xcodebuild for the simulator; the app launches."
- `dfaadeb` (2026-10-21) "feat(ios): Barretenberg frameworks for the native prover" and `scripts/build-bb-ios.sh`.
- Later commits describe *observed* runtime behaviour, which needs a run: `c2b4e9f` ("no log after 'creating the PXE',
  not even the 10 s heartbeat"), `e36c70f` / `89ffe44` (OPFS "AccessHandle is closed", "file is not a database"),
  `60e6acd`, `2f0b747` ("a stopped web view no longer crashes the app"), and the Xcode pre-build script fixes
  `99e44b7` (10-27) and `40709ac` (10-28).

So the app has been built and run on a Mac through 2026-10-28. What cannot be established from the repository: whether
it was run on a **physical device** (the first-compile note says simulator), whether a **proof** was ever produced on
iOS (the Rust prover + `AzBB_6_0_0.xcframework` path), and whether today's last two commits (`68ed766`, `88f866d`,
19:31, the iOS ports) were compiled — I read them for compile errors and found none, but see §7. Neither
`build/ios/AzwallFFI.xcframework` nor `vendor/bb-ios/` exist in this checkout (both are local build outputs). The doc
line should be replaced by what remains untested (device proof, App Store build).

## Findings (summary)

| ID | Sev | Status | One line |
|---|---|---|---|
| I01 | P1 | CONFIRMED | No `PrivacyInfo.xcprivacy`; `UserDefaults` (and the Rust/C++ core's `stat` family) are "required reason" APIs → ITMS-91053 rejection at upload |
| I02 | P1 | PLAUSIBLE | App Review 3.1.5(b)(i): wallet apps must come from a developer **enrolled as an organization**; cannot see the enrolment |
| I03 | P2 | CONFIRMED | Removing the device passcode makes iOS delete the vault (`WhenPasscodeSetThisDeviceOnly`); the app then shows plain onboarding with no explanation or "restore from phrase" path (Android has `VaultKeyLost`) |
| I04 | P2 | CONFIRMED | `lock()` awaits the PXE `close` call (20-min timeout) before forgetting the vault; a stuck page keeps the wallet unlocked and usable up to 20 min (Android locks first, closes in the background) |
| I05 | P2 | PLAUSIBLE | Channel timeouts run on a continuous clock: a transfer suspended > 20 min in the background is marked failed on resume while the PXE finishes it and sends it; its `txSubmitting` hash is then ignored → double-send risk |
| I06 | P2 | CONFIRMED | After "Retry" (`restart()`) on the same network, the browser keeps the dead session's `DappLink`: dApps silently cannot connect until lock/unlock |
| I07 | P3 | CONFIRMED | `PrivacyCover` is an `.overlay` on the root view: it does not cover presented `.sheet`s (imported-key field in `AccountsSheet`, review/approval sheets) in the app switcher snapshot |
| I08 | P3 | CONFIRMED | `beginBackgroundTask()` without an expiration handler around `flush`; a slow flush ends in a watchdog kill |
| I09 | P3 | CONFIRMED | No passcode check before creating the vault: on a device without passcode the phrase is shown, verified, then `SecItemAdd` fails with a Keychain status string (Android checks `deviceSecure` first) |
| I10 | P3 | CONFIRMED | `ITSAppUsesNonExemptEncryption` undeclared; no privacy-policy link on the welcome screen (Android has one); "Create a token"/Mint on mainnet may draw a 3.1.5 question |
| I11 | P3 | CONFIRMED | `BundleServer`s are never stopped (lock, wipe); a dApp page can probe `http://127.0.0.1:476xx` (fingerprint "Azwall installed"); tab can open the PXE origin (Android blocks it) |
| I12 | P3 | CONFIRMED | `AppModel.wipe()` sets `.onboarding` even when erasing threw (Android stays Locked); WebKit data removal is fire-and-forget |
| I13 | P3 | NOTE | Proving is not covered by a background task; no third-party-keyboard block; phrase/key text fields feed QuickType; screenshots cannot be blocked (not stated to the user) |

Strong points are listed in §9, test gaps in §8.

## 1. Vault and Keychain

**Access class and `has()`** — [Keychain.swift:46](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/Keychain.swift:46)
creates items with `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` + `.userPresence`. Apple's contract for that
class: never migrates to another device, not restored from backups to a new device, and **"if the user removes the
passcode, all items with this attribute are deleted"**. `has()` ([:21-29](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/Keychain.swift:21))
uses `interactionNotAllowed` and treats `errSecInteractionNotAllowed` as present — correct for a `.userPresence`
item (and for a locked device). After a passcode removal the items are gone, so `has()` returns `false` for both
slots, `exists()` is false, and `AppModel.boot()` ([AppModel.swift:62-72](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AppModel.swift:62))
goes to `.onboarding`.

- **I03 (P2, CONFIRMED)** — Scenario: the user turns the passcode off (lending the phone, a child, a repair), later
  turns it back on, opens Azwall: the welcome screen, as if never set up. Nothing says the keys were erased or that
  the recovery phrase restores the wallet; an account imported from a key without its own backup is lost. The
  wallet directory (`Application Support/wallet`), `azwall.network` and the `azwall.installed` marker are all still
  there, so the state "wallet existed, vault gone" *is* detectable — the app just does not look. Android detects the
  equivalent (`KeyPermanentlyInvalidatedException` → `VaultKeyLost`, phase `KeyLost`, a screen leading to restore,
  [KeystoreSecretBox.kt:21](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/vault/KeystoreSecretBox.kt:21),
  [AppViewModel.kt:139](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt:139)).
  Fix options: (a) keep the class and add a "wallet created" marker (set on create, cleared on wipe) so
  `boot()` can show a "Your wallet's keys were erased by iOS when the passcode was removed: restore from your
  recovery phrase" screen; or (b) use `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` + `.userPresence`: the item
  then survives a passcode removal (unreadable until a passcode is set again) instead of being deleted. (b) is
  safer for funds; nothing is released yet, so no migration is needed. Either way, tell the user before it happens
  (onboarding text: "Turning off the passcode erases the wallet from this phone").

**Two-slot write** — [VaultSlots.swift:34-44](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/VaultSlots.swift:34).
Every step leaves a complete copy: main→(spare=new, main=old)→(spare=new)→(main=new, spare=new)→main. Interleavings:
- Death between `add(spare)` and `remove(main)`: main=old, spare=new; `read()` ([:29-32](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/VaultSlots.swift:29))
  prefers main (old), and the next `write` drops the newer spare (the comment at :36 "a spare left by an interrupted
  write is older" is wrong in this one case). **Safe anyway**: `VaultState.change` only publishes `held = next` after
  `write` returned, so the user never saw that save succeed, and the UI state matches the disk. Not a finding, but
  the comment should say so, and a test should pin it (see §8).
- `add(spare)` fails (device locked meanwhile, `errSecInteractionNotAllowed`/`errSecNotAvailable`): main untouched,
  the change throws, `held` unchanged. Covered by `aFailedWriteNeverLosesTheKeys`.
- `add(main)` fails after `remove(main)`: spare holds the new copy, `read()` falls back to it. Covered.
- `SecItemAdd`/`SecItemDelete` with `.userPresence` do not prompt (only `SecItemCopyMatching` does), so no Face ID
  prompt can land in the middle of a write, and `VaultState` serialises writes against unlock/erase anyway.

**Reinstall logic (commit `2c7eeb8`)** — [AppContainer.swift:36-58](/home/eddy/Projects/azwall/ios/Azwall/AppContainer.swift:36).
Marker `azwall.installed` in `UserDefaults` **and** the existence of `Application Support/wallet` (`hadData`) must
both be absent for the Keychain vault to be wiped. Cases:
- Uninstall → reinstall: both absent, Keychain present → wiped (intended).
- Update, Offload App: both present → untouched.
- iCloud / Finder restore **to a new device**: `UserDefaults` restored (marker true), `wallet/` excluded from backup
  (absent), Keychain `ThisDeviceOnly` absent → no wipe, `.onboarding`. Correct, but silent: same gap as I03 (the
  user may believe the restore brought the wallet). A "keys never leave the phone they were made on; restore from
  the phrase" line on the welcome screen would cover both.
- Encrypted restore to the **same** device: marker true, Keychain restored, store absent → `.locked`, unlock works,
  the PXE re-syncs. Fine.
- A `UserDefaults` read glitch cannot wipe: `hadData` must also be false. Good design.
- Note: the marker is never cleared by `wipe()`; it means "launched before", not "has a wallet" — fine for its
  purpose, insufficient for I03's detection (hence the separate marker suggested).

**VaultState (port of `68ed766`)** — [VaultState.swift](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/VaultState.swift)
vs [VaultState.kt](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/vault/VaultState.kt):
equivalent. Serialisation by a task chain instead of a `Mutex`; `open` waits for a save under way; `change` starts
from the latest contents and respects the lock epoch; `erase` forgets first then waits for pending saves
(`deletingTheWalletWhileSavingLeavesNoVault` covers audit A04). Android's `.previous`/upgrade rollback (audit A01)
has no iOS counterpart because iOS never had a legacy key — nothing to port. `VaultContents` serialisation
([Vault.swift:15-37](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Vault.swift:15)) matches the Kotlin
fields and validation (`version`, `accounts[alias, signingKey, salt, index]`, `dbKey`, `phrase`; same regexes).

**Interrupted write by suspension**: Keychain calls are synchronous and short; `VaultState.change` runs them in a
detached task; the OS does not suspend mid-syscall. The only exposure is process death between two calls (above).

**I09 (P3)** — [AppModel.createWallet](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AppModel.swift:75)
never checks that a passcode exists; `SecItemAdd` with `WhenPasscodeSetThisDeviceOnly` fails on a passcode-less
device with a raw status string ("could not save the vault: …"), after the user has copied and verified 24 words.
Android throws `NoScreenLock()` first ([AppViewModel.kt:103](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/AppViewModel.kt:103)).
Check `LAContext().canEvaluatePolicy(.deviceOwnerAuthentication)` on the welcome screen and explain.

## 2. PXE hosting

**BundleServer** ([BundleServer.swift](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/BundleServer.swift)):
- Binds `127.0.0.1` only via `requiredLocalEndpoint` (:19). CONFIRMED loopback.
- Path handling (:64-74): query stripped, percent-decoded, `standardizedFileURL` resolves `..`, then a prefix check
  on `root/pxe/`. `/azwall-config.json`, `/pxe/../azwall-config.json`, `%2e%2e` variants, the CRS, and directory
  listings (`Data(contentsOf: dir)` fails → 404) are all refused. Only `GET`. Tested
  (`servesThePxeBundlesOnly`). No `Host` check, but the only readable content is the public PXE bundle, so DNS
  rebinding or another app on the port yields nothing secret. Another app can: learn Azwall is installed and which
  bundles it ships; or bind the port first, in which case `NWListener` fails and nothing is loaded (DoS only,
  [PxeHost.swift:63-75](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/PxeHost.swift:63)). The
  "only loads from its own listener" rule of `2f0b747` holds: `webView.load` happens only after `server.start()`
  returned `.ready`, and a reclaimed listener is replaced before any new load.
- **I11 (P3)**: servers live in a static dictionary ([PxeHost.swift:20](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/PxeHost.swift:20))
  and are never stopped on lock or wipe; they keep serving public files while the wallet is locked. Harmless but
  not "only while needed". WebKit treats `127.0.0.1` as potentially trustworthy, so an https dApp page can
  `fetch`/iframe `http://127.0.0.1:47606/pxe/v6/index.html` (opaque/cross-origin, no bridge in that web view's
  world, separate data store) — nothing to gain beyond a fingerprint. A tab can also be pointed at the PXE origin
  (`Browsing.address` turns `127.0.0.1:47606` into `http://…`; `decidePolicyFor` allows http); Android blocks it
  (`8a913ec`). Suggest refusing the PXE ports in `BrowserTab.decidePolicyFor`.

**WKWebView for the PXE** ([PxeHost.swift:83-100](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/PxeHost.swift:83)):
JavaScript on (needed), `websiteDataStore = .default()` (persistent; the browser uses a *separate* identified store,
[BrowserTabs.swift:16](/home/eddy/Projects/azwall/ios/Azwall/Browser/BrowserTabs.swift:16), so sites cannot touch the
PXE origin's storage), the `azwall` message handler in the page world (the PXE page is our own code; a page from
another origin or a subframe is ignored by `MessageHandler.accepts`, :161-171, tested). No navigation policy on the
PXE web view — nobody but the page itself can navigate it, acceptable. `limitsNavigationsToAppBoundDomains` not
used, not needed. `inactiveSchedulingPolicy = .none` keeps the hidden page scheduled (the build history shows this
compiled and worked; I could not verify the API's minimum OS offline against the iOS 17 target — keep an eye on it
if the deployment target changes). Process termination is handled
(`webViewWebContentProcessDidTerminate` → channel closed → `hostStopped` → "Retry", [:142-145](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallPlatform/PxeHost.swift:142),
[WalletSession.swift:320-326](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/WalletSession.swift:320)).
`stop()` removes the handler, unloads the page and detaches the 1-pt view. The `.default()` store and the browser
store are both wiped on wallet deletion ([AppContainer.swift:85-89](/home/eddy/Projects/azwall/ios/Azwall/AppContainer.swift:85)),
but fire-and-forget (I12).

**Store copies** ([PxeStoreFiles.swift](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/PxeStoreFiles.swift)):
under `Application Support/wallet/pxe/<bundle>/<name>.sealed`, directory excluded from backup (inherited from
`wallet/`, [AppContainer.swift:19-22](/home/eddy/Projects/azwall/ios/Azwall/AppContainer.swift:19)), written
`.atomic` with `.completeFileProtectionUntilFirstUserAuthentication` (:22). The content is AES-GCM sealed by the
PXE with `dbKey` ([native_store.ts:24-36](/home/eddy/Projects/azwall/pxe/host/src/native_store.ts:24)), so the file
class is a second layer; `Complete` would block background `confirm` writes, so the choice is defensible and the
privacy policy says so. Store names validated (`[A-Za-z0-9-]{1,100}`, tested). An unreadable copy: `storeLoad`
throws on an I/O error (the PXE `open` then fails, shown as "Can't reach …" with Retry); a copy that decrypts
badly is set aside as `<name>-unreadable.sealed` and the store starts empty (re-sync), logged. Those `-unreadable`
files are never cleaned up (minor).

## 3. dApp browser

[BrowserTab.swift](/home/eddy/Projects/azwall/ios/Azwall/Browser/BrowserTab.swift): the content script and the
`azwallBridge` shim are user scripts in `WKContentWorld.world(name: "azwall")`, main frame only, at document start
(:54-62); the `azwallBridge` message handler is registered **in that world only** (:61), so the page world cannot
call it; replies go back with `evaluateJavaScript(_, in: nil, in: world)` (:70). The page talks to the script only
through the wallet-sdk `window.postMessage`/MessageChannel protocol
([content_script.ts](/home/eddy/Projects/azwall/pxe/host/src/dapp/content_script.ts)). Origin comes from
`frameInfo.securityOrigin` (:100), never from the page; `allowedOrigin` is https-only in release (:102-105 in
BrowserTabs). Page ids change on every `didCommit`, late replies are dropped, and `pageClosed` rejects pending
approvals and closes gateway sessions (`BrowserTabs.swift:95-98`). The approval panel shows the host from the
origin first and the page's title second ([ApprovalPanel.swift:37-41](/home/eddy/Projects/azwall/ios/Azwall/Browser/ApprovalPanel.swift:37));
the gateway's `tab` key matches what `DappApprovals.approve` reads
([gateway.ts:39](/home/eddy/Projects/azwall/pxe/host/src/dapp/gateway.ts:39), [Dapps.swift:72](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Dapps.swift:72)).
Lock, network switch and restart call `rejectAll()`. Spends need Face ID (`answer(… owner:)`), double taps are
ignored (`answering`). `dappMessage` returns at once in the host ([api.ts:155](/home/eddy/Projects/azwall/pxe/host/src/api.ts:155)),
so the 2-min read timeout cannot cut an approval short. All good.

Navigation policy (:131-136): `https`, `http`, `about`, `blob`, `data` allowed; custom schemes cancelled. Remaining
gaps, all P3: with the public `.allow`, WebKit still opens **universal links** of other apps on user taps; no
`WKUIDelegate`, so `window.open`/`target=_blank` do nothing (UX) and JS dialogs are auto-dismissed (fine); a page
asking for `getUserMedia` would trigger the system camera prompt with Azwall's "scan a QR code" reason —
implement `requestMediaCapturePermissionFor` → `.deny`. `http://` sites are blocked by ATS in release (no
`NSAllowsArbitraryLoadsInWebContent`), which is privacy-positive but worth knowing.

**I06 (P2, CONFIRMED)** — [MainView.swift:15-21,58,77-87](/home/eddy/Projects/azwall/ios/Azwall/Browser/MainView.swift:15).
`BrowserTabs` is `@State`, created once per `MainView` identity; `attachBrowser()` runs in `.task(id: session.network.id)`.
`AppModel.restart()` (the "Retry" after the engine stopped) replaces the session with a new one **on the same
network** without passing through `.locked`, so `RootView` keeps the same `MainView` identity, the `.task(id:)`
does not re-run, and `BrowserTabs.link` still points at the old session's `DappLink`, whose `weak session` is nil.
`DappLink.fromPage` then returns at `guard let session` ([Dapps.swift:187](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Dapps.swift:187)):
every dApp message is dropped silently; the page waits forever. Lock/unlock or a network switch repairs it.
Functional, not a key risk, but it breaks the main recovery path on a platform where WebKit kills hidden
processes under memory pressure. Fix: `.task(id: ObjectIdentifier(session))` or re-attach on session change.

## 4. Secrets on screen and in memory

- Phrase screens: `PhraseBackupView`/`PhraseVerifyView`/`RestoreView` are pushed in a `NavigationStack`
  ([OnboardingView.swift:13-23](/home/eddy/Projects/azwall/ios/Azwall/Views/OnboardingView.swift:13)), so the
  root `PrivacyCover` ([AzwallApp.swift:41-42](/home/eddy/Projects/azwall/ios/Azwall/AzwallApp.swift:41)) covers them
  whenever `scenePhase != .active` (switcher, Control Center, Face ID prompt). Three random words checked, compared
  lowercased ([PhraseViews.swift:58-60,76](/home/eddy/Projects/azwall/ios/Azwall/Views/PhraseViews.swift:58)). No copy
  button for the phrase. The words live only in the navigation path state; process death mid-onboarding loses
  nothing (no vault yet) and after `vault.create` the next launch is `.locked`.
- Settings: `PhraseSheet` and the "Secret key" alert are cleared on `scenePhase != .active`
  ([SettingsView.swift:40-49](/home/eddy/Projects/azwall/ios/Azwall/Views/SettingsView.swift:40)); the key "Copy" is
  local-only with a 60 s expiry ([Format.swift:7-9](/home/eddy/Projects/azwall/ios/Azwall/Views/Format.swift:7)). Good.
- **I07 (P3, CONFIRMED)** — a SwiftUI `.overlay` on the root content does not cover modally presented sheets and
  alerts (they are presented above the hosting controller's hierarchy). What is in a sheet at background time is
  in the switcher snapshot: the **"Signing key to import" field of `AccountsSheet`**
  ([Sheets.swift:72](/home/eddy/Projects/azwall/ios/Azwall/Views/Sheets.swift:72), presented by
  [HomeView.swift:61](/home/eddy/Projects/azwall/ios/Azwall/Views/HomeView.swift:61)) — a key being typed/pasted —
  plus non-secret but private sheets (review, approval details, activity, connections). The phrase sheet is cleared
  on `.inactive`, so only the dismiss-animation frame is exposed. Android keeps the imported key out of
  screenshots (`2c7eeb8`). Fix: present the cover in its own `UIWindow` above everything (windowLevel .alert+1), or
  `.fullScreenCover`, and clear the import field on `.inactive`.
- Text fields: autocorrection off and no capitalization on phrase/key fields; `keyboardType` default (so emoji
  and predictive bars show), no `.textContentType`; QuickType still learns typed words. Third-party keyboards are
  not blocked (no `shouldAllowExtensionPointIdentifier`). Screenshots cannot be blocked on iOS and the UI does not
  say so (the design doc only promises the switcher cover). All I13, hardening.
- Pasteboard: the phrase is never copied; addresses/hashes use the general pasteboard (fine); the recipient
  "Paste" button reads the pasteboard on tap only (iOS shows its banner).
- Memory: the phrase and keys cross uniffi as Swift `String`s and sit in `VaultState` and in the PXE page's heap
  while unlocked; `lock()` drops `held` and unloads the page. Same model as Android; no zeroisation possible for
  Swift strings — accepted.

## 5. Lifecycle

- Auto-lock: `AutoLock` compares dates on foreground ([AutoLock.swift:19-23](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AutoLock.swift:19),
  tested); the 5-min background `Task` ([AppModel.swift:139-150](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AppModel.swift:139))
  only fires if iOS keeps the process running, which it does not beyond the flush — the comment says so. Keys stay
  in the suspended process's memory, as in every iOS wallet. `lock()` forgets the vault and closes the host so
  the keys leave the PXE page.
- **I04 (P2, CONFIRMED)** — [AppModel.lock():159-164](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AppModel.swift:159)
  `await session?.close()` **before** `vaultState.forget()` / `phase = .locked`, and
  [WalletSession.close():217-224](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/WalletSession.swift:217)
  awaits `client.close()`, a `long` method with a **20-minute** timeout
  ([PxeChannel.swift:43-47](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/PxeChannel.swift:43)). If the
  page is alive but not answering (busy sync, a wedged promise — the very case `2f0b747` added timeouts for), the
  wallet stays `.unlocked`, keys in memory, `MainView` shown and usable (a dApp approval can still be answered and
  proven), for up to 20 min after the user tapped "Lock wallet" or came back after 5 min away. Android's `lock()`
  forgets and locks immediately and launches `close()`+`stop()` in the background
  ([WalletSession.kt:243-251](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/WalletSession.kt:243)).
  Fix: in `lock()` reject approvals, forget the vault and set `.locked` first, then close/stop the host in a
  detached task with a short bound (`close` ≤ 10 s, then `stop()` regardless). Same for `switchNetwork`/`restart`.
- Proving in the background: no background task covers `prove` (only the flush in
  [AzwallApp.swift:44-50](/home/eddy/Projects/azwall/ios/Azwall/AzwallApp.swift:44)); the app is suspended within
  seconds, the Rust thread freezes and resumes later — nothing is lost or double-sent by itself (the gate is
  not released, so nothing is sent; if iOS kills the suspended app, `failInterrupted` marks the item failed, or
  pending when a hash was told). A `beginBackgroundTask` around proving would stop a brief app switch from
  stalling a 15-30 s proof (I13).
- **I05 (P2, PLAUSIBLE)** — [PxeChannel.call:66-72](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/PxeChannel.swift:66)
  arms `Task.sleep(for: limit)` on the continuous clock, which keeps counting while the process is suspended.
  Scenario: the user confirms a transfer, switches app; the proof freezes mid-way; they come back 25 min later.
  `onForeground` does not lock (`proving != nil`), the sleep expires at once → `transfer: no answer from the
  wallet engine after 20 min` → `track` records `.failed` ([Transactions.swift:120-140](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Transactions.swift:120))
  and `gate.reset()`. Meanwhile the PXE page resumes, calls `prove` (gate not cancelled → proof released),
  emits `txSubmitting` — `followSubmission` finds no `.proving` item and returns silently
  ([WalletSession.swift:311-313](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/WalletSession.swift:311)) —
  and sends. History says failed; a retry pays twice. Missing: a device run with a forced suspension (or a unit
  test with a short `timeoutOf` and a delayed fake). Fix: when `track` gives up on a transaction whose proof was
  not released, call `gate.cancel()` so the late proof is refused (then "failed" is true); and let
  `followSubmission` also match a `.failed`-without-hash item that timed out. Android's `delay` on the main
  dispatcher has the same exposure to the Android 12+ cached-app freezer — worth checking there too.
- WebContent termination during a wallet transaction: channel closed → `track` fails (hash kept if told) →
  `unsettled()` reconciles on the next open ([WalletStore.swift:164-172](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/WalletStore.swift:164)).
  Writes since the last `storeSave` debounce are lost and re-synced from chain. Fine.
- **I08 (P3)** — `UIApplication.shared.beginBackgroundTask()` without an `expirationHandler`
  ([AzwallApp.swift:46](/home/eddy/Projects/azwall/ios/Azwall/AzwallApp.swift:46)); `onBackground` awaits
  `flush` (gzip + AES of MBs; 20-min timeout). If it outlives the ~30 s grant, iOS kills the app (`0x8badf00d`).
  Add a handler that ends the task.
- Face ID during a Keychain write: writes do not prompt; `VaultState` serialises unlock/change/erase. A system
  cancel of the prompt (app backgrounded) returns `false` → "authentication was cancelled". Fine.

## 6. Transactions (port of `88f866d`, equivalence with Android)

Equivalent, line by line where it matters:
- `ProofGate` ([ProofGate.swift](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/ProofGate.swift)) =
  [ProofGate.kt](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/ProofGate.kt):
  `cancel()` false once released, `release()` throws if cancelled, both under one lock (no window between the
  cancel decision and the hand-over). The prove handlers are the same ([AppContainer.swift:107-116](/home/eddy/Projects/azwall/ios/Azwall/AppContainer.swift:107),
  [AndroidPxeLauncher.kt:44-52](/home/eddy/Projects/azwall/android/app/src/main/kotlin/dev/azwall/app/AndroidPxeLauncher.kt:44)).
- `track`/`proveAndSend`/`windDown`/`confirm`/`resume` = `Transactions.kt`; failed-with-hash kept; `droppedTooSoon`
  grace; `unsettled()` and `failInterrupted()` (hash → pending) = `WalletStore.kt:137-151`. Tests: `SubmissionTests`.
- `followSubmission` = `followSubmissions` (single match, `from` filter).
- `ProvingView` reads `ProofGate.shared.released` and hides Cancel, says "can no longer be cancelled"
  ([ReceiveView.swift:63-78](/home/eddy/Projects/azwall/ios/Azwall/Views/ReceiveView.swift:63)) = `ProvingOverlay.kt`.
  `CancelledByUser` ("nothing was sent") is only thrown when the gate refused the proof — true by construction.
- `DappTx.applied` "sent without hash → confirmed" is the same quirk on both ([Dapps.swift:142](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Dapps.swift:142),
  [Dapps.kt:244](/home/eddy/Projects/azwall/android/core/src/main/kotlin/dev/azwall/core/wallet/Dapps.kt:244)).
- Double send: `busy` from the first tap in `sign`, `proving == nil`, `activating`/`claiming`, `answering` for dApps,
  the review sheet dismissed before Face ID, the form closed only after Face ID. Good.
- Recipient: `0x`+64 hex, not own address ([TransferView.swift:55-59](/home/eddy/Projects/azwall/ios/Azwall/Views/TransferView.swift:55));
  zero address, token contract and off-curve addresses are refused in the shared host (`checkRecipient`, `ecaab7d`).
  Amount: `Amounts.parse` refuses `1,000`, > decimals, zero. The review shows the full recipient, the exact amount,
  network and fee ([Review.swift:20-31](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/Review.swift:20),
  `DetailRow` selectable, full width). A scanned request for another network or an unlisted token is flagged.
- Divergences: I04 (lock ordering), I12 (`wipe()` ends in `.onboarding` even if `erase` threw,
  [AppModel.swift:177-178](/home/eddy/Projects/azwall/ios/AzwallKit/Sources/AzwallKit/AppModel.swift:177) vs
  Android's `action {}` keeping `Locked`; the vault is already deleted at that point so funds are not at stake, but
  "Create a new wallet" then fails with "a vault already exists" if `vault.wipe()` was what failed).

## 7. App Store / build readiness

- **I01 (P1, CONFIRMED)** — no `PrivacyInfo.xcprivacy` anywhere in `ios/`, and `project.yml` adds none. The app
  uses `UserDefaults` ([AppContainer.swift:48,64-65,83](/home/eddy/Projects/azwall/ios/Azwall/AppContainer.swift:48),
  [BrowserTab.swift:75](/home/eddy/Projects/azwall/ios/Azwall/Browser/BrowserTab.swift:75), `@AppStorage` in
  BrowserView/SettingsView) → `NSPrivacyAccessedAPICategoryUserDefaults`, reason `CA92.1`. The static Rust core and
  Barretenberg link `stat`/`fstat`/`lstat` (any `std::fs`/`std::filesystem` use, CRS loading) →
  `NSPrivacyAccessedAPICategoryFileTimestamp`, reason `C617.1`; check the final binary for `mach_absolute_time`
  (`SystemBootTime`, `35F9.1`) and `statfs` (`DiskSpace`). Since 1 May 2024 App Store Connect **rejects** uploads
  that use required-reason APIs without a manifest (ITMS-91053). Add `ios/Azwall/PrivacyInfo.xcprivacy`
  (`NSPrivacyTracking: false`, `NSPrivacyCollectedDataTypes: []`, the API reasons) to the app target sources.
- **I02 (P1, PLAUSIBLE)** — App Review Guideline 3.1.5(b)(i): "Wallets: Apps may facilitate virtual currency storage,
  provided they are offered by developers enrolled as an organization." An individual Apple Developer account is a
  certain rejection for this app. The repository shows a personal git identity; the enrolment type is not visible
  here — confirm before building the submission.
- **I10 (P3)** — `ITSAppUsesNonExemptEncryption` is absent from
  [project.yml:72-85](/home/eddy/Projects/azwall/ios/project.yml:72): every upload will stop at the export-compliance
  question. The app uses AES-GCM/PBKDF2/SHA in the Rust core and WebCrypto; decide (standard-algorithm exemption or
  not), record it, and declare the key; a developer in France should also check the national declaration
  requirement for non-exempt encryption. No privacy-policy link on the welcome screen
  ([OnboardingView.swift:41-56](/home/eddy/Projects/azwall/ios/Azwall/Views/OnboardingView.swift:41)) while Android
  got one in `aec1510` (the Settings link exists, [SettingsView.swift:34](/home/eddy/Projects/azwall/ios/Azwall/Views/SettingsView.swift:34),
  and the URL must also go in App Store Connect). "Create a token" + "Mint" available on mainnet may draw a
  3.1.5 question from a reviewer (not an ICO, but be ready to explain or hide it on mainnet). Account deletion
  (5.1.1(v)) is satisfied by "Remove wallet from this device". Wallet creation before any content is normal for a
  wallet. Camera and Face ID usage strings are present; `NSAllowsLocalNetworking` is the right ATS exception for
  the loopback page and needs no justification; no `UIBackgroundModes` (correct, nothing runs in the background).
- Build: deployment target iOS 17.0, `SWIFT_VERSION 6`, packages at `swift-tools-version 6.4` — consistent with the
  "Xcode 27.1 / Swift 6.4" compile note; this requires the newest Xcode on the build Mac. `AzwallFFI.xcframework`
  and `AzBB_6_0_0.xcframework` are build outputs (ignored), checked by the pre-build scripts; the `AzBB` framework
  is embedded unsigned-by-link and `dlopen`ed from `privateFrameworksPath` — allowed, XcodeGen signs embedded
  frameworks. Swift 6 concurrency: I read every type crossing an actor boundary (`AppContainer @unchecked
  Sendable`, `VaultState`, `ProofGate`, `FirstOf`, `WalletStore`, `DiagnosticLog`, the `@MainActor` WebKit
  delegates, `nonisolated` handlers) and found no obvious violation in today's two uncompiled commits; the
  earlier code is known to compile. The one thing I could not settle offline is the minimum OS of
  `WKPreferences.inactiveSchedulingPolicy` against the iOS 17 / macOS 14 targets (it has compiled and run since
  `c2b4e9f`, so presumably fine).
- Memory: Chonk proving on a phone is the main jetsam risk; the Rust side caps the request (`63fc58b`), but a
  real-device proof under App Store build settings has no evidence in the repository. A kill mid-proof is safe
  (proof not released → nothing sent; `failInterrupted`).

## 8. Tests (ios/AzwallKit/Tests)

Present and valuable: vault round-trip, slot write failures, VaultState race/erase, submission/cancel/hash
retention, interrupted items, auto-lock arithmetic, bridge origin filter, bundle server traversal, Face ID gating
of spends, approvals per tab, amounts, recovery derivation, diagnostic redaction. Missing for the behaviours above:
- `SlottedVaultStorage`: death between `add(spare)` and `remove(main)` (spare newer than main) — pin that `read()`
  returns main and the next write drops the spare, and fix the comment.
- `AppContainer.forgetEarlierInstall` is untestable as written (static `hadData`, `UserDefaults.standard`): extract
  the decision (`marker`, `hadData`, `vaultExists`) → `wipe?` into AzwallKit and test the four cases of §1.
- The vault-gone case (I03): `boot()` with marker/data present and `exists() == false`.
- `AppModel.lock()` ordering (I04): a fake `RunningPxe` whose `close` never returns must still leave
  `phase == .locked` and `vaultState.current == nil` at once.
- `PxeChannel` expiry followed by a late `txSubmitting`/reply (I05): the item must keep its hash or the proof must be
  refused.
- `MainView`/`BrowserTabs` re-attach after `restart()` (I06) — needs a small view-model extraction or a UI test.
- `DappTx.applied` for `sent` without hash; `AppModel.wipe()` when `erase` throws; `BundleServer` with
  percent-encoded `..` and a directory path.
- No XCUITest target: the switcher cover, sheet exposure (I07) and Face ID flows have no automated coverage; a
  manual device checklist should exist for them.

## 9. Strong points

- Keychain design: add/remove two-slot write instead of in-place update, `userPresence` on every read, never
  synced, never restored elsewhere; `has()` without prompts; `exists()` never overwrites.
- Reinstall wipe needs two independent signals (marker and data directory), so a `UserDefaults` glitch cannot
  erase a wallet.
- Loopback server: bound to 127.0.0.1, `GET` only, `pxe/` prefix after standardisation, 404 for everything else;
  bridge accepts the PXE origin's main frame only; page loaded only after our own listener is ready.
- Browser: content script and bridge in a private `WKContentWorld`, main frame only, origin from WebKit, https-only
  dApp messaging in release, per-page ids, separate website data store from the PXE's, no custom schemes.
- Proof gate atomic; hash kept from `txSubmitting`; failed-with-hash reconciled on open; cancel UI tells the truth
  once released; review shows the full recipient and exact amount; burn/off-curve recipients refused in the host.
- Secrets: no phrase copy, local-only expiring key copy, phrase/key cleared on `.inactive`, the switcher cover on
  every pushed screen, redacted diagnostics exported only by the user, release logs private.
- Store files sealed by the PXE with the vault's key, atomic writes, excluded from backups, unreadable copies set
  aside and reported rather than overwritten.
