<!-- Working notes of one area of the 2026-10-28 store-release audit. The consolidated, verified report is ../2026-10-28-store-release-audit.md; where the two differ, it prevails. -->

# Android pre-release audit (secrets, WebViews, lifecycle, storage, input, build) — 2026-10-28

Reviewed revision: `ef3ccb5` (HEAD of `main`, clean tree). Read-only review of `android/app` and `android/core`
(generated code excluded), the PXE host where the Android code depends on it (`pxe/host/src`, `pxe/shared/src`), the
pinned Aztec packages' logging, and the Android release configuration. Prior audits of today
(`docs/audits/2026-10-28-*.md`) were read first; their findings are not repeated except where I checked the fix.

Automated checks run on this revision (no file in the repository was changed; `git status` clean afterwards):

| Check | Result |
| --- | --- |
| `./gradlew --quiet :core:testDebugUnitTest :app:testDebugUnitTest` | core 134 tests, app 28 tests, 0 failures |
| `./gradlew --quiet :core:lintRelease :app:lintRelease` | see "Lint" at the end |
| R8 output of the last release build (`app/build/outputs/mapping/release/`, 16:10 today) | `VaultContents`, `VaultContents$$serializer`, `AccountSecrets$$serializer` kept (renamed), kotlinx-serialization ships its own R8 rules (21 lines merged into `configuration.txt`) |

Severity: P1 keys/funds/wallet loss · P2 likely loss or leak under plausible conditions, misleading UI · P3 hardening.
CONFIRMED = established by reading the code path end to end and/or a test; PLAUSIBLE = depends on device behaviour I
could not exercise here (what is missing is stated).

## Summary

No P1. No P2 that I could confirm. The four fix commits hold (section 2). Six P3 findings, all hardening, the first
two of which I would fix before the store release because they are cheap and touch "locked out" / "paid twice" cases:

| # | Sev | Status | Finding |
| --- | --- | --- | --- |
| F1 | P3 | PLAUSIBLE | A vault whose Keystore key is *missing* (not "permanently invalidated") gets a fresh key generated silently and fails with an AES tag error on the lock screen; the KeyLost "start over" path is never offered, so the user is stuck until they reinstall |
| F2 | P3 | CONFIRMED | A dApp transaction in flight is not protected: Lock, network switch, auto-lock and the back gesture stop the engine mid-send; its `txSubmitting` hash can be missed, and the transaction is then shown as "interrupted before the proof was sent" although it may be mined |
| F3 | P3 | CONFIRMED | Auto-lock is skipped (not postponed) when the 5-minute background timer fires during a proof: the wallet then stays unlocked in the background indefinitely |
| F4 | P3 | CONFIRMED | Diagnostic-log redaction misses bare (no `0x`) hex and recovery words; the PXE console forwarder sends Aztec's warn/error lines (2000 chars) into the exportable log |
| F5 | P3 | CONFIRMED | All WebViews (hidden PXE page holding the signing keys and dbKey; dApp pages) share one renderer process and one profile; no isolation of the PXE page |
| F6 | P3 | CONFIRMED | "Remove wallet" is not transactional and leaves WebView HTTP cache/form data; the signing keys of every account live in UI state (`AccountView.signingKey`); the secret-key Copy is unprotected on Android 11–12 |

Everything else in scope checked out; the strong points are listed in section 10.

## 1. Secret display and input

Files: `app/ui/onboarding/Phrase.kt`, `Onboarding.kt`, `ui/components/Secrets.kt`, `ui/settings/SettingsScreen.kt`,
`ui/sheets/Sheets.kt`, `ui/WalletScreens.kt`, `ui/receive/ReceiveScreen.kt`.

CONFIRMED good:

- FLAG_SECURE covers every surface that shows or takes a secret: `Backup` (Phrase.kt:58), `Verify` (:80), `Import`
  (:112) via `SecureWindow()` (Secrets.kt:25–28, added on composition, cleared on dispose); the key-import field of
  `AccountsSheet` (Sheets.kt:123); `PhraseDialog` and `SecretKeyDialog` through
  `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` (SettingsScreen.kt:177, :196), which flags the
  dialog's own window. The Receive screen (address + QR) is deliberately not secured; nothing secret is on it.
