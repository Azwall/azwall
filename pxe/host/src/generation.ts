// Stores that must start over move to a new name (a new OPFS pool directory) rather than being deleted and recreated
// under the same one: on iOS, deleting a pool directory could hang or stop halfway, and a store recreated over what
// was left refused every write. The old directory is deleted in the background, and nothing waits for it.

function generationKey(base: string): string {
  return `azwall:store-generation:${base}`;
}

function generation(base: string): number {
  try {
    return Number(localStorage.getItem(generationKey(base)) ?? 0) || 0;
  } catch { // No localStorage (private context): the store keeps its first name.
    return 0;
  }
}

/** The store's current name: `base` at first, `base-g<n>` after n start-overs. */
export function generationName(base: string): string {
  const g = generation(base);
  return g ? `${base}-g${g}` : base;
}

/** Moves the store to its next name; returns the name it leaves. */
export function nextGeneration(base: string): string {
  const old = generationName(base);
  try {
    localStorage.setItem(generationKey(base), String(generation(base) + 1));
  } catch { // Without localStorage the store keeps its name, and the start-over deletes it in place as before
  }
  return old;
}
