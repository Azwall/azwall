<!-- Working notes of one area of the 2026-10-28 store-release audit. The consolidated, verified report is ../2026-10-28-store-release-audit.md; where the two differ, it prevails. -->

# Azwall pre-release audit — dApp gateway, transaction path, approval UIs

Date: 2026-10-28. Reviewed revision: `ef3ccb5` (working tree clean). Scope: "can money leave the wallet without the
user's informed consent, or can a dApp/page/node do something the user did not approve?" — `pxe/host/src/dapp/*`,
`pxe/host/src/{wallet,token,submit,gas,fees,fee_juice,api,discover,history,note_owners,senders,open}.ts`, the
pinned Aztec packages they rely on (`pxe/v6/node_modules/@aztec-labs/*`), the Android and iOS browser/approval
code, and transaction state on both apps.

Method: source reading end to end (host, pinned SDK, native), one temporary vitest (`pxe/host/test/zz_audit_tmp.test.ts`,
run, then deleted; `git status` clean afterwards) to confirm finding 1's two technical premises and finding 3's
premise. No device, no live network, no real keys. CONFIRMED = the path was traced end to end in the code (and unit
tested where stated); PLAUSIBLE = a premise could not be settled from the code alone, stated with each one.

Prior findings (`docs/audits/2026-10-28-independent-audit.md`) are **not** repeated unless still open; their fixes
were re-checked first (section "Prior findings").

## Summary

| # | Sev | Status | Finding |
|---|-----|--------|---------|
| 1 | P1 | CONFIRMED (unit) | A site can plant a crafted artifact for the **standard Token class** (same class id, swapped parameter labels, bogus storage layout); the wallet then keeps it for every real token it follows later, and a transfer *from* the user shows as a transfer *to* the user with "No change to the balances…" |
| 2 | P2 | CONFIRMED | A dApp transaction whose `wait` fails after the node accepted it (timeout, site-chosen `wait.timeout`) is shown **Failed** with its hash until the next unlock; the user is led to send it again |
| 3 | P2 | CONFIRMED (unit) | `txHashOf` misses the hash of the default dApp `sendTx` result (`{ receipt }`): the activity row is marked **Confirmed on a proposed block and its hash is erased** |
| 4 | P2 | CONFIRMED | The approval panel ellipsizes long origins at the end (2 lines): a phishing host `app.aztec.network.<60 chars>.evil.io` reads as `app.aztec.network…` |
| 5 | P2 | CONFIRMED | Site-controlled strings (call `name`, `appName`, manifest name, simulation error text) reach the panel unfiltered: newlines forge extra "•" lines, lengths are unbounded |
| 6 | P2 | CONFIRMED | Balance lines show a site-registered token's symbol without the "(as this site names it)" mark that calls get: a fake "USDC" minted to the user makes "You send 100 USDC" look like a round trip |
| 7 | P3 | CONFIRMED | `profileTx` with `skipProofGeneration: false` runs the native prover without approval and flips `ProofGate.released`, which disables the user's Cancel on their own transaction |
| 8 | P3 | CONFIRMED | Unapproved, unbounded `registerContract` / `registerContractClass` / `registerSender` by a connected site (the "contracts" capability is shown but not enforced); `getContractClassMetadata` fingerprints the wallet |
| 9 | P3 | CONFIRMED | Approval buttons accept a tap the instant the panel appears (no activation delay); connect/capabilities/verify have no biometric step, so a timed request can be approved by a tap meant for the page |
| 10 | P3 | PLAUSIBLE | Effects shown come from a run **without** the authorizations `EmbeddedWallet.sendTx` then signs; those implicit authorizations are never listed |
| 11 | P3 | CONFIRMED | Fee line says "paid from your account" whenever a site sets `gasSettings` on a sponsored network, although the sponsor pays; the details sheet then has no "Fee paid by" row |
| 12 | P3 | CONFIRMED | Reliability: explicit site `createAuthWit` refused while another account's send is in flight; gas-cache fast path skips implicit authwits and the resulting failure is not retried; `submit.ts` believes a DROPPED read 2–6 s after a lost answer (the apps wait 15 min); confirmed at PROPOSED never re-checked; auto-lock ignores a dApp proof in progress |