- Keyboard: `Verify` (Phrase.kt:92), `Import` (:128) and the key-import field (Sheets.kt:132) use
  `KeyboardType.Password` + `autoCorrectEnabled = false`; the key-import field is additionally masked
  (`PasswordVisualTransformation`). Password input type turns off IME learning/suggestions on stock keyboards.
- Saved instance state: the phrase is `remember`, explicitly not `rememberSaveable` (Onboarding.kt:63–64); `Verify`'s
  answers (`remember(words)`), `Import`'s text (Phrase.kt:108), the revealed key and phrase in `SettingsRoute`
  (WalletScreens.kt:229–230) and the import key (Sheets.kt:121) are all `remember`. Only enums, aliases, amounts and
  recipient addresses are saveable. No secret reaches a Bundle.
- Onboarding cannot complete without the phrase having been shown and checked: `Backup` needs the checkbox, `Verify`
  needs the 3 asked words to match (`right`, Phrase.kt:97) before `onDone` → `actions.onCreate(phrase!!)`
  (Onboarding.kt:70). After process death the saved `step` may be BACKUP/VERIFY but `words == null`, so the flow falls
  back to Welcome and a *new* phrase is generated (:67–71); a vault is only ever created from a phrase that went
  through `Verify`, or from what the user typed in `Import`.
- Verification picks three distinct random positions (`indices.shuffled().take(3).sorted()`, Phrase.kt:78), compares
  trimmed lower-case input against the word at that position. Sound for its purpose (catching a copy error; the
  randomness source does not need to be cryptographic here).
- Copy to clipboard exists only for the signing key (`SecretKeyDialog` → `copySecret`, WalletScreens.kt:251): the clip
  is marked `EXTRA_IS_SENSITIVE` and cleared after 60 s if unchanged (Secrets.kt:35–46). The phrase has no copy
  button, as the design doc says. See F6(c) for the Android 11–12 caveat.
- Revealing the phrase or a key asks for biometric/credential first (`revealPhrase`, `revealKey`, AppViewModel.kt:122,
  :238; SignTest `aDeclinedRevealSaysSo`).

Observations (part of F6): `revealKey` reads the key from `session.state.value.accounts[...].signingKey`
(AppViewModel.kt:241) — the UI state flow carries the cleartext signing key of every account (`AccountView`,
WalletSession.kt:56) into every composable that collects `state`. It is never serialized or logged (I grepped every
`toString`/log site), so this is exposure surface, not a leak.

## 2. The four fix commits hold

- **4269719** (one owner of the unlocked vault): `VaultState` (core/vault/VaultState.kt) serializes `open`/`change`/
  `erase` under one mutex, publishes a save only if no lock happened meanwhile (`epoch`), and `WalletSession.contents`
  reads `vaultState.contents` every time (WalletSession.kt:339). `AccountRaceTest.retryWhileSavingDoesNotDropTheAccount`
  and `lockingAndUnlockingWhileSavingDoesNotDropTheAccount` pass. CONFIRMED.
- **68ed766** (only the upgrade keeps `.previous`; wipe waits for a save): `Vault.write()` deletes `.previous` and
  drops the legacy key on any non-upgrade write (Vault.kt:121–127), so a later read failure can no longer roll an
  imported key back (`VaultUpgradeTest.anAccountAddedAfterTheUpgradeIsNeverRolledBack`, `aRenameAfterTheUpgrade…`).
  `AppViewModel.wipe()` → `vaultState.erase(deps::wipe)` takes the mutex after `forget()`
  (VaultState.kt:47–50); `AccountRaceTest.deletingTheWalletWhileSavingLeavesNoVault` passes. CONFIRMED. I also walked
  process death at each step of `upgrade()` (copy → seal → delete previous): every intermediate state is re-opened
  correctly by `unlock()`.
- **88f866d** (no cancel after the proof is handed over; hash kept from `txSubmitting`): `ProofGate.release()` sets
  `released`, `cancel()` returns false afterwards (ProofGate.kt:24–29, :44–48); the overlay switches to "Sending…"
  and removes Cancel (ProvingOverlay.kt:53, :88); `followSubmissions` writes the hash into the store before the node
  answers (Transactions.kt:240–249), `failInterrupted` turns a PROVING item with a hash into PENDING
  (WalletStore.kt:146–151), `unsettled()` re-checks FAILED items without a receipt verdict. The PXE emits
  `txSubmitting` from the `node.sendTx` wrapper for every send, dApp sends included (`pxe/host/src/submit.ts:29–31`,
  `wallet.ts:110`, `open.ts:218`). `SubmissionTest` (5 tests) passes. CONFIRMED. Residual window: F2.
