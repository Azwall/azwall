# Open-testing readiness review — 2026-10-29 (afternoon)

Reviewed revision: `b72d37b` (working tree clean), ten commits after the morning's
[store-release re-audit](2026-10-29-store-release-reaudit.md) and six after the
[Google Play open-test audit](2026-10-29-google-play-open-test-audit.md) by another reviewer. Evidence and the two
area notes: [2026-10-29-open-testing-evidence](2026-10-29-open-testing-evidence/).

Target: **Google Play open testing** of the Android app. iOS (TestFlight external testing) is assessed separately at the
end.

## Decision

**Android, code: ready for open testing at `b72d37b`.** Every P1 and P2 of the three audits is closed in the code on
Android (table below); what remains open is P3 hardening, none of which lets money leave without consent, loses keys
or wedges the wallet. **Publication itself is not ready**: the bundle built today is unsigned, no audit has yet run the
app on a physical ARM64 phone, and the Play Console items are the publisher's. The release is ready to upload once the
three steps under "Before uploading" are done.

**iOS, code: not ready for TestFlight external testing.** The Xcode phase that carries the `--public-only` gate still
does not fail when the gate refuses and is skipped once its outputs exist (R2, unchanged); the key-only wallet account
text (OT-03), the bounded auto-lock that counts dApp transactions (R5), and the three iOS P3s of the morning (R6–R8)
were fixed on Android only; `ITSAppUsesNonExemptEncryption` is still absent. Today's `.env` holds no endpoint, so an
archive built now would ship public hosts only, but the gate must hold before anyone archives with a keyed `.env`.

## What was checked on `b72d37b`

| Where | Check | Result |
|---|---|---|
| Linux | `scripts/test-all.sh` | Rust 73 passed, 1 live test ignored; PXE 14 shared + 99 host; Android JVM suites pass |
| Linux | `./gradlew :app:bundleRelease` with the release Rust core staged at 11:29 (after the last Rust change, 11:28) | `app-release.aab`, 72,267,800 bytes, SHA-256 `ee620986fe2973c8…`; **unsigned** (no `android/keystore.properties`); six native libraries, all `arm64-v8a`; `libbb_v6.0.0.so` matches `scripts/bb-libs.sha256`; the bundled `azwall-config.json` names the registry's public hosts only |
| Linux | archived reproduction `OpenTestAuditTest.kt.txt` of the open-test audit, re-run against `b72d37b` | OT-01 assertion passes as archived; OT-02 passes once the event carries the `id` the host now emits (the adapted file and its result XML are in the evidence) |
| Mac (Xcode 27.1) | `swift test`, Debug and Release simulator builds, hosts bundled in the Release product | 123 tests in 30 suites pass; both builds succeed; Release config holds public hosts only (`.env` is empty) |
| Device | Pixel 3 (Android 12, arm64) connected over USB | Found at the end of this review; the staging build is being installed, see "Device run" |

## Status of every finding

| Finding | Status on Android | Status on iOS |
|---|---|---|
| F1 crafted Token artifact (P1) | closed | closed (shared host code) |
| F2 privacy manifest (P1) | n/a | closed; export-compliance key still missing |
| F3 organisation enrolment (P1) | publisher's to confirm (Play requires it for wallet software too) | publisher's to confirm |
| F4 lock waits for the PXE (P2) | n/a | closed |
| F5 vault erased silently (P2) | n/a | closed |
| F6 missing Keystore key (P2) | closed | n/a |
| F7 sent transaction shown Failed (P2) | closed | closed |
| F8 `{receipt}` hash (P2) | closed | closed |
| F9 panel spoofing (P2) | closed, with a P3 residue: decoded argument values and struct field names from a site's own artifact bypass `oneLine` (`calls.ts` `show()`), reproduced by a probe; the line is still marked as the site's | same (shared host code) |
| F10 iOS archive ships `.env` keys (P2) | n/a | **open** (R2) |
| F11 prover provenance (P2) | closed (commit pin, hash record); Gradle itself still checks no hash | closed (Xcode phase checks the slices) |
| F12 browser after Retry (P2) | n/a | closed |
| F13 late proof after a timeout (P2) | closed, and OT-01 with it: an unanswered call retires the host, a late proof is handed back to nobody, a FAILED session cannot start a send | closed |
| F14 iOS tests compile (P2) | n/a | closed |
| R1 gate shut after any failed send (P2) | closed: only an unanswered call keeps it shut | closed |
| R2 Xcode phase (P2) | n/a | **open** |
| OT-01 next send reopens the gate for an old proof (P1) | closed; archived test passes | closed |
| OT-02 queued dApp sends lose the hash (P2) | closed: the dApp row's id travels with the hash; archived test passes with the new event shape | closed |
| OT-03 key-only wallet promises phrase recovery (P2) | closed: the form says the key is random and in no backup, and asks for a backup promise | **open**: `Sheets.swift` still promises "the next account of your phrase" |
| R3 sanitiser gaps (P3) | closed for names and capability lines; residue above | same |
| R4 "can't unlock" flag (P3) | closed: cleared on unlock, create, start-over and wipe; "Try again" first | n/a |
| R5 auto-lock wait (P3) | closed: ten minutes at most, every account's dApp rows, none of a FAILED session | **open**: still `proving == nil` only |
| R6 Unlock live during the close (P3) | n/a | open |
| R7 approval after `rejectAll` (P3) | n/a | open |
| R8 unexpected Keychain status → vault-lost (P3) | n/a | open |
| R9 `http://localhost:1@host` (P3) | closed | closed |
| R10 privacy policy claim (P3) | closed | closed |
| F15, F16 lifecycle guards (P3) | partial: lock/switch/restart during a dApp proof still unguarded | partial |
| F18 global gate, F19 registrations, F21 implicit authorizations, F22 renderer process (P3) | open | open |

