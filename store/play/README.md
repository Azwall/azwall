# Google Play listing

What Play Console asks for, ready to paste. Images: `icon-512.png` (512×512), `feature-graphic.png` (1024×500) and
`screenshots/` (1080×2160, phone; taken on the testnet with a test wallet, `docs/release.md`). Lengths are Play's limits.

## Main store listing (English, default)

**App name** (30): `Azwall: private Aztec wallet`

**Short description** (80):
`Private, self-custodial wallet for Aztec. Proofs made on your phone.`

**Full description** (4000):

```
Azwall is a self-custodial wallet for the Aztec network, where balances and transfers can stay private.

PRIVATE BY DEFAULT
Send tokens privately: the amount, the sender and the recipient stay hidden on chain. Shield public tokens into private notes, or unshield them back, in one tap.

PROVEN ON YOUR PHONE
Every transaction is proven on your device with a zero-knowledge proof. Your keys never leave the phone, and nothing is sent until the proof is ready. You can cancel while it is being proven.

YOUR KEYS, YOUR FUNDS
Azwall is non-custodial: there is no account and no server of ours. Your recovery phrase and keys are encrypted by the phone's secure hardware and unlocked with your fingerprint, face or device code. Every transaction asks you to confirm it.

DAPPS, SAFELY
Open Aztec apps in the built-in browser. Each request shows the site's real domain, what the transaction does to your balances and who pays the fee, before you approve it.

EVERY AZTEC NETWORK
Switch between Aztec networks. On test networks, fees are sponsored. Bridge Fee Juice from Ethereum and claim it when you activate your account.

NO TRACKING
No analytics, no ads, no account. The wallet talks only to the Aztec node and Ethereum endpoints it needs, and to the sites you open.

This is an early test version. Use small amounts, and keep your recovery phrase safe: without it, a lost phone means lost funds.
```

## Fiche en français (traduction)

**Nom** (30) : `Azwall : wallet Aztec privé`

**Description courte** (80) :
`Wallet Aztec privé et non custodial. Preuves faites sur votre téléphone.`

**Description complète** :

```
Azwall est un wallet non custodial pour le réseau Aztec, où les soldes et les transferts peuvent rester privés.

PRIVÉ PAR DÉFAUT
Envoyez des tokens en privé : le montant, l'expéditeur et le destinataire restent cachés sur la chaîne. Passez des tokens publics en notes privées, ou l'inverse, en un geste.

PROUVÉ SUR VOTRE TÉLÉPHONE
Chaque transaction est prouvée sur votre appareil par une preuve à divulgation nulle de connaissance. Vos clés ne quittent jamais le téléphone, et rien n'est envoyé avant que la preuve soit prête. Vous pouvez annuler pendant la preuve.

VOS CLÉS, VOS FONDS
Azwall est non custodial : pas de compte, pas de serveur de notre part. Votre phrase de récupération et vos clés sont chiffrées par la puce sécurisée du téléphone et déverrouillées par votre empreinte, votre visage ou le code de l'appareil. Chaque transaction vous demande de la confirmer.

LES DAPPS, EN SÉCURITÉ
Ouvrez les applications Aztec dans le navigateur intégré. Chaque demande montre le vrai domaine du site, ce que la transaction fait à vos soldes et qui paie les frais, avant que vous l'approuviez.

TOUS LES RÉSEAUX AZTEC
Passez d'un réseau Aztec à l'autre. Sur les réseaux de test, les frais sont sponsorisés. Faites venir du Fee Juice depuis Ethereum et réclamez-le en activant votre compte.

AUCUN PISTAGE
Pas d'analytics, pas de publicité, pas de compte. Le wallet ne parle qu'au nœud Aztec et aux points d'accès Ethereum dont il a besoin, et aux sites que vous ouvrez.

Ceci est une première version de test. Utilisez de petits montants et gardez votre phrase de récupération en lieu sûr : sans elle, un téléphone perdu, ce sont des fonds perdus.
```

## Store settings

- **Category**: Finance. **Tags**: cryptocurrency wallet, privacy.
- **Contact details**: an e-mail address is required (the publisher's, Swiss Block Services Sàrl); website
  `https://azwall.github.io/azwall/`.
- **Privacy policy**: `https://azwall.github.io/azwall/privacy`

## App content

- **App access**: all functionality is available without special access (no login; the wallet is created in the app).
- **Ads**: no.
- **Content rating** (IARC questionnaire): category "Utility, productivity, communication or other"; no violence, sexual
  content, language, controlled substances or gambling; users do not interact with each other in the app. The built-in
  browser opens any website: answer "yes" to unrestricted internet access if asked.
- **Target audience**: 18 and over (a financial app; not designed for children).
- **News app**: no. **Government app**: no. **Health**: no.
- **Financial features**: cryptocurrency, non-custodial wallet. The app holds no user funds and offers no exchange,
  lending or fiat conversion of its own.

## Data safety (a proposal: check it against Google's definitions before submitting)

The developer runs no server and receives nothing. The app itself sends some data to third parties, which Google may
count as collected and shared:

| Data type | Collected | Shared | Why | Notes |
|---|---|---|---|---|
| Financial info › Other financial info (account addresses, transactions) | Yes | Yes | App functionality | To the Aztec node's RPC provider (dRPC) and the Ethereum endpoints (Tenderly, MEV Blocker, 0xrpc.io): reading balances, sending the user's proven transactions. Transactions are public on chain by design, their private parts encrypted. |
| Web browsing (pages opened in the built-in browser) | No | No | | Sent by the browser to the sites the user opens, as any browser does; not to the developer. |

- **Is all data encrypted in transit?** Yes (https only; plain http is refused except to the device itself).
- **Can users ask for their data to be deleted?** The developer holds none. Removing the wallet in Settings, or
  uninstalling, deletes everything on the phone. The RPC providers' own request logs are theirs: the developer cannot
  delete them (the privacy policy says so).
- **Account addresses**: declared above as financial info. Google may also read them as "Personal info › User IDs"
  (an identifier that follows the user across requests); declaring both is the cautious answer.
- **Collection required or optional?** Required for the wallet to work (it cannot read balances without the node).
- No data is used for advertising, analytics, personalisation or fraud prevention; nothing is sold.
