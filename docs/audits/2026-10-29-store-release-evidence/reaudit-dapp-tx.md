<!-- Working notes of one area of the 2026-10-29 re-audit. The consolidated, verified report is ../2026-10-29-store-release-reaudit.md; where the two differ, it prevails. -->

# Re-audit of the dApp / transaction fixes — 2026-10-29

Scope: the fixes landed since `ef3ccb5` for F1, F7, F8, F9, F13, F18, F19, F20, F21 and the P3 dApp items of
`docs/audits/2026-10-28-store-release-audit.md`. Commits read in full: 758da46, 9813055, 6514116, 96ff7bb, f931ce1,
3ee6e59, b0eb710 (iOS approval/transactions/channel parts). Method: source reading end to end (host, pinned SDK, both
apps), `pnpm --dir pxe/v6 test` (25 files, 97 tests, all pass), one temporary vitest (`pxe/host/test/zz_reaudit_tmp.test.ts`,
run, deleted, `pxe/v6` re-synced; `git status` clean). Repository untouched. CONFIRMED = traced end to end in the code (unit
tested where stated); PLAUSIBLE = a premise not settled from the code alone.

Line numbers are those of HEAD (`01267c0`).

## Verdicts

### F1 (P1) — crafted artifact of a shipped class → **closed** for the shipped classes; residual for other classes

What was done:
- `pxe/host/src/shipped.ts:16-39`: the class ids of Token, SponsoredFPC, SchnorrAccount and SchnorrInitializerlessAccount are
  computed once; `shippedArtifact(id)` returns the wallet's own artifact, `trustedArtifact(a)` swaps a site's copy for it.
- `pxe/host/src/wallet.ts:213-217` `artifactAt` prefers the shipped artifact over the PXE store for those ids, keyed on
  `originalContractClassId` (bound by the address, so a site cannot steer it with a forged preimage).
