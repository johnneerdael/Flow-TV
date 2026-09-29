// Query strings of googlevideo URLs and signature ciphers, without URL or URLSearchParams.

function decode(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '));
  } catch {
    return value;
  }
}

/** Parses `a=1&b=2` into its (decoded) name/value pairs; the first of a repeated name wins. */
export function parseQuery(query: string): Record<string, string> {
  const params: Record<string, string> = {};
  for (const part of query.split('&')) {
    if (!part) continue;
    const index = part.indexOf('=');
    const name = decode(index < 0 ? part : part.slice(0, index));
    if (!(name in params)) params[name] = index < 0 ? '' : decode(part.slice(index + 1));
  }
  return params;
}

function splitUrl(url: string): { base: string; query: string; hash: string } {
  const hashIndex = url.indexOf('#');
  const hash = hashIndex < 0 ? '' : url.slice(hashIndex);
  const rest = hashIndex < 0 ? url : url.slice(0, hashIndex);
  const queryIndex = rest.indexOf('?');
  return queryIndex < 0 ? { base: rest, query: '', hash } : { base: rest.slice(0, queryIndex), query: rest.slice(queryIndex + 1), hash };
}

/** The decoded value of [name] in [url]'s query, or undefined. */
export function queryParam(url: string, name: string): string | undefined {
  return parseQuery(splitUrl(url).query)[name];
}

/** [url] with [name] set to [value], replacing an existing value in place or appending it. */
export function withQueryParam(url: string, name: string, value: string): string {
  const { base, query, hash } = splitUrl(url);
  const encoded = `${encodeURIComponent(name)}=${encodeURIComponent(value)}`;
  let replaced = false;
  const parts = query
    .split('&')
    .filter((part) => part.length > 0)
    .map((part) => {
      const index = part.indexOf('=');
      const key = decode(index < 0 ? part : part.slice(0, index));
      if (key !== name || replaced) return part;
      replaced = true;
      return encoded;
    });
  if (!replaced) parts.push(encoded);
  return `${base}?${parts.join('&')}${hash}`;
}

export interface SignatureCipher {
  url: string;
  s: string;
  /** The query parameter the solved signature goes in. */
  sp: string;
}

/** Reads a format's `signatureCipher` (or legacy `cipher`) blob. */
export function parseSignatureCipher(cipher: string): SignatureCipher | undefined {
  const params = parseQuery(cipher);
  if (!params.url || !params.s) return undefined;
  return { url: params.url, s: params.s, sp: params.sp || 'signature' };
}
