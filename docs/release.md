---
title: Releasing
nav_order: 6
---

# Releasing

## The prover's provenance

Every proof is made by Barretenberg, built from source by `scripts/build-bb-android.sh` and `scripts/build-bb-ios.sh`.
`scripts/bb-commits` records the aztec-packages commit of each release: the build refuses a checkout whose tag no
longer names that commit. Each library built is recorded in `scripts/bb-libs.sha256`, and the app builds refuse to
ship a library that does not match its line (rebuild it, then commit the new line with the reason). To check a
shipped prover, rebuild it from the recorded commit and compare.

# Releasing the Android app on Google Play

## The upload key

Google Play signs the app delivered to phones with its own key (Play App Signing); the developer signs what they
upload with an **upload key**. Create it once, keep it out of the repository and back it up (a lost upload key can be
reset through Play support, but it takes days):

```bash
keytool -genkeypair -v -keystore ~/keys/azwall-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then write `android/keystore.properties` (ignored by git):

```properties
storeFile=/home/you/keys/azwall-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without this file the release is built unsigned.

## Building

1. Bump the version in the four places `rust/azwall-core/tests/versions.rs` checks (the build number follows it).
2. Build the native libraries and the bundle:

   ```bash
   scripts/build-android.sh --release arm64-v8a
   cd android && ./gradlew :app:bundleRelease
   ```

   The bundle is `android/app/build/outputs/bundle/release/app-release.aab`. The build refuses to go on with a debug
   Rust core, or with an RPC endpoint from `.env` (it may carry an API key, which anyone could read in the bundle):
   empty those keys for a release.

## Trying the release build first

R8 shrinks and renames the release; what works in the debug build can break there. The `staging` build is the same
release, signed with the debug key and installed beside the app (`com.sbss.azwall.staging`), so it can be tried without
replacing the wallet on the phone:

```bash
cd android && ./gradlew :app:installStaging
```

Create a test wallet in it, open each network, send a transaction and connect a dApp before uploading.

## Play Console

The listing texts in English and French, the icon, the feature graphic, the screenshots and a proposal for the Data
safety form are in [`store/play/`](https://github.com/Azwall/azwall/tree/main/store/play).

- **Testing tracks**: internal testing (up to 100 testers, no review wait) is the quickest first step. New personal
  developer accounts must run a closed test with at least 12 testers for 14 days before open testing and production
  become available.
- **Privacy policy**: the [privacy policy](privacy.md) page of this site.
- **Data safety**: the developer runs no server and receives nothing. The app itself sends data to third parties,
  though: your account addresses and transactions to the Aztec node's RPC provider (dRPC by default), your address
  to the Ethereum endpoints (Tenderly, MEV Blocker, 0xrpc.io) to find bridged Fee Juice, and pages to the sites you
  browse. Check these flows against Google's definitions of "collected" and "shared" before answering; the privacy
  policy lists them. All of it is encrypted in transit (https); deleting the app deletes everything on the phone.
- **App category**: Finance. Google Play's policy on cryptocurrency wallets applies: Azwall is a non-custodial
  software wallet.
- **Content rating**: questionnaire, no objectionable content.

# Releasing the iOS app on the App Store

- **Developer account**: an app that stores cryptocurrency must come from a developer account enrolled as an
  organisation (App Review Guideline 3.1.5(b)(i)), the company named in the [privacy policy](privacy.md).
- **Building**: on a Mac, `scripts/build-ios.sh --release`, `scripts/build-bb-ios.sh all`, `scripts/fetch-crs.sh`, then
  `xcodegen generate` in `ios/` and Product > Archive in Xcode. A Release build refuses an RPC endpoint from `.env`, as
  Android's does.
- **Tests first**: `swift test` in `ios/AzwallKit`, and the app on a physical iPhone with Face ID: create and restore,
  a transfer, a dApp approval, lock and unlock.
- **Privacy**: `ios/Azwall/PrivacyInfo.xcprivacy` declares no tracking, no data collected and the "required reason"
  APIs the app uses (checked on the built binaries with `nm -u`); check it again when a dependency changes.
- **Export compliance**: App Store Connect asks whether the app uses non-exempt encryption (it uses HTTPS, AES-GCM for
  its stores and the zero-knowledge proofs); answer it, then record the answer as `ITSAppUsesNonExemptEncryption` in
  `ios/project.yml` so it is not asked at every upload.