- `wallet.ts:220-226` overrides `registerContract`/`registerContractClass`: a site's artifact of a shipped class is replaced
  before it reaches the PXE, so the store never holds the crafted copy (the cold-cache overwrite in pinned
  `contract_store.js:85-101` is moot: what is written is the wallet's). `raw_call.ts:33-34` calls `wallet[method]`, so both
  dApp paths hit the overrides. `EmbeddedWallet`'s own registrations (`embedded_wallet.js:181,185,309`) use the wallet's
  artifacts.
- `token.ts:42-44` `registerToken` always registers the wallet's own artifact for a shipped class, replacing a site's.
- `gateway.ts:300-316` `#noteRegistration`/`#noteClass` return early for shipped classes (no site mark on a class the wallet
  reads itself).
- Every reader goes through `artifactAt`: `effects.ts:81` (storage slots), `calls.ts:125` (labels), `grants.ts`/`details.ts`
  through the `artifactOf` closures (`gateway.ts:216,249,261,268,286,366`), `wallet.ts:228-239` `registeredTokens`, hence
  `discover.ts:25` ("Found in your apps").

Bypasses tried, all closed for the shipped classes: `registerContractClass` (override + early return), `registerContract`
with artifact (override), a class id not listed (not shipped, see residual), a token not followed (marked by F9's fix),
discovery (reads `artifactAt`), a forged `currentContractClassId` in the preimage (display keys on `original`; the kernel
simulation checks the current class against the registry, so at worst "could not simulate"), `effects.ts` (uses
`artifactAt` only; grep finds no other `getContractArtifact`/`storageLayout` reader).

Residual (P3, PLAUSIBLE as an exploit, CONFIRMED as code behaviour): a token of a **class the wallet does not ship** (a
custom token, or a Token build from another Aztec patch) is only followable if a site registered its artifact first
(`token.ts:43-44` keeps a registered artifact; registering the shipped `TokenContractArtifact` for another class id leaves
`artifactAt` undefined, so balances and reviews would not work at all). That site artifact's `storageLayout` and parameter
names are then trusted by the review (`effects.ts:81-86`, `calls.ts:137-139`). Scenario: a dApp registers its own custom
"USDC" with crafted slots, the user follows it from "Found in your apps" (balance read through bytecode, so correct); a
later `transfer_in_public(from: user, to: attacker)` is reviewed as "No change to the balances of the tokens the wallet
knows" (`gateway.ts:248`, no mark) plus `transfer_in_public(to: <user>, from: <attacker>, …) on Token (as this site names
it) 0x…` (`calls.ts:143`). The mark is there; the "no change" line is wrong for a followed token. Suggested: when a token in
`followed` has a site-provided artifact, say "balance effect unknown (artifact provided by a site)" instead of "No change",
and never hide its transfer call.

Minor: `registerToken` keys on `currentContractClassId` (`token.ts:42-43`) while `artifactAt` keys on
`originalContractClassId`; harmless today (the node's instance is used) but inconsistent for an upgraded instance.

Tests: `shipped.test.ts` proves the premise (same class id with swapped names and bogus slots) and the helper; it does not
exercise the wiring the audit asked for (register through the gateway, follow, approve → shipped labels and balance
change). The wiring is verified by reading only.

### F7 (P2) — wait fails after the node accepted → **closed**

- `submit.ts:27-43` `Told` notes the hash from the `txSubmitting` hook (`wallet.ts:112-117`) and rethrows a failure of the
  same send with `txHash` attached; sends are serialised (`senders.ts:17-29`, error object passed through unchanged).
- `gateway.ts:344-354`: with a hash the gateway emits `sent` + hash and throws "…was sent but …: it may still be mined, do
  not send it again".
- Apps: `Dapps.kt:247-254` / `Dapps.swift:141-151` make a `failed` that carries a hash PENDING and `followActivity`
  (`Dapps.kt:189`) / `track` (`Dapps.swift:240`) launch `confirm` at once. Wallet's own path: `Transactions.kt:152-170`,
  `Transactions.swift:127-146` return PENDING with the told hash and confirm.

Regression checks: a `failed` without a hash is still FAILED; `sent` without a hash and no told hash is still CONFIRMED (the
old "cannot be followed" case). `confirm` is launched once per status event (`sent` or `failed`, never both for one send).
Tests (`DappsTest.kt:277-291`, `SubmissionTest.kt:91-110`, `FeatureTests.swift:99-114`, `SubmissionTests.swift:28-42`,
`submit.test.ts:52-71`) drive the fixed path (hash told → failure → pending → receipt → confirmed): meaningful.

Note (PLAUSIBLE, low): the gas-cache retry (`gas.ts:65-79`) can send a second transaction after the first was answered
DROPPED; `followSubmissions` keeps the first hash on the item (`Transactions.kt:256-258`, `txHash == null` only), so if the
second send then fails after submission, the wallet's own path follows the dropped first hash and ends "dropped by the
network" while the second may be mined. Needs a `/gas/` rejection plus a later post-submission failure; pre-existing.

### F8 (P2) — default `{ receipt }` result hides the hash → **closed**

`describe.ts:21` reads `receipt`; `Dapps.kt:248` / `Dapps.swift:143` never drop a stored hash; `sent` with a hash is PENDING
and settled by `confirm`. `describe.test.ts:11-12` tests the real shape; `DappsTest.kt:258-275` tests the told hash
surviving a hashless `sent` and being settled by the receipt (block 42): meaningful.

### F9 (P2) — approval spoofing → **partially closed**

- Origin: `Browsing.kt:78-92` / `Browsing.swift:117-139` `SiteName`, shown bold with the host middle-ellipsised under it
  (`ApprovalPanel.kt:92-95`, `ApprovalPanel.swift:48-51`). The heuristic (last two labels, three under `co|com|net|org|gov|
  edu|ac|or|ne|go` + 2-letter TLD) is not a public-suffix list, but it errs on the safe side: the bold text is always a
  suffix the attacker cannot choose (`aztec-wallet.github.io` shows "github.io"; `evil.me.uk` shows "me.uk"). Closed for the
  stated scenario. Cosmetic: an IPv6 literal origin (`http://[::1]:5173`) shows "[" as the domain (`Browsing.kt:84`
  `substringBefore(':')`, `Browsing.swift:129`); dev only. IDN is punycode from the web view (unchanged).
