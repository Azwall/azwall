---
title: Releasing
nav_order: 6
---

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
release, signed with the debug key and installed beside the app (`dev.azwall.staging`), so it can be tried without
replacing the wallet on the phone:

```bash
cd android && ./gradlew :app:installStaging
```

Create a test wallet in it, open each network, send a transaction and connect a dApp before uploading.

## Play Console

- **Testing tracks**: internal testing (up to 100 testers, no review wait) is the quickest first step. New personal
  developer accounts must run a closed test with at least 12 testers for 14 days before open testing and production
  become available.
- **Privacy policy**: the [privacy policy](privacy.md) page of this site.
- **Data safety**: no data collected or shared by the developer; data is encrypted in transit; users cannot request
  deletion because nothing is held (deleting the app deletes everything).
- **App category**: Finance. Google Play's policy on cryptocurrency wallets applies: Azwall is a non-custodial
  software wallet.
- **Content rating**: questionnaire, no objectionable content.