## Prior findings: are the fixes closed?

- **A02 (concurrent sends broaden authwit)** — closed. `SenderGuard.sending` (`pxe/host/src/senders.ts:17-29`) queues every `sendTx`
  (`wallet.ts:126-128`, also reached by `deployAccount`, `deployToken`, `mint`, `claimFeeJuice` through aztec.js `send()`), and
  `createAuthWit` (`wallet.ts:130-133`) refuses any `onBehalfOf` other than the one sender; a `NO_FROM` send refuses all.
  `dapp_safety.test.ts:74-108` covers the queued case. No other signing path exists for a site: `simulateTx`/`profileTx`/`executeUtility`
  never sign, and explicit `createAuthWit` is confined to the session's account (`policy.ts:41-43`).
- **A05 (balance change hides other calls)** — closed. `otherCallLines` (`calls.ts:145-163`) hides only a *verified* call whose
  name is in `BALANCE_FUNCTIONS` to a token already in the changes; unverified calls and `set_admin`-like calls stay. See finding 1 for
  how "verified" can be subverted upstream.
- **A03/A06 (uncertain submission, cancel after broadcast)** — closed as described: `confirmSubmissions` (`submit.ts:26-51`) tells the
  hash before sending (`txSubmitting`), the apps record it on the proving item (`Transactions.kt:240-249`, `WalletSession.swift:305-316`),
  `failInterrupted` turns a hashed PROVING item into PENDING (`WalletStore.kt:146-151`), a send nobody can answer for returns
  normally, and the overlay switches to "Sending to the network…" without Cancel once the proof is released
  (`ProvingOverlay.kt:53-90`, `ReceiveView.swift:66-74`). What remains is the in-session gap of findings 2 and 3.

## Findings

### 1. P1 — A crafted artifact of the standard Token class changes how every later real token is displayed

**CONFIRMED** (class-id equality and the resulting label swap unit-tested; store semantics by reading the pinned PXE).

Locations:
- `pxe/host/src/dapp/gateway.ts:292-308` — `registerContract` runs `#noteRegistration` (marks the *address* only) then
  `rawCall('registerContract', [instance, artifact])`; the class of an artifact passed this way is never marked (`#noteClass` only runs
  for `registerContractClass`, `:310-315`). Both methods are `UNSCOPED` (`policy.ts:10`) and need no approval (`grants.ts:142-144`).
- `@aztec-labs/wallet-sdk/dest/base-wallet/base_wallet.js:255-262` → `@aztec-labs/pxe/dest/pxe.js:661-690` — "registration performs no
  validation"; `contract_store.js:85-101` `addContractArtifact` keys by class id, returns early only on an **in-memory cache hit**,
  otherwise overwrites the stored artifact.
- `@aztec-labs/stdlib/dest/contract/artifact_hash.js` — the class id binds function selectors (name + parameter *types*), bytecode,
  return types and `{name, outputs}`; it does **not** bind `parameters[].name` nor the top-level `storageLayout` (a separate schema
  field, `stdlib/dest/abi/abi.js:172,187`).
- `pxe/host/src/token.ts:35-43` — `registerToken` registers the wallet's own `TokenContractArtifact` **only if no artifact is
  registered for the class yet**; a site's artifact for that class id is kept for good (it is persisted in the encrypted store).
- `pxe/host/src/wallet.ts:193-196` `artifactAt` reads that artifact; `calls.ts:112-138` labels decoded arguments with
  `fn.parameters[i].name` (`:128-130`); `effects.ts:73-80` reads `storageLayout.balances` / `public_balances` to find balance changes.
- Both shipped networks start with no token (`networks.json`: `tokens: []`), so the Token class artifact is first written by whoever
  registers it first — a site, or the user following/discovering a token.