- Site strings: `calls.ts:59-62` `oneLine` strips `\p{C}` (controls, format incl. bidi overrides), U+2028/9, collapses
  whitespace, caps at 80. Applied to the call label (`calls.ts:124`), app name / manifest name (`gateway.ts:157,369`),
  simulation error (`gateway.ts:253`), symbol (`effects.ts:72`). **Not applied** to artifact-derived names of a
  site-registered class nor to capability pattern strings: see NEW-1.
- Unfollowed tokens: `effects.ts:69-78` marks "not a token you follow"; `followed` is filled on open (`api.ts:111`
  `registerTokens`) and at every balance read (`api.ts:102`), `addToken` refreshes at once (`WalletSession.kt:375-380`).
  No wallet token is shown unfollowed outside the seconds between add and refresh. Closed. The audit's "show the address in
  Found in your apps" was not done (`FoundTokens.kt:52-55` unchanged; minor).

Tests (`calls.test.ts:108-119`, `effects.test.ts:57-72`, `BrowsingTest.kt:59-69`, `SiteNameTests`) cover the fixed
strings and the listed hosts; nothing covers artifact names or IPv6.

### F13 (P2, iOS) — late proof after a timed-out call → **closed, with a regression (NEW-2)**

`Transactions.swift:161-172` and `Transactions.kt:184-195`: a send that fails before `released` cancels the gate, so the
page's late `prove` is refused (`AppContainer.swift:119`, `AndroidPxeLauncher.kt:47`) and nothing is sent behind a Failed
row. The timer still counts suspended time (`PxeChannel.swift:69-72`, `Task.sleep(for:)` on the continuous clock), so the
transfer is still failed after a long absence, but it cannot be paid twice any more. Tests
(`SubmissionTests.swift:44-57`, `SubmissionTest.kt:148-159`) assert the refused release and a working wallet retry:
meaningful for the wallet's own path, blind to the dApp path (see NEW-2).

### F18 (P3) — `profileTx` runs the prover / disarms Cancel → **partially closed**

`policy.ts:40-42` forces `skipProofGeneration: true` on every site `profileTx` (`confine` runs on every op,
`gateway.ts:215`); `policy.test.ts:22-26`. The second half of the remediation (key the gate to the item) was not done:
`ProofGate` is still one global flag on both platforms. Residual is small: only an *approved* dApp `sendTx` proof now
touches it, and on Android the proving overlay covers the approval panel while the wallet's own transaction is proven
(`WalletScreens.kt:115`), so the two cannot overlap through the UI; `track` refuses the reverse order
(`Transactions.kt:146`, `Transactions.swift:120`).

### F19 (P3) — unapproved, unbounded registrations → **not closed** (no change)

`policy.ts:10` UNSCOPED unchanged; `grants.ts:142-144` still lets registrations through; `canRegister` is display only
(`details.ts:132-137`); `getContractClassMetadata(...).isArtifactRegistered` still answers for any class; 32 MB cap unchanged
(`Dapps.kt:217`, `Dapps.swift:172`). Expected for a P3 left for later; listed for completeness.

### F20 (P3) — no activation delay → **closed**

