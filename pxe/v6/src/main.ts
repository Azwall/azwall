// Entry point of the PXE host page. Native code loads this page in a hidden web view and talks to it
// through `azwallHost.receive` (see @azwall/pxe-shared/bridge).
import { Buffer } from 'buffer';

import { Bridge, detectNativePort, errorMessage } from '@azwall/pxe-shared';

import { createHandlers } from './api';
import { attachLog, log } from './log';

(globalThis as { Buffer?: typeof Buffer }).Buffer ??= Buffer;

declare global {
  // eslint-disable-next-line no-var
  var azwallHost: { aztec: string; receive(json: string): void } | undefined;
}

const AZTEC_VERSION = '6.0.0-rc.3';

// Outside the apps (desktop browser, tests) there is no native side: collect what would be sent to it.
const outbox: string[] = ((globalThis as { azwallOutbox?: string[] }).azwallOutbox ??= []);
const port = detectNativePort() ?? { send: (json: string) => outbox.push(json) };
const bridge = new Bridge(port, createHandlers);
attachLog(bridge);
globalThis.azwallHost = { aztec: AZTEC_VERSION, receive: json => bridge.receive(json) };

// Android may freeze a background web view right away: persist the memory-first stores first.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'hidden') return;
  bridge.invokeLocal('flush').catch(err => log('store', `flush on hide failed: ${errorMessage(err)}`, 'warn'));
});

log('host', `PXE host for Aztec ${AZTEC_VERSION} loaded`);
bridge.emit('ready', { aztec: AZTEC_VERSION });
