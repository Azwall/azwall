// Logs go to native code (the app's log) as `log` events; the console keeps a copy for web view debugging.
// The page's own console (Aztec's loggers write there) and uncaught errors are forwarded too: on iOS nothing else gets
// them out of the web view (Android's WebView copies the console to logcat).
import type { Bridge } from '@azwall/pxe-shared';

export type LogLevel = 'info' | 'warn' | 'error';

let bridge: Bridge | null = null;
const raw = { info: console.log.bind(console), warn: console.warn.bind(console), error: console.error.bind(console) };

export function attachLog(b: Bridge): void {
  bridge = b;
  forwardConsole();
}

export function log(scope: string, message: string, level: LogLevel = 'info'): void {
  raw[level](`[${scope}] ${message}`);
  bridge?.emit('log', { level, scope, message });
}

/** One line for any console argument: errors with their stack, objects as JSON when they can be. */
export function describe(value: unknown): string {
  if (value instanceof Error) return `${value.message}${value.stack ? `\n${value.stack}` : ''}`;
  if (typeof value !== 'object' || value === null) return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function forwardConsole(): void {
  const levels = { log: 'info', info: 'info', warn: 'warn', error: 'error' } as const;
  for (const [method, level] of Object.entries(levels) as [keyof typeof levels, LogLevel][]) {
    const original = console[method].bind(console);
    console[method] = (...args: unknown[]) => {
      original(...args);
      bridge?.emit('log', { level, scope: 'console', message: args.map(describe).join(' ').slice(0, 2000) });
    };
  }
  if (typeof addEventListener !== 'function') return;
  addEventListener('error', e => log('page', `uncaught: ${e.message} (${e.filename}:${e.lineno})`, 'error'));
  addEventListener('unhandledrejection', e => log('page', `unhandled rejection: ${describe(e.reason)}`, 'error'));
}
