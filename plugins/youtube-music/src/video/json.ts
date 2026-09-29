// Walking the main site's JSON: its renderers move between wrappers from one response to the next,
// so these helpers search within a bounded node instead of spelling out every path.
import type { Artwork } from '@milkbeat/plugin-sdk';
import { type Json, text } from '../util';

/** Depth-first over every object under [node], parents before children; stops once [visit] returns true. */
export function walk(node: Json, visit: (object: Record<string, Json>) => boolean | void): boolean {
  if (Array.isArray(node)) {
    for (const child of node) if (walk(child, visit)) return true;
  } else if (node && typeof node === 'object') {
    if (visit(node)) return true;
    for (const key of Object.keys(node)) if (walk(node[key], visit)) return true;
  }
  return false;
}

/** The first value held under [key] anywhere below [node]. */
export function find(node: Json, key: string): Json {
  let found: Json;
  walk(node, (object) => {
    if (object[key] !== undefined) {
      found = object[key];
      return true;
    }
    return false;
  });
  return found;
}

/** The first `continuationCommand.token` below [node]: a continuation renderer, view model or button. */
export function continuationToken(node: Json): string | undefined {
  let token: string | undefined;
  walk(node, (object) => {
    const candidate = object.continuationCommand?.token;
    if (typeof candidate === 'string' && candidate) token = candidate;
    return !!token;
  });
  return token;
}

/** The token of a list's continuation entry, looking only at entries that are continuation renderers. */
export function listContinuation(entries: Json[]): string | undefined {
  for (const entry of entries) {
    const holder = entry?.continuationItemRenderer ?? entry?.continuationItemViewModel;
    if (holder) {
      const token = continuationToken(holder);
      if (token) return token;
    }
  }
  return undefined;
}

/** Text of any shape a renderer uses, trimmed; undefined when blank. */
export function label(value: Json): string | undefined {
  const result = (typeof value === 'string' ? value : text(value)).trim();
  return result || undefined;
}

/** The largest image of `{ thumbnails }`, `{ sources }` or a bare list of either. */
export function image(value: Json): Artwork | undefined {
  const list: Json[] | undefined = Array.isArray(value) ? value : (value?.thumbnails ?? value?.sources);
  if (!Array.isArray(list) || list.length === 0) return undefined;
  let best: Json;
  for (const candidate of list) {
    if (typeof candidate?.url !== 'string') continue;
    if (!best || (candidate.width ?? 0) * (candidate.height ?? 0) > (best.width ?? 0) * (best.height ?? 0)) best = candidate;
  }
  if (!best) return undefined;
  const url: string = best.url;
  return { url: url.startsWith('//') ? `https:${url}` : url };
}

/** A video's own thumbnail, without the per-request signature YouTube appends. */
export function videoArtwork(videoId: string): Artwork {
  return { url: `https://i.ytimg.com/vi/${videoId}/hq720.jpg` };
}

/** `1:02:10` → 3730 seconds; undefined for anything that is not a duration. */
export function parseDuration(value: string | undefined): number | undefined {
  if (!value || !/^\d+(:\d{1,2}){1,2}$/.test(value.trim())) return undefined;
  return value
    .trim()
    .split(':')
    .reduce((total, part) => total * 60 + Number(part), 0);
}

/** The leading number of a label such as "164 videos" or "1,234 replies". */
export function leadingCount(value: string | undefined): number | undefined {
  const match = value?.match(/\d[\d,.]*/);
  if (!match) return undefined;
  const count = Number(match[0].replace(/[,.]/g, ''));
  return Number.isFinite(count) ? count : undefined;
}

/** The channel a browse endpoint points at, when it is one (`UC…`). */
export function channelIdOf(node: Json): string | undefined {
  const id = find(node, 'browseEndpoint')?.browseId;
  return typeof id === 'string' && id.startsWith('UC') ? id : undefined;
}

/** Removes youtube.com's `/redirect?…&q=<target>` wrapper from an outbound link. */
export function unwrapRedirect(url: string): string {
  if (url.indexOf('/redirect?') < 0) return url;
  const match = url.match(/[?&]q=([^&]*)/);
  if (!match) return url;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return url;
  }
}