Scenario (input → wrong outcome):
1. A fresh wallet; the user opens any site that registers a standard Token instance (a normal thing for a dApp to do) with a
   crafted copy of the Token artifact: `transfer_in_public`/`transfer_in_private`/`transfer` parameters renamed (`from`↔`to`),
   `storageLayout.balances.slot` and `public_balances.slot` pointing nowhere. Same class id (tested), accepted without approval,
   persisted. Nothing is marked as the site's (class not marked; only the site's own instance address is).
2. Later the user follows the real USDC-like token (Add token or "Found in your apps"): `registerToken` sees the class artifact
   registered and keeps the crafted one.
3. Any site (this one or another) proposes `sendTx` with `USDC.transfer_in_public(from: user, to: attacker, 1000, 0)`.
   `simulateEffects` finds no note at the crafted slots → `changes` empty → the panel reads:
   `• No change to the balances of the tokens the wallet knows` /
   `• transfer_in_public(to: 0x000000…00aa, from: 0x000000…00bb, amount: 1000, authwit_nonce: 0) on Token 0xusdc…` (the `to:` label
   carries the user's own address; verified by the temporary test's regex `transfer_in_public\(to: 0x0+…00aa, from: 0x0+…00bb`) /
   `• Fee: … paid by the network's sponsor`.
4. The user approves ("Approve and prove"), confirms with biometrics; the real transfer runs. The same applies to `transfer_in_private`
   through an authorization request and to a site's explicit `createAuthWit` review.

Why the existing defences do not catch it: the label-vs-selector check compares the site's label with the artifact's function *name*
(unchanged); `looksLikeToken` keys on names; `otherCallLines`'s "verified" test is satisfied by the crafted artifact; the provenance
mark is address-based and only set on the `registerContractClass` path. The wallet's own send review (native) is unaffected — the
PXE executes the same bytecode — so balances and the wallet's own transfers stay correct, which makes the planted artifact invisible
until a dApp approval.

Remediation:
- Never let a site's artifact stand for a class the wallet ships (Token, SponsoredFPC, account classes): compute the shipped class ids once,
  and in `artifactAt` (or `registerToken`) prefer the shipped artifact for those ids; refuse (or at least mark and log) a site's
  `registerContractClass` / `registerContract(…, artifact)` whose class id is one of them.
- For any class already in the store, make the site's registration a no-op: call `getContractClassMetadata(id)` **before** `rawCall`
  on the `registerContract` path too (this also warms the PXE cache, which is the only thing that prevents the store overwrite), and mark
  the class as site-registered on that path exactly as `#noteClass` does.
- Derive display labels and storage slots only from artifacts the wallet trusts; show "(as this site names it)" on the balance lines too
  (finding 6).
- Regression test: register a same-id artifact with swapped names through the gateway, follow the token, approve a transfer: the review
  must show the shipped labels and the balance change.

### 2. P2 — A dApp transaction accepted by the node but whose `wait` fails is shown Failed until the next unlock

**CONFIRMED** by reading.

Locations: `pxe/host/src/dapp/gateway.ts:330-346` (`#send`: any throw from `rawCall('sendTx')` → `dappTx failed`),
`@aztec-labs/wallet-sdk/dest/base-wallet/base_wallet.js:368-415` (after `aztecNode.sendTx`, `waitForTx` with the site's `wait`
options; `DefaultWaitOpts.timeout` is 300 s, `aztec.js/dest/contract/wait_opts.js`), `android/core/.../wallet/Dapps.kt:243-247`
(`"failed"` → `FAILED`, hash kept) and `:189` (`confirm` launched for PENDING only), `ios/AzwallKit/.../Dapps.swift:138-147,236`;
reconciliation of FAILED-with-hash items only in `resume()` at open (`Transactions.kt:252-254`, `WalletStore.kt:137-140`).

