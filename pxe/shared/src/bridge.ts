// The message channel between a PXE host page and the native app.
//
// Every message is one JSON string, in both directions:
//   call   { kind: 'call', id, method, params }          a request (either side can call the other)
//   reply  { kind: 'reply', id, ok, result | error }      the answer to a call
//   event  { kind: 'event', name, data }                 fire-and-forget notification (logs, progress)
//
// Native → page: the app evaluates `azwallHost.receive("<json>")`.
// Page → native: Android exposes `AzwallAndroid.postMessage(json)` (@JavascriptInterface); iOS exposes
// `webkit.messageHandlers.azwall.postMessage(json)` (WKScriptMessageHandler).

export type CallMessage = { kind: 'call'; id: number; method: string; params: unknown };
export type ReplyMessage = { kind: 'reply'; id: number; ok: true; result: unknown } | { kind: 'reply'; id: number; ok: false; error: string };
export type EventMessage = { kind: 'event'; name: string; data: unknown };
export type Message = CallMessage | ReplyMessage | EventMessage;

/** Where outgoing messages go. */
export interface NativePort {
  send(json: string): void;
}

export type Handler = (params: unknown) => Promise<unknown>;
export type Handlers = Record<string, Handler>;

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return typeof err === 'string' ? err : JSON.stringify(err);
}

export class Bridge {
  #nextId = 1;
  readonly #pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void; method: string }>();

  #handlers: Handlers;

  /** `handlers` may be built from the bridge itself, for handlers that call native code back. */
  constructor(
    private readonly port: NativePort,
    handlers: Handlers | ((bridge: Bridge) => Handlers),
  ) {
    this.#handlers = typeof handlers === 'function' ? handlers(this) : handlers;
  }

  /** Runs a handler locally, as if native code had called it (e.g. a flush when the app is hidden). */
  invokeLocal(method: string, params: unknown = {}): Promise<unknown> {
    const handler = this.#handler(method);
    return handler ? handler(params) : Promise.reject(new Error(`unknown method: ${method}`));
  }

  #handler(method: string): Handler | undefined {
    return Object.hasOwn(this.#handlers, method) ? this.#handlers[method] : undefined;
  }

  /** Entry point for messages from native code. Malformed input is reported, never thrown. */
  receive(json: string): void {
    let msg: Message;
    try {
      msg = JSON.parse(json) as Message;
    } catch (err) {
      this.emit('log', { level: 'error', message: `bridge: unreadable message: ${errorMessage(err)}` });
      return;
    }
    if (msg.kind === 'call') void this.#serve(msg);
    else if (msg.kind === 'reply') this.#settle(msg);
  }

  /** Calls a native method (e.g. `prove`) and resolves with its result. */
  callNative<T>(method: string, params: unknown): Promise<T> {
    const id = this.#nextId++;
    return new Promise<T>((resolve, reject) => {
      this.#pending.set(id, { resolve: v => resolve(v as T), reject, method });
      this.#post({ kind: 'call', id, method, params });
    });
  }

  emit(name: string, data: unknown): void {
    this.#post({ kind: 'event', name, data });
  }

  async #serve(msg: CallMessage): Promise<void> {
    const handler = this.#handler(msg.method);
    try {
      if (!handler) throw new Error(`unknown method: ${msg.method}`);
      const result = await handler(msg.params ?? {});
      this.#post({ kind: 'reply', id: msg.id, ok: true, result: result ?? null });
    } catch (err) {
      this.#post({ kind: 'reply', id: msg.id, ok: false, error: errorMessage(err) });
    }
  }

  #settle(msg: ReplyMessage): void {
    const pending = this.#pending.get(msg.id);
    if (!pending) return;
    this.#pending.delete(msg.id);
    if (msg.ok) pending.resolve(msg.result);
    else pending.reject(new Error(`${pending.method}: ${msg.error}`));
  }

  #post(msg: Message): void {
    this.port.send(JSON.stringify(msg));
  }
}

type HostGlobals = {
  AzwallAndroid?: { postMessage(json: string): void };
  webkit?: { messageHandlers?: { azwall?: { postMessage(json: string): void } } };
};

/** The port of the platform the page runs in, or `null` outside the apps (tests, desktop browser). */
export function detectNativePort(g: HostGlobals = globalThis as HostGlobals): NativePort | null {
  const android = g.AzwallAndroid;
  if (android) return { send: json => android.postMessage(json) };
  const ios = g.webkit?.messageHandlers?.azwall;
  if (ios) return { send: json => ios.postMessage(json) };
  return null;
}
