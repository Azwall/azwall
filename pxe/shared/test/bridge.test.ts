import { describe, expect, it } from 'vitest';

import { Bridge, detectNativePort, errorMessage, type Message, redactUrls } from '../src/bridge';

function setup(handlers = {}) {
  const sent: Message[] = [];
  const bridge = new Bridge({ send: json => sent.push(JSON.parse(json)) }, handlers);
  return { bridge, sent };
}

const tick = () => new Promise(r => setTimeout(r, 0));

describe('Bridge', () => {
  it('answers native calls with the handler result', async () => {
    const { bridge, sent } = setup({ add: async (p: any) => p.a + p.b });
    bridge.receive(JSON.stringify({ kind: 'call', id: 7, method: 'add', params: { a: 2, b: 3 } }));
    await tick();
    expect(sent).toEqual([{ kind: 'reply', id: 7, ok: true, result: 5 }]);
  });

  it('reports handler failures and unknown methods as error replies', async () => {
    const { bridge, sent } = setup({
      boom: async () => {
        throw new Error('nope');
      },
    });
    bridge.receive(JSON.stringify({ kind: 'call', id: 1, method: 'boom', params: {} }));
    bridge.receive(JSON.stringify({ kind: 'call', id: 2, method: 'toString', params: {} }));
    await tick();
    expect(sent).toHaveLength(2);
    expect(sent).toContainEqual({ kind: 'reply', id: 1, ok: false, error: 'nope' });
    expect(sent).toContainEqual({ kind: 'reply', id: 2, ok: false, error: 'unknown method: toString' });
  });

  it('turns undefined results into null so every reply carries a result', async () => {
    const { bridge, sent } = setup({ nothing: async () => undefined });
    bridge.receive(JSON.stringify({ kind: 'call', id: 3, method: 'nothing' }));
    await tick();
    expect(sent[0]).toEqual({ kind: 'reply', id: 3, ok: true, result: null });
  });

  it('resolves and rejects calls made to native code', async () => {
    const { bridge, sent } = setup();
    const ok = bridge.callNative<string>('prove', { b64: 'AA==' });
    const ko = bridge.callNative('prove', {});
    const [first, second] = sent as Extract<Message, { kind: 'call' }>[];
    expect(first).toMatchObject({ kind: 'call', method: 'prove', params: { b64: 'AA==' } });
    bridge.receive(JSON.stringify({ kind: 'reply', id: first.id, ok: true, result: 'proof' }));
    bridge.receive(JSON.stringify({ kind: 'reply', id: second.id, ok: false, error: 'no library' }));
    await expect(ok).resolves.toBe('proof');
    await expect(ko).rejects.toThrow('prove: no library');
  });

  it('reports unreadable messages as log events', () => {
    const { bridge, sent } = setup();
    bridge.receive('{not json');
    expect(sent[0]).toMatchObject({ kind: 'event', name: 'log', data: { level: 'error' } });
  });
});

describe('detectNativePort', () => {
  it('finds the Android and iOS ports, or none', () => {
    const got: string[] = [];
    const android = detectNativePort({ AzwallAndroid: { postMessage: (j: string) => got.push(`a:${j}`) } });
    const ios = detectNativePort({ webkit: { messageHandlers: { azwall: { postMessage: (j: string) => got.push(`i:${j}`) } } } });
    android?.send('1');
    ios?.send('2');
    expect(got).toEqual(['a:1', 'i:2']);
    expect(detectNativePort({})).toBeNull();
  });

  it('keeps API keys in URL paths out of errors and logs', () => {
    expect(redactUrls('Error fetching from host https://lb.drpc.org/aztec-testnet/SECRET: 500')).toBe(
      'Error fetching from host https://lb.drpc.org/…: 500',
    );
    expect(redactUrls('node at https://rpc.example.org is down')).toBe('node at https://rpc.example.org is down');
    expect(errorMessage(new Error('request to "wss://n.io/k?key=SECRET" failed'))).toBe('request to "wss://n.io/…" failed');
    const { bridge, sent } = setup();
    bridge.emit('log', { level: 'warn', message: 'retry https://a.io/v1/KEY' });
    bridge.emit('dapp', { message: 'https://a.io/kept' });
    expect(sent.map(m => (m as { data: { message: string } }).data.message)).toEqual(['retry https://a.io/…', 'https://a.io/kept']);
  });
});