- **ef3ccb5** (screen gone stops the engine): `onCleared()` closes the session and forgets the vault
  (AppViewModel.kt:212–216); because `viewModelScope` is already cancelled when `onCleared` runs,
  `WalletSession.close()` takes the `parent.isActive == false` branch and stops the host at once
  (WalletSession.kt:249). `ScreenGoneTest` passes. The back gesture is swallowed while sending
  (ProvingOverlay.kt:56–61). CONFIRMED. Two consequences worth knowing (not findings): (1) on a back-finish the PXE
  gets no `close()`; the flush launched by `onBackground()` is cancelled with the scope and the WebView is destroyed
  right behind it, so the PXE's un-flushed debounce window is lost — SQLite's PERSIST journal keeps the database
  consistent and the PXE re-syncs that window, so this is a time cost, not a loss; (2) dApp transactions have no
  overlay, so the back gesture on the Home route or on the browser start page finishes the activity mid-proof (F2).

## 3. Findings

### F1 — P3 PLAUSIBLE — A missing Keystore key silently becomes a new key; the vault is then unreadable with no way out

Location: `core/vault/PromptedSecretBox.kt:57` (`key()`: `getKey(alias) as? SecretKey ?: generate()`), same pattern
in `KeystoreSecretBox.kt:59`; `AppViewModel.unlock()` (:131–145); `Vault.unlock()` (:69–84).

`VaultKeyLost` (→ the KeyLost screen with "Start over") is raised only from `KeyPermanentlyInvalidatedException` on
`cipher.init`. If the key entry is *absent* — Keystore entries lost by an OS update or vendor Keystore reset (a
well-known class of Android bug), or some implementations deleting auth-bound keys when the screen lock is removed
instead of invalidating them — `getKey` returns null, `open()` generates a brand-new key under the alias, `doFinal`
throws `AEADBadTagException`, and `Vault.unlock()` rethrows it (no `.previous` to fall back to). The lock screen then
shows "Tag mismatch!" (or similar) on every attempt. The Locked phase offers nothing but Unlock; the vault cannot be
removed from the app, so the only exit is uninstalling (which deletes the local history and PXE stores too) and
restoring from the phrase. Funds are not lost, but the user is wedged with a message that does not say what to do.

Missing to confirm: a device whose Keystore drops the entry (I could not exercise the Keystore on the JVM).

Fix: in `open()` (both boxes), a missing key is `VaultKeyLost`, never a reason to generate; only `seal()` of a fresh
vault may generate. Optionally let the Locked screen reach "Remove wallet" after a non-authentication failure.

### F2 — P3 CONFIRMED (narrow window) — A dApp transaction in flight is not protected from lock / switch / back / auto-lock

