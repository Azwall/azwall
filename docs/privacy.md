---
title: Privacy policy
nav_order: 5
---

# Privacy policy

Azwall is a self-custodial wallet for the Aztec network. It has no account, no server of its own, no analytics and
no advertising. Nobody behind Azwall can see your keys, balances, addresses or transactions.

## What stays on your phone

- **Your recovery phrase and keys.** They are encrypted on the device with a key held by the phone's secure hardware
  (Android Keystore, iOS Keychain), unlocked with your fingerprint, face or device code. They are never sent anywhere
  and are not included in device backups.
- **Your balances, notes and history.** The wallet computes them on the device, in an encrypted store.
- **Proofs.** Every transaction is proven on the device; what you send is the finished proof, never your keys.
- **A diagnostic log** of recent errors, with addresses and hashes shortened and web addresses cut to their host. It
  leaves the phone only if you export it yourself from Settings, and only to where you choose to share it.

## What the wallet sends over the network

To work, the wallet talks to public services. They see your IP address and the requests below, like any website you
visit, under their own privacy policies:

| Service | Why | What it receives |
|---|---|---|
| The Aztec node of the selected network (by default dRPC: `aztec-mainnet.drpc.org`, `aztec-testnet.drpc.org`) | Read the chain, send your proven transactions | Encrypted chain data requests and your transactions, which are public on chain by design (their private parts stay encrypted) |
| An Ethereum endpoint (by default Tenderly's public gateway, then MEV Blocker or 0xrpc.io as fallback) | Find Fee Juice bridged to your account from Ethereum | Your Aztec account address, to look up deposits made to it |
| Websites you open in the built-in browser, and the search engine you pick (DuckDuckGo by default) | Browse Aztec apps | What any browser sends to a website |
| A block explorer (Aztecscan), only when you tap a link to it | Show a transaction | The transaction you open |

You can replace the default Aztec node and Ethereum endpoint with your own.

## Permissions

- **Camera**: only to scan QR codes (addresses, payment requests) when you choose to. Images are not stored or sent.
- **Biometrics**: handled by the operating system; the wallet only learns whether you were recognised.
- **Internet**: for the services above.

## Children

Azwall is not directed at children under 13 and does not knowingly collect any data from anyone.

## Changes and contact

Changes to this policy are published on this page, with the source code, at
[github.com/Azwall/azwall](https://github.com/Azwall/azwall). Questions can be sent through the issue tracker there,
or to the contact address on the app's store page.