Scenario: a dApp calls `sendTx` with the default `wait` (it waits for PROPOSED) and a slow testnet, or deliberately passes
`wait: { timeout: 1 }`. The node accepted the transaction (`txSubmitting` recorded its hash); `waitForTx` throws → the gateway emits
`failed` → the activity shows **Failed** (hash present, error "Timeout awaiting isMined…"); the site is told the call failed and asks
the user to retry; the user approves a second, identical transaction. Both are mined: paid/sent twice. The same in-session gap exists on
the wallet's own path when the native `transfer` call times out (20 min, `PxeChannel.kt:136`) after the node accepted.

Remediation: split "failed before the node had it" from "sent, outcome unknown": once `txSubmitting` fired for the item (or `#send`
catches an error from the wait phase), emit/record **PENDING** and `confirm` it, never FAILED; in the apps, any FAILED item that carries a
hash and no final receipt error should be followed at once, not only at the next open. Test: a fake node that accepts and a `wait`
that times out must leave the item pending and then confirmed.

### 3. P2 — The default dApp `sendTx` result hides its hash from the gateway: Confirmed at "proposed", hash erased

**CONFIRMED** (unit-tested: `txHashOf({ receipt: { txHash } })` → `undefined`).

Locations: `pxe/host/src/dapp/describe.ts:14-22` (looks at `txHash` and `result` only), `@aztec-labs/aztec.js/dest/wallet/wallet.js:365-378`
(the `sendTx` output is `{ txHash } | { receipt }`; `receipt` is the shape when `wait` is not `NO_WAIT`, i.e. the aztec.js default for
`contract.methods.x().send({from})`), `Dapps.kt:243-245` and `Dapps.swift:142` (`"sent"` without a hash → **CONFIRMED**, and
`txHash = txHash` overwrites the hash `followSubmissions` had stored with `null`/`nil`).

Scenario: any dApp using the SDK defaults. After the wait returns (status PROPOSED — a block the SDK itself documents as re-orgable), the
gateway emits `sent` without a hash; the activity row becomes Confirmed with no hash: no explorer link, no reconciliation, and a transaction
dropped by a proposer failure stays "Confirmed" forever. `describe.test.ts` tests `{txHash, blockNumber}` (not the real `{receipt}` shape).

Remediation: read `o.receipt` in `txHashOf` (or, better, take the hash from the `submitting` hook, which the gateway already has
through `txSubmitting`), never erase a stored hash, and record `sent` as PENDING so `confirm` settles it from a receipt.

### 4. P2 — The origin shown in the panel is cut at the end: the registrable domain can be pushed out of view

**CONFIRMED** by reading.

Locations: `android/app/.../browser/ApprovalPanel.kt:77` (`siteOf(origin)`, `maxLines = 2`, `TextOverflow.Ellipsis`),
`ios/Azwall/Browser/ApprovalPanel.swift:39` (`.lineLimit(2)`, tail truncation), `Format.kt:17` / `Format.swift:12` (`siteOf` only strips
`https://`). The origin itself is taken from the web view (good), but hostnames may be 253 characters with 63-character labels.

Scenario: a phishing dApp at `https://app.aztec.network.wallet-session-verify-9f3a7b2c1d4e5f6a7b8c9d.evil-host.io` connects; the
panel header (two lines of title text on a phone) reads `app.aztec.network.wallet-session-verify-9f3a7b2c1d4e5f…`; the address bar shows
the same beginning. The user "connects to app.aztec.network" and approves its capabilities and transactions under that belief. (Punycode
is not an issue: the native origin is ASCII, so homographs show as `xn--…`.)

Remediation: show the registrable domain (eTLD+1, or at least the last two labels) first and in bold, middle-ellipsize the rest, and
keep the whole origin in the details sheet (already the case).

### 5. P2 — Site-controlled strings are rendered verbatim in the approval summary

**CONFIRMED** by reading.