Locations: `AppViewModel.kt:188–198` (`onBackground`: `session.transactions.proving.value == null` — `proving` is set
only by the wallet's own `Transactions.track`, Transactions.kt:151), `:201–206` (`onForeground`, same), `:218–224`
(`lock()`, no guard), `:167–182` (`switchNetwork`/`restart`, no guard), `:212–216` (`onCleared`);
`WalletSession.close()` (:244–252) sets `running.value = null` and cancels `scope`, which ends `followSubmissions` and
`followActivity`, before the PXE's `close()`/`wallet.shutdown()` (flush + stop) has run; the JS side can still finish
a `node.sendTx` that was already past `ProofGate.release()`.

Scenario: the user approves a dApp transaction (no overlay; the drawer, the network sheet and the back gesture stay
reachable), then taps "Lock wallet" or picks another network (or presses back on the Home route / browser start page,
which finishes the activity), during the second or two between the proof being handed over and the node's answer. The
`txSubmitting` and `dappTx sent` events arrive after `running.value = null`/`scope.cancel()` and are dropped
(`pxeEvents` is `flatMapLatest` over `running`, WalletSession.kt:169). At the next open, `failInterrupted()` marks the
DAPP item FAILED "interrupted before the proof was sent" (WalletStore.kt:149) although the node may have it. The user
repeats the dApp action and pays twice. The wallet's own transactions are protected by the overlay (UI), but
`onCleared()` is not guarded for them either (the system finishing the activity, swipe from Recents); there the hash is
usually already in the store because `recordActivity` is synchronous on `txSubmitting`, so the exposure is the same
narrow window.

Fix: treat a DAPP item in PROVING in the store (`store.proving()`) like `proving != null` in `onBackground`/
`onForeground`; refuse or defer `lock()`/`switchNetwork()` while anything is proving (show the same "sending" state);
in `close()`, keep collecting `txSubmitting`/`dappTx` until `client.close()` returned (or move the hash write to
the PXE side: write the hash into the wallet store before `sendTx`, as A03 recommended). Add a `WalletSessionTest`
that closes the session between `release()` and `txSubmitting` and asserts the item is PENDING after reopen.

### F3 — P3 CONFIRMED — Background auto-lock is skipped, not postponed, while proving

Location: `AppViewModel.kt:193–197`. The 5-minute job runs once; if a wallet transaction is proving at that moment the
lock is skipped and nothing reschedules it. `onForeground` locks only when the app comes back ≥ 5 min later and is not
proving. A proof started just before backgrounding keeps the keys (vault contents, PXE JS heap) in memory for as long as
the process lives in the background. `LifecycleTest` covers the plain timer only.

Fix: re-arm the job (poll until `proving == null`, then lock), or lock as soon as the proving job ends while
backgrounded.

### F4 — P3 CONFIRMED — Redaction gaps in the diagnostic log and the PXE console forwarder

Locations: `core/DiagnosticLog.kt:87–92` (`HEX` requires the `0x` prefix and ≥ 38 hex chars; `URL` cut to host; no
word-list filter); `core/pxe/PxeHost.kt:107–118` (Aztec warn/error lines, up to 2000 chars each from
`pxe/host/src/log.ts:40`, are recorded in the exportable log and written redacted to logcat); `AppViewModel.kt:154`,
`WalletStore.kt:187` (exception messages recorded verbatim apart from `redact`).

What I verified: nothing in the Android code logs a key, the phrase or the dbKey; the vault's JSON decode errors (which
kotlinx prefixes with a `JSON input:` snippet of the *plaintext*) only reach the snackbar, and `Errors.friendly` keeps
the first line (Errors.kt:17–19), so the snippet is cut; the PXE's `errorMessage` strips URL paths
(`pxe/shared/src/bridge.ts:30–40`); a grep of the pinned `@aztec-labs/*` `dest/` for log statements mentioning
`secret`/`signingKey`/`secretKey`/`sk` found only "shared secret for recipient …" lines, whose only payload is an
address (redacted by `HEX`). Console copies to logcat are suppressed in release (`onConsoleMessage` returns
`!verbose`, PxeHost.kt:98–100); `Log.i` of info lines is debug-only.

Gaps: a key printed without `0x` (e.g. `Buffer.toString('hex')`, base64) or recovery words in any future error text
would reach the exportable log unredacted; the `store` decode error includes a snippet of `wallet-<network>.json`
(addresses are redacted, token names/amounts are not). Cheap hardening: redact bare 64-hex and ≥ 40-char base64 runs;
drop any line containing ≥ 12 consecutive BIP-39 words; cap exception messages at the first line in `report()`.

### F5 — P3 CONFIRMED — The PXE page (keys in its JS heap) shares renderer process and profile with dApp pages

Locations: `core/pxe/PxeHost.kt:86–104` (hidden WebView, default profile, `AzwallAndroid` JavascriptInterface only
here); `app/ui/browser/BrowserTab.kt:163–203` (dApp WebViews, no JavascriptInterface, `addWebMessageListener`
`azwallBridge` + document-start script on `"*"`, native-side origin filter `BrowserTabs.allowedOrigin`).

