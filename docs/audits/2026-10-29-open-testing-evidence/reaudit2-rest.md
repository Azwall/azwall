<!-- Working notes of one area of the 2026-10-29 open-testing readiness review. The consolidated report is ../2026-10-29-open-testing-readiness.md; where the two differ, it prevails. -->

# Re-audit of the fixes for R3, R4, R5, R9, R10 and OT-03 — HEAD b72d37b

Reviewed read-only. Commits traced: 9749bd8, 9e1cd43, a16bdb6, 7c057b4, 2902145 (SiteName part only), 03c4df7.
Checks run: `pnpm exec vitest run test/calls.test.ts test/grants.test.ts test/details.test.ts` in pxe/v6 (22 pass);
`cargo test -p azwall-core network` (14 pass, `http_urls` included); one probe test from the scratchpad against
pxe/v6/src (3 assertions, all demonstrating the gap in N1). Gradle not run (release build in progress).

## Verdicts

### R3 — PARTIALLY CLOSED (names closed, decoded values still forge lines)

Closed:
- `pxe/host/src/dapp/calls.ts:139,144-148`: parameter names (`oneLine(p.name, 30)`), the contract name (`oneLine(artifact.name, 40)`,
  also what `read.contract` carries into `grants.ts:165`) and the function name (`oneLine(fn.name, 40)`) are sanitised.
- `gateway.ts:375`: capability types through `oneLine(c.type, 40)`; `grants.ts:145,151`: contract and function names of
  `addedLines` through `oneLine(…, 40)`; `gateway.ts:253`: the simulation error through `oneLine(…, 120)`.
- `canonicalArgs` (`raw_call.ts:21-27`) parses every request with `WalletSchema` before `readCall`, so `to`, `selector`,
  `caller`, `consumer`, `innerHash` are canonical hex: `short()` on them is safe.
- SiteName (`android/core/.../Browsing.kt:90-104`, `ios/AzwallKit/.../Browsing.swift:131-151`, identical logic): traced
  `a.b.github.io` → `b.github.io`; `github.io` → `github.io`; `evil.co.uk`/`www.evil.co.uk` → `evil.co.uk`;
  `x.y.evil.github.io` → `evil.github.io`; `cid.ipfs.dweb.link` whole; `github.io.evil.com` → `evil.com`;
  `EVIL.GITHUB.IO.` → `evil.github.io`; `localhost` and `[::1]` whole; `xn--` hosts shown as punycode (the safer form).
  Residual (P4): the shared-host list is hand-picked (no `*.ipfs.w3s.link`, `*.eth.link`, `*.repl.co`, `*.codesandbox.io`…);
  a tenant of an unlisted host collapses to the host, as before.

Open — see N1: decoded argument *values* were not covered. `show()` (`calls.ts:70-80`) prints struct field names
(from the artifact) and string-typed arguments (from the site) raw, and the R3 test only exercises parameter/function/
contract names.

### R4 — CLOSED

`AppViewModel.kt:163` clears `_unlockFailed` on a successful unlock; `:122` (createWallet), `:199` (startOver), `:294`
(wipe) clear it too. `cannotUnlock()` (`:182`) goes to `CannotUnlock`, whose screen (`Onboarding.kt:123-145`) says "could
not be unlocked… Try again" with "Try again" as the primary button and "Restore with my recovery phrase" as a text button
behind the "Start over?" confirmation (`:147-154`). `VaultKeyLost` alone still goes to the erased-key wording (`:156-158`).
The lock screen's "Can't unlock?" button is `enabled = !busy` (`Onboarding.kt:169`) and only shown when `failed`; an
unlock that succeeds after the user moved to `CannotUnlock` still wins (`start()` sets `Unlocked`). Test
`SignTest.kt:108-119` covers the phase sequence and the cleared flag. Residual (P4): `startOver()` from `CannotUnlock`
wipes without authentication although the key may be intact; the confirmation dialog is the only guard, which is the
design the finding asked for.

### R5 — CLOSED on Android, UNCHANGED on iOS

`AppViewModel.kt:235`: `withTimeoutOrNull(AutoLock.MAX_SEND_WAIT_MS) { session.sending.first { !it } }` then `lock()`;
`AutoLock.kt:25` = 10 min. `WalletSession.kt:180-181`: `proving != null || s.phase != Phase.FAILED && store.proving().any
{ DAPP && txHash == null }` — every account (`WalletStore.kt:157`), a hashed row does not block, a FAILED session never
blocks. The wallet's own `proving` is cleared in `finally` (`Transactions.kt:166`) and `PxeChannel.kt:102` fails every
pending call when the host closes, so a dead host cannot hold `proving`. After the bound, `lock()` → `session.close()`
kills a proof mid-way; on the next open `failInterrupted()` (`WalletStore.kt:146-151`) reports it "interrupted before the
proof was sent" (or PENDING when a hash was told) — reported in the activity, acceptable against keys staying in memory.
`AutoLockTest.kt:124-136` covers the bound.
Residual (P4, PLAUSIBLE): `sending` recomputes only on `state` changes and `recordActivity` (`WalletSession.kt:475`)
updates `_state` for the selected account only, so another account's dApp row leaving PROVING does not wake the wait;
the lock then comes at the 10-minute bound instead of at once. Bounded, so cosmetic.
iOS: `AppModel.swift:161,171` still check `session.proving == nil` only, no dApp rows, no bound, and the background task
*returns without locking* when a proof runs. Not fixed.

