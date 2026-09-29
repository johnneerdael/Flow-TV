// The pure half of web PO Tokens, ported from the app's JavaScriptUtil, VisitorId and
// PoTokenAttestationPolicy (after Metrolist): reading BotGuard's Create and GenerateIT answers, the
// identity a streaming token binds to, and when a session must attest again.
import { base64ToBytes, bytesToBase64, utf8Decode } from '../bytes';

/** What `runBotGuard` in assets/po_token.html takes. */
export interface Challenge {
  messageId: string;
  interpreterJavascript: {
    privateDoNotAccessOrElseSafeScriptWrappedValue: string | null;
    privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: string | null;
  };
  interpreterHash: string;
  program: string;
  globalName: string;
  clientExperimentsStateBlob: string;
}

const firstString = (value: unknown): string | null =>
  Array.isArray(value) ? ((value.find((entry) => typeof entry === 'string') as string | undefined) ?? null) : null;

/** The Create answer, descrambled when YouTube scrambled it (base64, each byte plus 97). */
export function parseChallenge(raw: string): Challenge {
  const scrambled = JSON.parse(raw) as unknown[];
  let data: unknown[];
  if (scrambled.length > 1 && typeof scrambled[1] === 'string') {
    const bytes = base64ToBytes(scrambled[1]).map((byte) => (byte + 97) & 0xff);
    data = JSON.parse(utf8Decode(bytes)) as unknown[];
  } else {
    data = scrambled[0] as unknown[];
  }
  return {
    messageId: String(data[0]),
    interpreterJavascript: {
      privateDoNotAccessOrElseSafeScriptWrappedValue: firstString(data[1]),
      privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: firstString(data[2]),
    },
    interpreterHash: String(data[3]),
    program: String(data[4]),
    globalName: String(data[5]),
    clientExperimentsStateBlob: String(data[7]),
  };
}

/** The GenerateIT answer: the integrity token's bytes and how many seconds it lives. */
export function parseIntegrityToken(raw: string): { bytes: number[]; lifetimeSeconds: number } {
  const data = JSON.parse(raw) as unknown[];
  if (!Array.isArray(data) || data.length < 2) throw new Error('GenerateIT returned no integrity token');
  const token = String(data[0] ?? '');
  const lifetimeSeconds = Number(data[1]);
  if (!token.trim() || !(lifetimeSeconds > 0)) throw new Error('GenerateIT returned a degraded integrity response');
  return { bytes: base64ToBytes(token), lifetimeSeconds };
}

/** A minted token as YouTube sends it: url-safe base64, padded. */
export const poTokenFromBytes = (bytes: ArrayLike<number>): string => bytesToBase64(bytes, true);

const VISITOR_ID = /^[A-Za-z0-9_-]{11}$/;

/**
 * The 11-character visitor id inside `visitorData` (a base64url protobuf whose first field it is).
 * GVS binds a signed-out streaming token to this id, not to the blob around it; as yt-dlp does.
 */
export function visitorIdOf(visitorData: string | undefined): string | undefined {
  if (!visitorData) return undefined;
  try {
    const bytes = base64ToBytes(decodeURIComponent(visitorData));
    if (bytes.length < 13) return undefined;
    const id = utf8Decode(bytes, 2, 13);
    return VISITOR_ID.test(id) ? id : undefined;
  } catch {
    return undefined;
  }
}

export const gvsBinding = (visitorData: string): string => visitorIdOf(visitorData) ?? visitorData;

/** BgUtils: a healthy content-bound token is 110–128 characters of base64url. */
export const MIN_TRUSTED_TOKEN_LENGTH = 110;

export const tokenLength = (token: string): number => token.replace(/=+$/, '').length;
export const isLowTrust = (token: string): boolean => tokenLength(token) < MIN_TRUSTED_TOKEN_LENGTH;

/** A cold (low-trust) token is never kept: it trades a clean failure now for a 403 minutes in. */
export function shouldReattest(state: {
  forced: boolean;
  hasSession: boolean;
  expired: boolean;
  visitorChanged: boolean;
  lowTrust: boolean;
}): boolean {
  return state.forced || !state.hasSession || state.expired || state.visitorChanged || state.lowTrust;
}