Checked and fine: `AzwallAndroid` is only on the PXE WebView (a page in the browser cannot reach it);
`WebViewAssetLoader` only exists on the PXE WebView, and the browser refuses main-frame and sub-frame navigations to
`appassets.androidplatform.net` (`shouldOverrideUrlLoading`, BrowserTab.kt:193–194; typed addresses, :91) so a dApp
cannot get a page on the PXE's origin (the real domain is Google-reserved and does not resolve; user CAs are not
trusted: `network_security_config` without `trust-anchors` on API 30+); the PXE page cannot navigate away
(`shouldOverrideUrlLoading` always true, PxeHost.kt:129); settings on both: `allowFileAccess=false`,
`allowContentAccess=false`, mixed content never (browser; default never on the PXE for targetSdk ≥ 21), no
`setSupportMultipleWindows`, no download listener, no file chooser, default `onPermissionRequest`/geolocation (denied),
Safe Browsing default on, third-party cookies default off, `onRenderProcessGone` on both, only `http(s)` leaves the
browser (no `intent:`, `javascript:`, `file:`, `content:`; `http` is dead in release by `cleartextTrafficPermitted=false`,
debug allows `localhost`/`127.0.0.1` only). `Browsing.addressOf` prefixes `https://` to anything with a dot, so
`file:`/`data:`/`about:` typed in the bar become unloadable URLs.

Remaining: all WebViews of one Android app process run in one renderer process, and WebView does not isolate sites
inside it. A renderer exploit from a malicious dApp page therefore sees the PXE page's heap (signing keys, dbKey,
decrypted notes) while the wallet is unlocked. `WebViewCompat.setProfile` would separate storage/cookies, not the
process. The real mitigation is to host the PXE WebView in a separate Android process (`:pxe` service) so it gets its
own renderer, or to not browse untrusted sites in the same process as the keys. Document the limitation until then
(the privacy policy covers data flows, not this).

### F6 — P3 CONFIRMED — Smaller hardening items

(a) `AppContainer.wipe()` (AppContainer.kt:68–78) is not transactional: a process death after `vault.wipe()` leaves
the Keystore key and `wallet/wallet-<network>.json` (addresses, counterparties, amounts in clear) on disk; the next
wallet's `WalletStore` loads the old tokens list from it. Also not cleared: WebView HTTP cache and form/auth data
(`WebView.clearCache`, `WebViewDatabase`), which say which dApps were used. `WebStorage.deleteAllData()` is posted to
the main thread while the PXE's `close()` (flush to OPFS) may still be running (`lock()` is called first but closes
asynchronously, AppViewModel.kt:246–248); a store re-created after the delete is ciphertext under the deleted dbKey
and is discarded at the next open by `startOverOnOtherKey` (`pxe/host/src/open.ts:124–135`), which keys the OPFS
names on a generation counter in `localStorage` (`generation.ts`; values are counters and store names derived from
chain id/rollup address, nothing personal). Whether `WebStorage.deleteAllData()` removes OPFS on current WebView
builds is PLAUSIBLE only — verify once on a device (`app_webview/Default/File System/`).

(b) Signing keys in UI state (section 1): `revealKey` could read the vault (`vaultState.contents`) after `auth()`
instead, and `AccountView` could carry the `keyHash` that `Snapshots` already derives.

(c) Secret-key Copy on Android 11–12 (API 30–32): `EXTRA_IS_SENSITIVE` is honoured from 13; before that the keyboard's
clipboard history and the foreground app can read it for up to 60 s. Accepted trade-off in the design; consider
hiding Copy below API 33 or saying so in the dialog.

(d) Keys are never zeroed: Kotlin `String`s (vault contents, `AccountView.signingKey`) and the JS heap are only
dereferenced on lock (`VaultState.forget`, `WebView.destroy`). Inherent to the platform; noted for the threat model.

## 4. Logging

Covered under F4. Every `Log.*` call in the app is in `PxeHost.logEvent` (redacted warn/error; info only when
debuggable). `DiagnosticLog.record` redacts before writing (DiagnosticLog.kt:38); `report()` ignores cancellations;
500 lines, repeats folded. The export goes through an `ACTION_SEND` chooser the user picks (DiagnosticsSection.kt:28–34).
RPC URLs: the registry's `rpcHost` is host-only (WalletBackend.kt:66–68); `checkReleaseInputs` refuses a release with a
`.env` endpoint (build.gradle.kts:144–160), so no API key is in the APK's `azwall-config.json`.

