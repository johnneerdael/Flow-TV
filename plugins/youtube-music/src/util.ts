// Small helpers for walking InnerTube's JSON, whose shapes are deep and never guaranteed.
import type { Artwork } from '@milkbeat/plugin-sdk';

// biome-ignore lint: InnerTube responses are untyped by nature.
export type Json = any;

/** The value at [path] under [node], or undefined when any step is missing. */
export function dig(node: Json, ...path: (string | number)[]): Json {
  let current = node;
  for (const step of path) {
    if (current === null || current === undefined) return undefined;
    current = current[step];
  }
  return current;
}

/** The text of a `{ runs: [{ text }] }` or `{ simpleText }` value. */
export function text(value: Json): string {
  if (!value) return '';
  if (typeof value.simpleText === 'string') return value.simpleText;
  if (Array.isArray(value.runs)) return value.runs.map((run: Json) => run.text ?? '').join('');
  if (typeof value.content === 'string') return value.content;
  return '';
}

/** The largest thumbnail among `{ thumbnails: [...] }`, or of any nested renderer holding one. */
export function artwork(value: Json): Artwork | undefined {
  const thumbnails: Json[] | undefined =
    value?.thumbnails ??
    value?.thumbnail?.thumbnails ??
    value?.musicThumbnailRenderer?.thumbnail?.thumbnails ??
    value?.croppedSquareThumbnailRenderer?.thumbnail?.thumbnails;
  if (!Array.isArray(thumbnails) || thumbnails.length === 0) return undefined;
  const best = thumbnails.reduce((a, b) => ((b.width ?? 0) * (b.height ?? 0) > (a.width ?? 0) * (a.height ?? 0) ? b : a));
  const url: string | undefined = best.url;
  if (!url) return undefined;
  return { url: url.startsWith('//') ? `https:${url}` : url };
}

/** Parses a `Cookie` header into its name/value pairs. */
export function parseCookies(cookie: string): Record<string, string> {
  const cookies: Record<string, string> = {};
  for (const part of cookie.split(';')) {
    const index = part.indexOf('=');
    if (index > 0) cookies[part.slice(0, index).trim()] = part.slice(index + 1).trim();
  }
  return cookies;
}

/** A query string from [params], percent-encoded; QuickJS has no URLSearchParams. */
export function query(params: Record<string, string | number | boolean | undefined>): string {
  return Object.entries(params)
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
}