Locations: `gateway.ts:156` (`It calls itself "${d.appName ?? d.appId}"`), `:360` (manifest name), `:252` (simulation error text, 120
chars but site-influenced through a contract's assertion message), `calls.ts:114-122` (an unverified call's `name` label, unbounded;
`FunctionCall.schema` is `name: z.string()`, `stdlib/dest/abi/function_call.js:42`), `grants.ts:97-108` (`addedLines` with the site's
function pattern strings and the site's artifact names), rendered as `• $it` (`ApprovalPanel.kt:90`, `ApprovalPanel.swift:53`); neither
`Dapps.kt` nor `Dapps.swift` filters control characters or lengths.

Scenario: a `sendTx` to an unverified contract with `calls[0].name = "claim\n• You receive 1,000 USDC (private, 0x1234…abcd)\n• Fee: 0,
paid by the network's sponsor\n"` renders as three bullets, the forged ones indistinguishable from the wallet's, with the honest
`on 0x… (unverified…)` suffix several lines below (and below the 280 dp fold after enough padding). The connect prompt can likewise be
made to show fake emoji rows or "Verified" claims inside the app name.

Remediation: in the gateway, normalise every site string before it reaches a panel line: strip C0/C1 controls and line breaks,
collapse whitespace, cap at ~80 characters with an ellipsis; quote it and keep it on one line. Keep the raw text only in the details sheet.

### 6. P2 — Balance lines do not carry the "as this site names it" mark

**CONFIRMED** by reading.

Locations: `effects.ts:64-71` (`changeLines`: symbol + 8/4 characters of the address, no provenance), `effects.ts:157-164` (symbol from
`tokenInfo`, i.e. the token's on-chain `public_get_symbol`), `wallet.ts:199-210` (`registeredTokens` includes every token a site
registered), versus `calls.ts:134` where calls are marked through `isSiteRegistered`. The "Found in your apps" list shows name and symbol
without the address (`FoundTokens.kt:55`).

Scenario: a site deploys a token whose symbol is `USDC`, registers it (unapproved) and proposes a transaction that transfers 100 real USDC
to the attacker and mints 100 fake USDC to the user. Summary: `• You send 100 USDC (private, 0xa1b2c3d4…ef01)`,
`• You receive 100 USDC (private, 0x9f8e7d6c…1234)`, `• Fee: …`. The transfer call is hidden (it is "said" by the change), the mint is listed
with the site's mark, but the two balance lines read as a round trip.

Remediation: mark tokens the wallet does not follow (the app knows its followed list; pass it to the gateway, or mark site-registered and
discovered tokens) on the balance lines, and show the address in "Found in your apps".

### 7. P3 — A site can run the native prover without approval and disarm the user's Cancel

**CONFIRMED** by reading.

Locations: `policy.ts:33-37` and `grants.ts:132-134` (`profileTx` allowed with the simulation scope), `wallet.js:80-85`
(`ProfileOptionsSchema` accepts `skipProofGeneration`), `pxe.js:735-760` (`#prove(…, { simulate: skipProofGeneration })`),
`private_kernel_execution_prover.js:55-60` (`simulate: false` → real proofs through the PXE's `proofCreator`, i.e. `NativeKernelProver`),
`AndroidPxeLauncher.kt:44-52` (every `prove` call does `ProofGate.check()` then `ProofGate.release()`), `ProofGate.kt:24-29`
(`cancel()` is refused once `released`), `Transactions.kt:146` (only *sendTx* dApp items are tracked).

Scenario: with a simulation scope, a site calls `profileTx(payload, { skipProofGeneration: false })` repeatedly: 15–30 s of native proving
each, no panel, battery and CPU drained. If the user's own transfer is being proven meanwhile, the site's proof's `release()` sets
`released = true`: the overlay switches to "Sending to the network…" and the user can no longer cancel a transaction whose proof has not
been handed over.

Remediation: force `skipProofGeneration: true` for site `profileTx` (or refuse the method), and key `ProofGate` to the transaction it
protects (a token per `prove` request / per item) rather than one global flag.

### 8. P3 — Unapproved, unbounded registrations and metadata probes

**CONFIRMED** by reading. `registerContract`, `registerContractClass`, `registerSender`, `getContractMetadata`,
`getContractClassMetadata` are `UNSCOPED` (`policy.ts:10`) and unapproved (`grants.ts:142-144`); messages up to 32 MB are accepted from
a connected page (`Dapps.kt:217`, `Dapps.swift:168`). The "contracts"/"contractClasses" `canRegister` flags are displayed in the
capabilities sheet (`details.ts:131-138`) but never enforced (the design doc lists per-call enforcement as to do). Effects: a site can
fill the encrypted store with artifacts, add senders that slow every sync, and `getContractClassMetadata(id).isArtifactRegistered`
(`base_wallet.js:496-502`) tells it which contract classes this wallet has used (fingerprinting). Remediation: enforce `canRegister`,
cap registrations per session, and answer `isArtifactRegistered` only for classes the site itself registered.

### 9. P3 — No activation delay on the panel's buttons

**CONFIRMED** by reading. `ApprovalPanel.kt:64-67` and `ApprovalPanel.swift:17-21` enable Reject/Approve immediately; iOS slides the panel
in (`MainView.swift:40-43`). Signing kinds require `owner()` (biometric) in `DappApprovals.answer`, which is the right defence; connect,
capabilities and verify do not. A page that times its discovery or `requestCapabilities` to a tap on its own bottom-of-page button (or that
chains requests so the next panel replaces the answered one under the finger) obtains a session and simulation/utility scopes
(private-state reads) without the user reading anything. Remediation: ignore taps for ~500 ms after the panel appears or its request
changes, as browsers do for permission prompts.

### 10. P3 (PLAUSIBLE) — Effects are shown from a run without the authorizations the send then signs

Locations: `effects.ts:103-106` simulates without authwits; `embedded_wallet.js:84-120` simulates again, signs an authwit for every
`CallAuthorizationRequest` the run emitted (guarded to the sender, `wallet.ts:130-133`), then proves a third run with them; the approval
lists only the authorizations the *site* attached (`calls.ts:156,170`), never those the wallet signs. A consumer contract whose behaviour
differs with a witness present would show benign effects and run others. What I could not settle from the code: whether the pinned
simulator ever lets the first run proceed past a missing witness (`utility_execution_oracle.js:281-287` throws, and the private oracle
inherits it), which would bound the divergence to contracts that do not read the witness in the first run. Either way, the approval
should list the implicit authorizations ("lets <consumer> call <selector> on your behalf", available from the same `CallAuthorizationRequest`
fields) so the user sees what is signed.

### 11. P3 — Fee line vs what actually pays

**CONFIRMED** by reading. `gateway.ts:265` returns `'account'` whenever `opts.fee` is set (a site setting only `gasSettings`), while
`wallet.ts:139-148` still adds the Sponsored FPC when no `feePayer` is named. The panel says "paid from your account" and the details sheet
drops the "Fee paid by" row (`details.ts:192`). Safe direction, but wrong, and it trains users to ignore the fee line. On a Fee-Juice
network the exposure the site controls (`maxFeesPerGas × gasLimits`) is not stated either; only the priority tip is.

### 12. P3 — Reliability items that touch money states

All CONFIRMED by reading:
- `wallet.ts:130-133`: a site's user-approved `createAuthWit` for account B fails with "another account" if the user's own send from A is in
  flight (the guard is right for implicit authwits; the explicit, confined one need not be refused).
- `gas.ts:98-107`: `#sendKnown` bypasses `EmbeddedWallet.sendTx`, so a cached shape that now needs an implicit authwit fails with "Unknown auth
  witness…", which does not match `/gas/i` (`gas.ts:68`) and is not retried.
- `submit.ts:37-48`: after a lost answer, a DROPPED receipt read 2–6 s later is believed (`answered` → rethrow → Failed with hash), while the apps
  wait 15 min before believing a single DROPPED (`Transactions.kt:233-234`); behind a load balancer this yields a false Failed until the next open.
- `wallet.ts:81-87` with `Transactions.kt:256-261`: any mined status (PROPOSED included) becomes Confirmed and is never re-read; a proposed block
  can be re-orged (SDK comment in `embedded_wallet.js:120-130`).
- `AppViewModel.kt:198-213`: auto-lock only checks the wallet's own `proving`; a dApp transaction being proven is killed with the PXE host
  (recorded Failed by `failInterrupted` unless its hash was already told).

## Checks that passed (what is done right)

- **Origin and session binding**: the origin comes from the web view only (`BrowserTab.kt:71-78`, `BrowserTab.swift:98-103`); each page load
  gets an id, sessions and pending discoveries answer only their page and origin (`gateway.ts:104-120`, `policy.ts:53-55`), navigation and
  tab close end them and reject their pending panels (`rejectTab`), the old origin's messages are dropped during a navigation (`BrowserTab.kt:75`).
- **Isolation**: Android browser tabs cannot reach the PXE origin (`BrowserTab.kt:91,193-194`), file/content access and mixed content are off,
  only `http(s)` leaves the browser; the PXE web view blocks every navigation and only its page talks to `AzwallAndroid` (`PxeHost.kt:101-129`).
  iOS runs the content script and bridge in a separate content world, main frame only, with a dedicated website data store so a page at the
  PXE's loopback origin cannot touch its stores (`BrowserTab.swift:14-15,55-62`, `BrowserTabs.swift:16`), and the PXE handler accepts its own
  origin only (`PxeHost.swift:160-172`). Content script is a pure relay, top frame only.
- **Review == run**: arguments are parsed by `WalletSchema` and re-serialised once (`raw_call.ts:21-27`), the review, the grants and the call read
  the same objects; a label naming another function than the selector is refused (`calls.ts:123`); a smuggled readable `call` next to a signed
  hash is dropped (tested); `from`/`sendMessagesAs`/scopes are confined to the session's account; `getAddressBook` is empty; nested batches
  and unknown methods are refused; `NO_FROM` sends cannot obtain any authwit.
- **Flooding/limits**: 64 KB before a session, one connect prompt per page, one signing request per session, capability requests serialised,
  JSON parsing cannot pollute prototypes (own-property semantics; zod strips unknown keys).
- **Fee trust**: fee functions are trusted by address (the network's sponsor), not by name (`calls.ts:43-53`); the priority tip is shown as an
  upper bound; the sponsor is only used when the registry declares it and the contract exists (`fees.ts`).
- **Native approval**: signing approvals require the owner's biometric/credential after the tap; `settle()` prevents a cancel from landing on a
  dApp proof; pending panels are rejected on lock, restart, network switch and screen destruction.
- **Wallet's own transfers**: recipient checked for zero, the token itself and curve validity (`token.ts:28-32`); amounts are decimal base-unit
  strings parsed as bigint and must be positive (`params.ts:34-40`); the review's claim is pinned (`Transactions.kt:44-58`).
- **Node/RPC trust**: chain id and rollup version are checked twice (`open.ts:47-73,177-182`), the Fee Juice portal is pinned, the Ethereum
  endpoint's chain id is checked, every deposit log must bind its own message hash (`fee_juice.ts:146-152`), scans are bounded, and a phantom
  deposit can never be claimed (the Aztec node must hold the message). What the node can still bend is the *public* part of the pre-approval
  simulation (`effects.ts:84-92`), which is inherent to Aztec's public simulation.
- **Submission**: hash told before sending, uncertain sends followed as pending, hashed proving items resumed as pending, overlay honest after
  release.

## Notes for the release decision

Finding 1 is the one that lets a user approve something other than what runs, with ordinary dApp behaviour (registering a contract) as the
entry point and no warning in the panel; it should block external testing until the shipped artifacts win over a site's. Findings 2 and 3 can
each cost a second payment or hide a dropped one and are cheap to fix together (treat "sent" as pending, read the receipt's hash, never
erase a hash). Findings 4–6 are the display-spoof set a phishing dApp would combine; 7–12 are hardening.