## 5. Lifecycle and locking

CONFIRMED: auto-lock after 5 min in background without waiting for foreground (`LifecycleTest`); `lock()` closes the
session (PXE `close` → `wallet.shutdown()` = flush + stop, then `WebView.destroy()`, PxeHost.kt:48–52, so the in-memory
account DB and the JS heap go with the renderer), rejects pending dApp requests and forgets the vault
(AppViewModel.kt:218–224). `onBackground` flushes the PXE stores (WalletSession.kt:239–241) and the PXE flushes itself
on `visibilitychange` (`pxe/host/src/main.ts:29–32`). Process death during a transaction: hash already in the store
from `txSubmitting` → PENDING, else FAILED "interrupted" (correct: the proof died with the process). Process death
during onboarding: either no vault (new phrase next time) or a complete vault (`create` writes once). Process death
during the vault upgrade: safe at every step (section 2). `PromptAuthorizer` blocks an IO thread under the
`VaultState` mutex while the prompt is up; the prompt is dismissed by the system when the activity stops, releasing
it. Gaps: F2, F3.

## 6. Storage

CONFIRMED: `allowBackup=false`, `dataExtractionRules` exclude everything for cloud backup and device transfer; vault in
`filesDir/vault.bin` sealed by AES-256-GCM with a Keystore key that needs biometric/credential per operation
(`PromptedSecretBox`, timeout 0, bound through `CryptoObject`); `wallet-<network>.json` and `diagnostics.log` in
`filesDir/wallet` (plaintext metadata; disclosed in the policy per re-audit 2); snapshots keyed by SHA-256 of the
signing key (Snapshot.kt:25–26) — a hash of a 254-bit random key, not reversible, though it does let two installs be
linked if both files leak; the PXE stores are SQLite-OPFS encrypted with the vault's dbKey. Atomic writes everywhere
(`Vault.seal`, `WalletStore.save`). `network_security_config`: HTTPS only, system CAs only; debug adds cleartext
`localhost`/`127.0.0.1`. Gaps: F6(a).

## 7. Input parsing

CONFIRMED good: `Amounts.parse` refuses the ambiguous `1,000` and anything but `\d+(\.\d*)?`, caps decimals at the
token's, uses BigDecimal/BigInteger (no overflow), rejects zero (`AmountsAndStoreTest`); `Max` strips grouping before
parsing. `PaymentRequest.parse` accepts a bare `0x`+64-hex address or `aztec:<address>?network&token&amount`; the
token is matched by address against the *followed* list only, an amount for an unknown token is dropped and a note
shown, a network mismatch is shown (`readScan`, TransferScreen.kt:135–146; `ScanTest`, `PaymentRequestTest`). The
review sheet shows the exact amount (all decimals) and the recipient in full, selectable, in a monospace font
(`TransferReview` → `Review.transfer` → `DetailRow`), and nothing is sent before Confirm + biometric (`vm.sign`,
WalletScreens.kt:147; `SignTest.aDeclinedFingerprintSendsNothing`). A lookalike address can only pass if the user does
not compare the full string, which is the state of the art. `Browsing.addressOf`: typed `localhost` gets `http://`
even in release, where the cleartext policy kills it; dApp bridging over http is refused in release
(`allowedOrigin`, `BrowserRulesTest`). QR scanning is local (ZXing), camera asked only on scan. `Fees.line` says
"Known once the wallet has reached the network" before `check` exists and `track()` refuses to send on that
(Transactions.kt:144).

## 8. Build and release configuration

