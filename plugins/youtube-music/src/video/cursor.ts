// Cursors the video pages hand out: YouTube's continuation plus what the next page needs to read it,
// such as a playlist's position or the channel a tab's anonymous items belong to.
import { fail } from '@milkbeat/plugin-sdk';

export function encodeCursor<T extends object>(state: T): string {
  return JSON.stringify(state);
}

export function decodeCursor<T extends object>(cursor: string, required: keyof T): T {
  let state: unknown;
  try {
    state = JSON.parse(cursor);
  } catch {
    fail('NOT_FOUND', 'Unreadable cursor');
  }
  if (!state || typeof state !== 'object' || (state as Record<string, unknown>)[required as string] === undefined) {
    fail('NOT_FOUND', 'Unreadable cursor');
  }
  return state as T;
}
