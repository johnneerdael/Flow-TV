// Bytes, base64 and UTF-8 in plain ECMAScript: QuickJS has no atob, btoa, TextEncoder or TextDecoder,
// and YouTube's tokens and tags are base64url protobufs or byte strings.

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const LOOKUP: Record<string, number> = {};
for (let i = 0; i < ALPHABET.length; i++) LOOKUP[ALPHABET[i]] = i;
LOOKUP['-'] = 62;
LOOKUP._ = 63;

/** Decodes standard or url-safe base64, padded or not; YouTube's `.` padding is accepted too. */
export function base64ToBytes(input: string): number[] {
  const clean = input.replace(/[\s=.]/g, '');
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of clean) {
    const value = LOOKUP[char];
    if (value === undefined) throw new Error(`Invalid base64 character ${char}`);
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  return bytes;
}

/** Encodes bytes as base64; url-safe (`-`, `_`) when asked, as YouTube's PO Tokens are. */
export function bytesToBase64(bytes: ArrayLike<number>, urlSafe = false): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i];
    const b = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const c = i + 2 < bytes.length ? bytes[i + 2] : 0;
    const triple = (a << 16) | (b << 8) | c;
    out += ALPHABET[(triple >> 18) & 63] + ALPHABET[(triple >> 12) & 63];
    out += i + 1 < bytes.length ? ALPHABET[(triple >> 6) & 63] : '=';
    out += i + 2 < bytes.length ? ALPHABET[triple & 63] : '=';
  }
  return urlSafe ? out.replace(/\+/g, '-').replace(/\//g, '_') : out;
}

export function utf8Encode(text: string): number[] {
  const bytes: number[] = [];
  for (const char of text) {
    const code = char.codePointAt(0) as number;
    if (code < 0x80) bytes.push(code);
    else if (code < 0x800) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 63));
    else if (code < 0x10000) bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
    else bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 63), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
  }
  return bytes;
}

export function utf8Decode(bytes: ArrayLike<number>, start = 0, end = bytes.length): string {
  let out = '';
  let i = start;
  while (i < end) {
    const a = bytes[i++];
    let code: number;
    if (a < 0x80) code = a;
    else if (a < 0xe0) code = ((a & 31) << 6) | (bytes[i++] & 63);
    else if (a < 0xf0) code = ((a & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
    else code = ((a & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
    out += String.fromCodePoint(code);
  }
  return out;
}

/** A varint at [offset], and where the next field starts; undefined past the end. */
export function readVarint(bytes: ArrayLike<number>, offset: number): { value: number; next: number } | undefined {
  let value = 0;
  let shift = 0;
  let index = offset;
  while (index < bytes.length && shift <= 28) {
    const byte = bytes[index++];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value, next: index };
    shift += 7;
  }
  return undefined;
}

/** A client playback nonce: 16 characters of YouTube's url-safe alphabet. */
export function randomCpn(): string {
  const alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_';
  let cpn = '';
  for (let i = 0; i < 16; i++) cpn += alphabet[Math.floor(Math.random() * 64)];
  return cpn;
}