CONFIRMED: release `isMinifyEnabled` with `proguard-android-optimize.txt`, `core/consumer-rules.pro` keeps JNA and the
uniffi bindings; kotlinx-serialization's own R8 rules are merged; the last release mapping keeps every
`$$serializer` the vault and store need and `Vault` calls `VaultContents.serializer()` explicitly (no reflection) —
a vault written before an update stays readable after it. (`Vault` uses `ignoreUnknownKeys = false`: a *downgrade*
after a future vault field is added would refuse the vault; Play never downgrades.) `debuggable` false in release;
no `usesCleartextTraffic` (default off for targetSdk 37) plus the config above; `extractNativeLibs=true` +
`useLegacyPackaging` because Barretenberg is `dlopen`ed by path; 16 KB alignment gate and arm64-only packaging in
`checkReleaseInputs`; `versionCode = major*10000 + minor*100 + patch` (monotonic while minor/patch < 100). Staging
build: `applicationIdSuffix = ".staging"` → different UID → its own AndroidKeyStore namespace, so the shared aliases
`azwall.vault` / `azwall.vault.v2` cannot collide with the release app — CONFIRMED by design of the Keystore (per-UID).
minSdk 30 API use: lint (below); `setUserAuthenticationParameters(0, …DEVICE_CREDENTIAL)` and
`BIOMETRIC_STRONG|DEVICE_CREDENTIAL` with a `CryptoObject` are API 30 features, matching minSdk.

## 9. Tests

Present and relevant: `AccountRaceTest` (retry/lock/wipe during a save), `VaultUpgradeTest`, `VaultTest`,
`SubmissionTest` (hash kept, no cancel after release), `LifecycleTest` (auto-lock timer, retry), `ScreenGoneTest`,
`SignTest` (declined auth sends nothing; only TRANSACTION/AUTHWIT sign), `DappsTest` (approval needs the owner,
rejections, per-tab), `BrowserRulesTest`, `DiagnosticLogTest` (redaction), `AmountsAndStoreTest`,
`PaymentRequestTest`, `ScanTest`, `BrowsingTest`, `UnreadableStoreTest`, `WalletSessionTest` (66 cases),
`PxeChannelTest`, `ManifestTest`.

Without a test (JVM-testable unless noted):

- F2: close/lock/switch between proof release and `txSubmitting` on a DAPP item; `onBackground` with a DAPP item
  proving.
- F3: the timer firing while proving, then the proof ending in the background.
- F1: missing Keystore key → `VaultKeyLost` (instrumented only).
- `Verify`'s three-word check and the `OnboardingFlow` process-death fallback (Compose; a pure function for the
  check would make it testable).
- `redact` for bare hex / recovery words; `Errors.friendly` cutting the kotlinx `JSON input:` line.
- `BrowserTab.shouldOverrideUrlLoading` scheme/host filtering and the PXE/browser WebView settings (Robolectric or
  instrumented; today only the pure `BrowserTabs` helpers are tested).
- `wipe()` clearing WebView storage incl. OPFS (instrumented).
- `WalletStore.failInterrupted` for DAPP items specifically (the generic case is tested).

## 10. Strong points

- One owner of the unlocked vault with an epoch, atomic file replacement everywhere, and the upgrade path that never
  leaves the wallet without a readable vault — all with regression tests from today's audits.
- Every signing path (wallet transactions, dApp TRANSACTION/AUTHWIT) is gated by BiometricPrompt, and the vault key
  itself is bound per operation through `CryptoObject`.
- Secrets are kept out of screenshots, the Bundle and the keyboard on every screen that handles them; the phrase has
  no copy button; the diagnostic log never leaves the device unless exported and is redacted at write time.
- The dApp bridge takes the origin from the WebView, not the page; a session answers only the page that opened it;
  sites are confined to the account they were granted; sends are serialized so an authorization can only be for the
  sender (`SenderGuard`).
- Both WebViews are locked down (file/content access off, schemes filtered, renderer death handled, HTTPS only).
- No backup, no analytics, no crash service; the release build refuses a private RPC key in the bundle.

## Lint

`./gradlew --quiet :core:lintRelease :app:lintRelease`: see the end of this file (appended after the run).

Result: exit 0. core: 0 errors, 1 warning (`MissingOnRenderProcessGone`, the known false positive on `AssetClient`);
app: 0 errors, 17 warnings (launcher icon shape/monochrome ×6, `UseKtx` ×5, dependency/AGP version notices ×3,
`NotShrinkingResources`, `DataExtractionRules`, `ObsoleteSdkInt`). None touches the scope above; no NewApi error
against minSdk 30.
The `DataExtractionRules` warning (rules apply from API 31; API 30 reads `fullBackupContent`) is moot here:
`android:allowBackup="false"` disables backup and transfer on every supported API on its own.

Tree check after the run: `git status --short` prints nothing (no file in the repository was created or changed).
