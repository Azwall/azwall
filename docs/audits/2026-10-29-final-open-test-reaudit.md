# Final Android Google Play open-test re-audit — 2026-10-29

Reviewed commit: `aa64f3993b55b29c8044d61cae39fbec28d32b43` (clean at entry).
Scope: Android **open testing**, including the shared Rust/PXE code, native/browser trust boundaries,
wallet storage and recovery, transaction lifecycle, dependency advisories, the built AAB and Play readiness.
This is a fresh verification of the current implementation and artifact, not an endorsement of an earlier verdict.
No application code was changed, no wallet was opened, and no transaction or publication was performed.

## Decision

**Hold the open-test release.** The three findings from the earlier open-test audit are addressed in their
reported scenarios, but one remaining P2 transaction-lifecycle defect is reproduced below with two failing
assertions. An uncertain native send can be recorded as failed and its late hash assigned to a retry.
This qualifies the earlier readiness review's conclusion that transaction timeout handling was fully closed.

Publication also needs a signed upload bundle, completed physical-device flows and the publisher's Play Console
checks. These are validation/release prerequisites, separate from the reproduced code defect.
The current artifact exposes **mainnet as well as testnet**; an open-test track does not restrict it to test funds.

Evidence: [final-open-test-evidence](2026-10-29-final-open-test-evidence/).

## Reproduced finding

### FO-01 — P2: a timeout after proof release loses the operation's identity

Relevant code:

- `android/core/src/main/kotlin/dev/azwall/core/wallet/Transactions.kt:159–162`: no recorded hash means FAILED.
- `Transactions.kt:193–203`: an unanswered call retires its host only when `ProofGate.cancel()` succeeds;
  after proof release it returns false, so the host stays alive and the gate resets.
- `Transactions.kt:266–269`: native submission events have no operation ID and are assigned to the single
  currently PROVING native row for that sender.
- `android/core/src/main/kotlin/dev/azwall/core/pxe/PxeChannel.kt:61–74`: timeout abandons the reply wait,
  without cancelling the running JavaScript call.
- `pxe/host/src/submit.ts:58–62`: the submission notification occurs later, just before `node.sendTx`.

Trigger and result:

1. A native transfer hands its proof to the host. Before its submission notification is recorded, the native
   channel's 20-minute deadline expires (for example, after an abnormally slow operation or suspended host).
2. `ProofGate.cancel()` refuses cancellation because the proof has left. With no hash recorded,
   `track()` nevertheless saves FAILED and allows another send on the still-ready session.
3. A retry creates another PROVING row and queues behind the first JavaScript send.
4. The first send resumes and emits its unlabelled `txSubmitting` event. Its hash is saved on the retry's row.
   The first row remains FAILED. If the app stops here, restart reconciliation follows the wrong operation;
   without a retry, the late notification is simply ignored because the first row is no longer PROVING.

The user may resend a payment that is still on its way and see an incorrect history. This does **not** demonstrate
signing without approval or key theft. The trigger is a timeout in the interval after proof release and before
hash persistence, not an ordinary completed send. The two assertions are manifestations of one lifecycle defect.

Reproduction: [FinalOpenAuditTest.kt.txt](2026-10-29-final-open-test-evidence/FinalOpenAuditTest.kt.txt),
[result XML](2026-10-29-final-open-test-evidence/reproduction-results.xml).
Both tests exercise the real `Transactions` and `WalletStore` with the repository's fake PXE transport:

| Assertion | Observed result |
|---|---|
| A released, unanswered send must not be a definite failure | Actual status: `FAILED` |
| The retry must not inherit the first send's hash | Expected `null`, actual `0xfirst` |

The fake injects the channel error and delayed event deterministically; it does not run a real 20-minute device
transaction. The channel and host source establish why that ordering can occur. The fixture was archived and
removed from the active test tree; unchanged JVM suites then passed again.