## New this round (all P3)

- **N1.** After an unanswered send retires the host and the user taps Retry, the process-wide `ProofGate` is still
  cancelled (nothing resets it: the FAILED session cannot, the new session shares the object), so the first dApp
  transaction approved on the new host is refused as "cancelled" until a wallet send resets it. Reproduced by a probe on
  the fakes. One line: reset the gate once `retireHost` returned, or bind the gate per prove request (F18). Same on iOS.
- **N2.** `cancel()`'s return value is ignored in the unanswered path (`Transactions.kt:192-196`, `.swift:172-176`): a
  `release()` landing between the `released` read and `cancel()` leaves nothing cancelled while the host is destroyed
  as the page posts the transaction (row Failed, node may have it). Instruction-sized window; branch only when
  `cancel()` returned true.
- **N3.** A site granted under account B keeps acting as B after the user switches to A; B's dApp proof ahead in the
  send queue sets the global `released`, so A's timeout does not retire the host and A's proof can be sent behind a
  Failed row. Contrived (second-account dApp plus a 20-minute call); F18's per-request gate closes it.
- **N4.** A truly stuck native proof holds the prover mutex: every Retry waits behind it and times out again (liveness
  only; the process must be killed).
- **N5.** Coverage: the handler-side guard that closes OT-01 (`alive()`/`isClosed`) has no test on either platform; the
  STUCK message is overwritten by any later refresh.
- **R3 residue** above (`show()`), and `SiteName`'s hand-picked shared-host list.

## Before uploading (Android)

1. **Sign.** Create the upload key and `android/keystore.properties` as `docs/release.md` says, rebuild
   `:app:bundleRelease`, check the bundle carries a signature; confirm version code 800 was never uploaded.
2. **Run the staging build on the Pixel 3** (the device run below, to be completed with the owner's fingerprint): create
   and restore a wallet, unlock with fingerprint and PIN, a private transfer on testnet, shield and unshield, a dApp
   connect, reject and approve, cancel a transaction, kill the app during a transaction and reopen, lock during a
   sync, Retry then approve a dApp transaction (N1 shows here as one refused approval).
3. **Play Console**: organisation account verified, financial-features declaration, Data safety as drafted in
   `store/play/README.md`, privacy-policy URL, listing, content rating; then the pre-launch report.
4. Decide what the open test exposes: the artifact serves mainnet as well as testnet, and open testing is public. Either
   validate the mainnet flows (fees, activation) to the same standard, or ship a testnet-only build for the open test.

Recommended but not blocking: N1 and N2 (a few lines each), the `show()` sanitising, F15 guards.

## Device run (Pixel 3, Android 12, arm64)

A Pixel 3 was found connected over USB at the end of this review, with `dev.azwall`, `dev.azwall.staging` and the
prototype installed, screen off and locked. The staging variant of `b72d37b` (the release as R8 makes it, debug-signed,
`dev.azwall.staging` 0.8.0-staging, version code 800, installed beside the wallet, which is not touched) was built and
installed from this machine at 12:42 and started: the process came up, loaded its native libraries and showed no crash or
PXE error in the first seconds. The phone stayed on its lock screen, so nothing past the launch was exercised. Driving
the flows needs the phone unlocked and, at each biometric prompt, the owner's finger; the result of that run is to be
appended here.

## iOS before TestFlight

Fix R2 (`set -euo pipefail` in the phase, delete the config before generating it, `basedOnDependencyAnalysis: false`,
a Release-only check of the bundled config), port OT-03 and R5, add `ITSAppUsesNonExemptEncryption`, then R6–R8; archive
with an empty `.env` until R2 holds; confirm the organisation enrolment (F3); run on a physical iPhone (no proof has
been produced on iOS hardware so far).
