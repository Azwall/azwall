// Logs go to native code (the app's log) as `log` events; the console keeps a copy for web view debugging.
import type { Bridge } from '@azwall/pxe-shared';

export type LogLevel = 'info' | 'warn' | 'error';

let bridge: Bridge | null = null;

export function attachLog(b: Bridge): void {
  bridge = b;
}

export function log(scope: string, message: string, level: LogLevel = 'info'): void {
  const line = `[${scope}] ${message}`;
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
  bridge?.emit('log', { level, scope, message });
}