Recommended correction: carry an immutable operation ID through **all native and dApp sends**, including the
submission notification. Preserve an explicit uncertain/submitting outcome after proof release when no definitive
answer exists, and prevent a retry from masquerading as a distinct safe replacement. Reconcile delayed events by
ID even after the original call stops waiting. For crash consistency, persist the submission identity/hash before
broadcast with an acknowledgement; a fire-and-forget event alone does not guarantee durable recording.
Add tests for delayed native events, retry overlap and process restart, in addition to the already-covered dApp case.

## Previous corrections verified

| Earlier issue | Current evidence and assessment |
|---|---|
| OT-01: a retry reopens the gate for an old, unreleased proof | Host retirement, FAILED-session guard and host-alive check are present. `aTransactionLeftUnansweredIsNeverSentLater` passes. FO-01 covers the different, already-released branch. |
| OT-02: two queued dApp sends lose their hash | Gateway ID now travels through `sendLabelled`, `SenderGuard.label` and `txSubmitting.id`. `queuedDappSendsKeepTheirHashAcrossARestart` passes. Native sends still lack that ID. |
| OT-03: a key-only wallet promises phrase recovery for a random new account | UI explicitly describes the independent random key and requires a backup promise; view-model guard is tested. `aKeyOnlyWalletMakesNoRandomAccountWithoutABackupPromise` passes. |
| Readiness N1/N2/N5 | Retired host resets the shared gate, retirement branches on the return from `cancel()`, and refresh preserves the failure reason. Existing tests pass; the full Android WebView/native callback race was not exercised on hardware. |
| Approval text sanitation | Decoded argument values and field names now pass through bounded `oneLine`; host tests pass. |

## Security review coverage and limits

- **Keys and recovery:** inspected BIP-39 generation/derivation, scalar validation, per-operation authenticated
  Keystore AES-GCM, legacy migration, serialised vault mutations and wipe ordering. Prior rollback/wipe tests pass.
  Secret screens use screenshot protection and sensitive, expiring clipboard entries. Hardware authentication and
  restore usability remain device checks; this review does not establish zeroisation of all managed-memory copies.
- **dApps:** inspected main-frame/origin/page binding, refusal of the private PXE origin in the browser, capability
  and account confinement, serialized sender authorizations, contract provenance and transaction summaries.
  The broader isolation tests pass. No additional approval bypass was reproduced in this pass.
- **Transactions and lifecycle:** inspected cancellation, timeout, submission events, persistence and restart
  reconciliation, session retirement and bounded background auto-lock. FO-01 remains. Existing hardening concerns
  around the process-wide proof gate and close/network-switch during approved sends remain relevant; a passing
  fake-backend test is not an end-to-end lifecycle guarantee.
- **Rust/prover:** checked framing limits, frozen derivation tests, pinned Aztec/Barretenberg versions, shipped
  library provenance and CRS checksums. This is an application/integration audit, not a cryptographic proof or a
  new audit of Aztec/Barretenberg internals.
- **Privacy and platform:** release WebView console output is suppressed; diagnostics are local and redacted.
  Backup is disabled, network security blocks external cleartext, and no unnecessary exported wallet service was
  found in the bundle manifest. Device behaviour and the actual Console declarations were not observed.

## Executed checks

| Check | Result |
|---|---|
| `scripts/test-all.sh` | PASS: Rust 73; PXE 14 shared + 100 host; Android JVM 142 core + 33 app = **362 distinct tests**. One Rust live-RPC test ignored by default; Swift unavailable and outside this Android scope. Includes size/swallowed-error checks and TypeScript checking. |
| `cargo clippy --workspace --all-targets -- -D warnings` | PASS |
| `./gradlew --quiet lintRelease :app:bundleRelease` | PASS; 17 app + 1 core lint warnings, no errors |
| New FO-01 probes | 2 tests, 2 expected assertion failures documenting the defect |
| Clean JVM rerun after removing probes | 175 tests pass, no errors/failures |
| `cargo audit --json` | No known vulnerabilities; one unmaintained `basic-toml` advisory. `cargo tree -i basic-toml` shows no dependency in the current default graph. |
| `pnpm --dir pxe audit --prod --json` | 11 advisories: 7 high, 3 moderate, 1 low; no critical |