### R9 — CLOSED

`rust/azwall-core/src/network.rs:245-249`: the authority is cut at `/ ? #`, then any `@` refuses. Traced:
`http://localhost:8080/@x` → authority `localhost:8080` → accepted (the `@` is in the path, a parser keeps it there);
`http://[::1]@evil` → refused; `http://localhost?@evil` → authority `localhost` → accepted, and a parser sends it to
localhost with query `@evil`; `http://localhost\@evil` → refused (no split on `\`); `http://[::1]evil.com` passes the
lint but every URL parser (url crate, fetch) rejects it. Tests `network::tests::http_urls` pass.

### R10 — CLOSED

`docs/privacy.md:43-50`: the endpoint-replacement sentence is gone; the node row says account addresses are sent; the
providers' logs are said to be theirs and undeletable; `store/play/README.md:102-106` says the same and suggests "User
IDs". Nit (P4): "uninstalling the app deletes everything the wallet keeps on your phone" is deferred on iOS — the
Keychain vault survives until the next install's first launch wipes it (`ios/Azwall/AppContainer.swift:44-58`).

### OT-03 — CLOSED on Android, NOT CLOSED on iOS

Android: `Sheets.kt:122-125,140-160`: without a phrase and with the key empty the text says "a new random key… nothing
you backed up restores it" and the Create button needs the checkbox "I will back up this account's key (Settings › Show
secret key)…" — the label exists (`SettingsScreen.kt:116`). `AppViewModel.kt:271-275` enforces it (`check(importedKey !=
null || hasRecoveryPhrase || backupPromised)`), and `session.addAccount` has no other caller (`WalletScreens.kt:213` is the
only one). LifecycleTest covers the refusal.
iOS: `ios/Azwall/Views/Sheets.swift:73` still reads "Signing key to import (empty: next account of your phrase)" with no
phrase-state check and no promise; `AppModel.addAccount` has no gate; `ios/AzwallKit/.../Recovery.swift:29` returns
`Vault.newSigningKey()` for a phrase-less wallet. The exact finding of OT-03 stands on iOS.

## iOS parity (fixed on Android only)

| Finding | iOS state | Files to change |
|---|---|---|
| R3 | closed (PXE host shared; SiteName in 2902145) | — |
| R4 | not applicable (Android Keystore flag; iOS has `vaultLost`/R8) | — |
| R5 | open | `ios/AzwallKit/Sources/AzwallKit/AppModel.swift:151-172` (no dApp wait, no bound, gives up instead of locking); `WalletSession.swift` has no `isSending` |
| R9, R10 | closed (shared Rust / docs) | — |
| OT-03 | open | `ios/Azwall/Views/Sheets.swift:70-80`, `ios/AzwallKit/Sources/AzwallKit/AppModel.swift` (`addAccount`) |

## New findings

### N1 — P3 — CONFIRMED — Decoded argument values of a site's artifact still forge approval lines

`calls.ts:139` builds each argument as `${oneLine(p.name, 30)}: ${show(values?.[p.name])}`; `show()` (`:70-80`) prints a
struct as `{${k}: …}` with `k` taken from the artifact's field names (the decoder, `@aztec-labs/stdlib` `decoder.js`,
returns a plain object keyed by them) and a string-typed argument through `short()`, which keeps a string of up to 14
characters whole and never strips control characters. `summary()` (`:87-95`) only caps the length (90). Probe against
`pxe/v6/src/dapp/calls.ts` (scratchpad `probe/show_gap.test.ts`):

- struct field `x\n• You receive 1,000 USDC` → line `claim(d: {x\n• You receive 1,000 USDC: 1}) on Vault 0x222222…2222`
- `str<14>` argument `\n• Get 100 ETH` → line `pay(memo: \n• Get 100 ETH) on Vault 0x222222…2222`
- `show('a‮b')` keeps the bidi override.

Both panels render each detail as `Text("• " + line)` (`android/app/.../ui/browser/ApprovalPanel.kt:108`,
`ios/Azwall/Browser/ApprovalPanel.swift:65`) with no line limit, so the second line reads as a wallet bullet, as in the
R3 probe. Scenario: a site registers its own contract class with `claim(d: D)` where `D` has such a field, asks for
a transaction; the approval shows "• You receive 1,000 USDC: 1}) on Vault (as this site names it) 0x…" under the
site's line. Same class as R3 (a forged bullet with trailing text), unchanged severity.
Fix: in `show()`, `oneLine(k, 30)` on struct keys and `oneLine(v, 14)` instead of `short(v)` for strings (arrays and
nested structs follow); and, as a last defence, return `oneLine(line, 2 * LINE_LIMIT)` from `summary()` and run every
`details` string through `oneLine` in `#ask`. Add the two probe cases to `calls.test.ts`.

### Residuals noted while tracing (no new finding)

- `gateway.ts:349` emits `error: errorMessage(err)` raw in the `dappTx failed` event; Android shows it on the activity
  row through `friendlyError(…)` with `maxLines = 2` (`HomeParts.kt:218`). A contract's assertion text can reach it, after
  the fact; bounded display, P4.
- `grants.ts:73,124`: a capability `function` containing `|` is cut at the `|` by `k.split('|')`; harmless.
- Nothing in these commits leaks a secret: 03c4df7 keeps the typed key in a `remember` (not saveable) field as before;
  9749bd8/9e1cd43 add no logging of secrets; a16bdb6 is a lint on developer-controlled input.
