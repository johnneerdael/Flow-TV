// Cursors the host hands back unchanged. A browse continuation is self-contained; a watch queue's is
// only valid with the endpoint it continues, so those travel together.
import { fail } from '@milkbeat/plugin-sdk';
import type { WatchEndpoint } from './api';

export type Cursor =
  | { type: 'browse'; continuation: string }
  | { type: 'radio'; endpoint: WatchEndpoint; continuation: string }
  | { type: 'queue'; endpoint: WatchEndpoint; continuation: string };

export function encodeCursor(cursor: Cursor): string {
  return JSON.stringify(cursor);
}

export function decodeCursor<T extends Cursor['type']>(value: string, ...types: T[]): Extract<Cursor, { type: T }> {
  let cursor: Cursor | undefined;
  try {
    cursor = JSON.parse(value) as Cursor;
  } catch {
    cursor = undefined;
  }
  if (!cursor || !types.includes(cursor.type as T) || typeof cursor.continuation !== 'string') fail('NOT_FOUND', 'The cursor is not one this plugin issued');
  return cursor as Extract<Cursor, { type: T }>;
}