The JavaScript advisories concern `systeminformation`, `uuid`, `@opentelemetry/core` and
`@opentelemetry/propagator-jaeger`. They are dependency-graph findings, not eleven demonstrated mobile exploits.
The affected entry points' reachability in the shipped browser bundle was not conclusively established.
Record a reachability assessment or compatible vendor fix before closing this dependency risk; preserve the exact
Aztec/Barretenberg/Noir pins. Raw advisory data is included in the evidence. Lint's toolchain/dependency updates and
backup/icon/style warnings are recorded but do not explain FO-01.

## Exact release artifact and public services

Artifact: `android/app/build/outputs/bundle/release/app-release.aab`.
SHA-256: `bd1f29de6b85c18e665853e7273a9c188c1619d8533bf84dca8184e028bc1f97`.
Size: 72,267,799 bytes. Manifest parsed from the AAB itself, not just Gradle configuration.

- Package `com.sbss.azwall`, version `0.8.0`, code `800`, minimum API 30, target API 37; non-debuggable by default.
- **Unsigned:** no JAR signing entries. This exact file is not an upload-ready signed release.
- Six native libraries, only `arm64-v8a`; every ELF LOAD alignment is 16,384 bytes. The Barretenberg library matches
  `scripts/bb-libs.sha256`; all three CRS assets match `scripts/crs.sha256`.
- Packaged PXE/config bytes match local generated output. RPC and L1 endpoints match the registry's public defaults.
  No `.env` values or signing credentials were printed. No JavaScript source maps are packaged; R8 mapping is metadata.
- Testnet is the default, but `mainnet-v6` is selectable. Both public nodes returned version 6.0.0 with expected chain,
  rollup and Fee Juice portal identities. VK/protocol hashes are absent from this RPC response; the local SDK checks
  them against the registry during `open`, so the probe does not claim to verify those fields remotely.
- Public privacy URL returned HTTP 200 and names Swiss Block Services. The outdated claim that users can replace
  RPC endpoints is absent. Listing icon/feature dimensions are 512×512 / 1024×500; seven screenshots are 1080×2160.
- Physical Pixel 3/ARM64 detected, **keyguard showing**. An older `dev.azwall.staging` is installed; that is not
  evidence of current `com.sbss.azwall` flows. The connected 16-KB emulator is x86_64, so it cannot validate these
  release ARM64 binaries. ELF alignment is not a substitute for a 16-KB ARM64 execution test.

## Before opening the Play track

1. Correct FO-01 and pass its delayed-event/restart regression cases.
2. Exercise a current release-equivalent build on a physical ARM64 phone: create/restore, fingerprint/device code,
   private transfer, shield/unshield, activation/fees, dApp accept/reject, cancel before proof release, lock and process
   death during sending, and retry after a stopped engine. Record the exact revision and results. Validate mainnet
   flows to the intended exposure, or deliberately limit this open-test build to testnet.
3. Sign the upload AAB and recheck its identity, native contents and endpoints. Confirm package registration,
   signing setup and unused version code in the Console. [Android app signing](https://developer.android.com/studio/publish/app-signing).
4. Verify the publisher's organization account, financial-features declaration, Data safety, privacy/contact details,
   audience/content rating, supported devices and Play pre-launch report. None of these Console states was accessed.

Google's [open-testing guidance](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en)
says anyone can join and the listing is visible; the
[Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) apply.
Wallet providers need the appropriate
[organization account](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en-419).
The [non-custodial exclusion](https://support.google.com/googleplay/android-developer/answer/16329703?hl=en)
concerns the specific cryptocurrency exchanges/software-wallets policy, not every Play requirement.
Target 37 exceeds the current [API 36 submission minimum](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).
These references were checked on the audit date; they do not constitute Play approval.
