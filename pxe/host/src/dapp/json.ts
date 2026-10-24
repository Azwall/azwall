// Reading the loosely typed values a site sends: a record or nothing, a list or nothing, never a throw.

export type Json = Record<string, unknown>;

/** `v` as a record; anything else (arrays included) as an empty one. */
export const obj = (v: unknown): Json => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : {});

/** `v` as a list; anything else as an empty one. */
export const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