`ApprovalPanel.kt:52-65,79-80` (`enabled = armed` after 500 ms, re-armed per `request.id`), `ApprovalPanel.swift:14,25-29`
(`.disabled(armedFor != request.id)`, `.task(id:)` cancelled with the request; the cancelled task sets the *old* id, so the
new request still waits its own 500 ms). A press begun while disabled yields no click on either toolkit. The details
sheet's buttons (`ApprovalDetailsSheet.kt:53-54`, `.swift:58-59`) are not delayed but are reachable only through a
deliberate tap on "View all details" (`ApprovalPanel.kt:113`, `.swift:69`). A long press is not a bypass (the panel is a
`Button`, not a gesture target). Connect/capabilities still need no biometric; the delay is the chosen defence.

### F21 (P3) — implicit authorizations never listed → **not closed** (no change; grep finds no listing of
`CallAuthorizationRequest`s in `pxe/host/src`).

### Auto-lock (F15/F16, Android) → **closed for the described cases; one residual**

`AppViewModel.kt:210-217` waits on `session.sending` (`WalletSession.kt:167-177`: wallet proving or a DAPP item in
PROVING), then locks; `onForeground` (`:220-226`) uses the same predicate. `AutoLockTest.kt` drives it. Residual (P3,
PLAUSIBLE): the wait is unbounded and a site controls when its item leaves PROVING: its `wait` options pass through
`confine` (`policy.ts:37-43`, `SendOptionsSchema` in pinned `wallet.js:55-63`) and `waitForTx` with `timeout: 0` never
times out (pinned `aztec.js/dest/utils/node.js:42-44`); a PXE page that dies without the `CLOSED` event leaves the item
PROVING too. The wallet then stays unlocked in the background until the user returns (the pre-fix behaviour as well, so
not a regression). A ceiling on the wait (e.g. lock after a further 20 min regardless) would bound it.

### P3 dApp items

- Fee line: **closed** (`effects.ts:131-135` `payerOf`; consistent with pinned `base_wallet.js:178-192`, where the payer
  comes from `executionPayload.feePayer`, never from `opts.fee`). `effects.test.ts:99-113`.
- `submit.ts` believes a DROPPED read 2–6 s after a lost answer: **unchanged** (`submit.ts:60-71`).
- Confirmed-at-PROPOSED never re-read: **unchanged** (`wallet.ts:83-89`, `Transactions.kt:267-272`).
- Gas-cache fast path skips implicit authwits, failure not retried: **unchanged** (`gas.ts:65-79`, `/gas/i` only).
- Explicit `createAuthWit` refused while another account's send is in flight: **unchanged** (`wallet.ts:149-152`).

## New findings

### NEW-1 — P3 — CONFIRMED (temporary vitest) — The sanitiser misses artifact-derived names and capability patterns

A site-registered class (any class the wallet does not ship) carries names the site chose: the PXE's artifact schema is
`z.string()` for contract, function and parameter names (pinned `stdlib/dest/abi/abi.js:34,40,84,120`), registration
performs no validation (pinned `pxe.js:679-690`), parameter names are not bound by the class id at all, and function and
contract names are bound only to a class the site itself deploys. `calls.ts:139` (`${p.name}: …`), `:143` (`artifact.name`)
and `:144` (`fn.name`) put them in the summary line without `oneLine`; `summary()` (`calls.ts:87-95`) caps the arguments
but never the function or contract name. The line is rendered as a bullet (`ApprovalPanel.kt:108`, `ApprovalPanel.swift:65`)
and stored as the activity summary (`gateway.ts:337`).

Probe (deleted): a verified call to a site contract whose artifact name is `Vote\n• You receive 1,000 USDC (private,
0x1234…abcd)\n` rendered as
`"vote(…) on Vote\n• You receive 1,000 USDC (private, 0x1234…abcd)\n (as this site names it) 0x222222…2222"`, also through
`otherCallLines`. Same for the capabilities panel: `gateway.ts:375` shows `String(c.type)` raw for the first request, and
`grants.ts:99-104` `addedLines` shows the manifest's `function` pattern and the contract's artifact name raw
(`"Propose transactions with transfer\n• You receive … of Vote\n• You receive …"`).

