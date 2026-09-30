import { fail } from '@milkbeat/plugin-sdk';
import type { EntityRef } from '@milkbeat/plugin-sdk';

export type JsonObject = Record<string, unknown>;
export const object = (value: unknown): JsonObject => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : {};
export const array = (value: unknown): unknown[] => Array.isArray(value) ? value : [];
export const text = (value: unknown): string | undefined => typeof value === 'string' && value ? value : undefined;
export const number = (value: unknown): number | undefined => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
export function at(value: unknown, ...keys: string[]): unknown {
  return keys.reduce((current, key) => object(current)[key], value);
}
export const items = (value: unknown) => array(at(value, 'items'));
export const label = (value: unknown) => text(at(value, 'transformedLabel')) ?? text(at(value, 'translatedBaseText')) ?? text(at(value, 'text'));

/** Pagination is bound to its initiating entity/query and filter. */
export function offset(cursor: string | null | undefined, scope: string): number {
  if (!cursor) return 0;
  try {
    const value = JSON.parse(cursor);
    if (value.scope === scope && Number.isSafeInteger(value.offset) && value.offset > 0) return value.offset;
  } catch { /* Invalid cursors follow the same typed failure as a mismatched cursor. */ }
  return fail('UNSUPPORTED', 'This Spotify page cursor does not belong to the requested page');
}

export function nextCursor(scope: string, start: number, count: number, total: unknown): string | undefined {
  const end = start + count;
  return count > 0 && end < (number(total) ?? end) ? JSON.stringify({ scope, offset: end }) : undefined;
}

export function uri(ref: EntityRef): string {
  const expected = ref.kind === 'MIX' ? 'playlist' : ref.kind.toLowerCase();
  if (ref.providerId === 'spotify:collection:tracks' && ref.kind === 'PLAYLIST') return ref.providerId;
  if (!new RegExp(`^spotify:${expected}:[A-Za-z0-9]{22}$`).test(ref.providerId)) {
    fail('UNSUPPORTED', `Invalid Spotify ${expected} reference`);
  }
  return ref.providerId;
}

export function plain(value: unknown): string | undefined {
  return text(value)?.replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").trim() || undefined;
}
