import { fail, mb } from '@milkbeat/plugin-sdk';
import { array, number, object, text } from './util';

const BASE = 'https://raw.githubusercontent.com/johnneerdael/Milkbeat/spotify-data';
const TTL_MS = 6 * 60 * 60 * 1000;
type DataKind = 'hashes' | 'totp';
const pending = new Map<DataKind, Promise<unknown>>();
const loaded = new Map<DataKind, { value: unknown; expires: number }>();

function valid(kind: DataKind, value: unknown): boolean {
  if (kind === 'totp') return array(value).some((v) => Number.isInteger(atVersion(v)) && atVersion(v) > 0 && /^[0-9a-f]{2,512}$/i.test(text(object(v).keyHex) ?? '') && (text(object(v).keyHex)?.length ?? 1) % 2 === 0);
  return Object.values(object(value)).some((v) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v));
}
const atVersion = (value: unknown) => number(object(value).v) ?? 0;

async function read(kind: DataKind, force: boolean): Promise<unknown> {
  let cached: { value?: unknown; expires?: number } = {};
  try {
    const value = object(JSON.parse((await mb.storage.get({ key: `data/${kind}` })).value ?? '{}'));
    cached = { value: value.value, expires: number(value.expires) };
  } catch { /* Use the bundled public snapshot. */ }
  let fallback = cached.value;
  if (!valid(kind, fallback)) fallback = JSON.parse((await mb.assets.read({ path: `assets/${kind}.json` })).text);
  if (!force && (cached.expires ?? 0) > Date.now() && valid(kind, cached.value)) return cached.value;
  try {
    const response = await mb.http.fetch({ url: `${BASE}/${kind}.json`, timeoutMs: 2_000 });
    if (response.status === 200) {
      const value: unknown = JSON.parse(response.body);
      if (valid(kind, value)) {
        await mb.storage.set({ key: `data/${kind}`, value: JSON.stringify({ value, expires: Date.now() + TTL_MS }) });
        return value;
      }
    }
  } catch { /* Metadata remains usable during a registry outage. */ }
  if (!valid(kind, fallback)) fail('UNAVAILABLE', `Spotify ${kind} data is unavailable`);
  return fallback;
}

export function publicData(kind: DataKind, force = false): Promise<unknown> {
  const cached = loaded.get(kind);
  if (!force && cached && cached.expires > Date.now()) return Promise.resolve(cached.value);
  const active = pending.get(kind);
  if (active) return active;
  const request = read(kind, force).then((value) => {
    loaded.set(kind, { value, expires: Date.now() + TTL_MS });
    return value;
  }).finally(() => pending.delete(kind));
  pending.set(kind, request);
  return request;
}

export async function queryHash(operation: string, force = false): Promise<string> {
  const value = text(object(await publicData('hashes', force))[operation]);
  if (value && /^[a-f0-9]{64}$/.test(value)) return value;
  const bundled = JSON.parse((await mb.assets.read({ path: 'assets/hashes.json' })).text);
  const fallback = text(object(bundled)[operation]);
  return fallback ?? fail('UNSUPPORTED', `No Spotify query for ${operation}`);
}

export async function totp(timestamp: number, force = false): Promise<{ code: string; version: number }> {
  const entries = array(await publicData('totp', force)).map(object).filter((v) =>
    Number.isInteger(v.v) && Number(v.v) > 0 && /^[a-f0-9]+$/i.test(text(v.keyHex) ?? '') && (text(v.keyHex)?.length ?? 1) % 2 === 0,
  );
  const secret = entries.sort((a, b) => Number(b.v) - Number(a.v))[0];
  if (!secret || !Number.isFinite(timestamp) || timestamp <= 0) fail('UNAVAILABLE', 'Spotify token data is unavailable');
  const counter = Math.floor(timestamp / 30);
  const messageHex = Math.floor(counter / 0x100000000).toString(16).padStart(8, '0') + (counter >>> 0).toString(16).padStart(8, '0');
  const { hex } = await mb.crypto.hmac({ algorithm: 'SHA1', keyHex: String(secret.keyHex), messageHex });
  if (!/^[a-f0-9]{40}$/i.test(hex)) fail('INTERNAL', 'Invalid host HMAC response');
  const start = (parseInt(hex.slice(-2), 16) & 15) * 2;
  const code = ((parseInt(hex.slice(start, start + 8), 16) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
  return { code, version: Number(secret.v) };
}