Scenario: a dApp with a real custom contract (or a phantom instance it registered, since the PXE checks nothing) proposes
`[site.vote(…), USDC.transfer_in_public(user → attacker, 100)]`; the review shows the true "You send 100 USDC" line followed
by a forged "• You receive 100 USDC" bullet inside the `vote` call line; the honest "(as this site names it)" suffix lands
several lines down. This is F9's second bullet through a path the fix did not cover.

Fix: pass `artifact.name`, `fn.name` and each `p.name` through `oneLine` in `readFresh` (and `read.contract`), and
`oneLine` the capability `type`, pattern `function` and `nameOf` results in `gateway.ts:375` and `grants.ts:99-104`. Test:
a readCall against an artifact with a line break in a parameter name must yield a single-line `line`.

### NEW-2 — P2 — CONFIRMED (by reading) — After any failed wallet send, every dApp transaction is refused as "cancelled"

Introduced by 3ee6e59 / b0eb710. `Transactions.kt:184-195` (and `Transactions.swift:161-172`): on *any* exception from
`send()` before the proof was released (`checkRecipient` throwing on a bad address, "balance too low" from the simulation,
a node error, the 20-minute timeout), the gate is cancelled and left so: `ProofGate.cancelled = true` until the next
`ProofGate.reset()`, which only `proveAndSend` (`:179`) and `windDown` (`:208`) of the wallet's own path call. The dApp
path never resets it (`Dapps.kt`, `DappApprovals.answer` → `settle()` = `winding?.join()`, nothing else), and every proof,
a dApp's included, starts with `ProofGate.check()` (`AndroidPxeLauncher.kt:47`, `AppContainer.swift:119`).

Scenario: the user mistypes a recipient (transfer fails before proving, Failed row) → opens a dApp → approves a
transaction with biometrics → the PXE's `prove` throws `CancelledByUser` → the gateway reports `failed` with "You cancelled
the transaction; nothing was sent." (`gateway.ts:349`) → the site sees an error; every retry fails the same way until the
user completes a wallet-own transaction (which resets the gate). Safe direction (nothing is sent), but a confusing,
persistent breakage of dApps, with a wrong message. Before 3ee6e59 the catch called `reset()` and this could not happen.

The new tests (`SubmissionTest.kt:148-159`, `SubmissionTests.swift:44-57`) retry through the wallet's own `send`, which
resets the gate, so they do not see it.

Fix: keep the refusal of the *late* proof without poisoning the next one, e.g. reset the gate at the start of a dApp
transaction (`followActivity` on `proving`, or an explicit `ProofGate.arm()` the prove handler calls when no wallet item is
proving), or make `check()` compare a token issued per transaction rather than a global flag (the second half of F18's
remediation). Test: wallet send fails before proving → emit a dApp `proving` → the prove handler must not throw.

### NEW-3 — P3 — PLAUSIBLE — residual of F1 for tokens of a class the wallet does not ship (detailed under F1).

### NEW-4 — P3 — PLAUSIBLE — the background auto-lock can wait for good on a dApp item a site keeps in PROVING
(detailed under "Auto-lock").

## Money leaving without informed consent: anything new?

Nothing new that lets money leave. The new code either narrows what a site can show (F1, F9) or turns "Failed" into
"Pending" (F7, F8), which only avoids a second payment. NEW-1 is the one new display path that still lets a site forge a
bullet in a signing approval, but it needs the user to approve a transaction that already shows an honest "You send" line
for a token the wallet knows.

## Test run

`pnpm --dir pxe/v6 test`: 25 files, 97 tests passed (`shipped.test.ts` included, 507 ms for the class-id premise). The
temporary probe `pxe/host/test/zz_reaudit_tmp.test.ts` passed (both gaps reproduced), was deleted, `pxe/v6` re-synced
(generated copy removed), `git status` clean.
