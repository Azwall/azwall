// Injected at document start into every page of the app's browser (never into the wallet itself). It plays the
// role of a browser-extension content script in the wallet-sdk protocol: it answers the page's
// `aztec-wallet-discovery`, holds the page side of the MessageChannel, and relays everything to the wallet.
// It is a pure relay: keys, shared secrets and decryption live in the wallet (dapp/gateway.ts).
//
// Transport: `azwallBridge`, an object the app defines in the page before this script runs, with
// `postMessage(string)` towards the wallet and an `onmessage` handler for its replies. On Android it is an
// androidx.webkit WebMessageListener object, whose native side knows the page's real origin.
import { ContentScriptConnectionHandler } from '@aztec/wallet-sdk/extension/handlers';

type Bridge = { postMessage(data: string): void; onmessage: ((ev: { data: string }) => void) | null };

const bridge = (globalThis as unknown as { azwallBridge?: Bridge }).azwallBridge;
const marker = '__azwallContentScript';

if (bridge && !(globalThis as Record<string, unknown>)[marker] && window.top === window) {
  (globalThis as Record<string, unknown>)[marker] = true;
  const listeners: ((m: never) => void)[] = [];
  bridge.onmessage = ev => {
    let msg: unknown;
    try {
      msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data));
    } catch { // Not a protocol message (the wallet only sends JSON): nothing to relay.
      return;
    }
    for (const l of listeners) l(msg as never);
  };
  new ContentScriptConnectionHandler({
    sendToBackground: message => bridge.postMessage(JSON.stringify(message)),
    addBackgroundListener: handler => listeners.push(handler as (m: never) => void),
  }).start();
}
