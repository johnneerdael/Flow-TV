// From a format to a URL GVS will serve: the signature cipher solved, the `n` challenge transformed,
// and a web client's streaming PO Token attached last so the transform never sees it.
import type { RawFormat } from './formats';
import { solveN, solveSignature } from './solver';
import { parseSignatureCipher, queryParam, withQueryParam } from './urls';

/** The format's URL, deciphering a `signatureCipher`; undefined when that needs solvers not loaded. */
export function cipherResolvedUrl(format: RawFormat): string | undefined {
  if (format.url) return format.url;
  const cipher = parseSignatureCipher(format.signatureCipher ?? format.cipher ?? '');
  if (!cipher) return undefined;
  const signature = solveSignature(cipher.s);
  return signature ? withQueryParam(cipher.url, cipher.sp, signature) : undefined;
}

export const hasCipher = (format: RawFormat): boolean => !format.url && !!(format.signatureCipher || format.cipher);

export const needsNTransform = (url: string): boolean => !!queryParam(url, 'n');

/** [url] with its `n` challenge solved; unchanged without one; undefined when it cannot be solved. */
export function transformN(url: string): string | undefined {
  const challenge = queryParam(url, 'n');
  if (!challenge) return url;
  const solved = solveN(challenge);
  return solved && solved !== challenge ? withQueryParam(url, 'n', solved) : undefined;
}

export function withPoToken(url: string, poToken: string | undefined): string {
  if (!poToken || queryParam(url, 'pot')) return url;
  return withQueryParam(url, 'pot', poToken);
}

/**
 * A ladder deciphered in order, giving up on the rest once one signature fails: whether the cipher
 * resolves is a property of the player script, not of the format.
 */
export function decipherWhilePossible(formats: RawFormat[]): Array<{ format: RawFormat; url: string }> {
  let usable = true;
  const out: Array<{ format: RawFormat; url: string }> = [];
  for (const format of formats) {
    if (format.url) {
      out.push({ format, url: format.url });
    } else if (usable && hasCipher(format)) {
      const url = cipherResolvedUrl(format);
      if (url) out.push({ format, url });
      else usable = false;
    }
  }
  return out;
}
