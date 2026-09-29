// Web PO Tokens from BotGuard, run in the host's hidden browser (ported from the app's PoTokenGenerator,
// WebPoTokenSession and PoTokenWebView). The page cannot reach the network, so the plugin calls
// BotGuard's Create and GenerateIT itself and hands the answers in. Optional by design: without a
// browser (the Node harness, a TV without a working WebView) every mint answers undefined and the
// ladder carries on with the clients that need no token.
import { mb } from '@milkbeat/plugin-sdk';
import { utf8Encode } from '../bytes';
import { gvsBinding, isLowTrust, parseChallenge, parseIntegrityToken, poTokenFromBytes, shouldReattest } from './botguard';

const PAGE = 'assets/po_token.html';
const ORIGIN = 'https://www.youtube.com/';
const API_KEY = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
const STATE_KEY = 'streams.botguard';
const STREAMING_TOKEN_ATTEMPTS = 3;
const EXPIRY_MARGIN_MS = 10 * 60 * 1000;

export interface PoTokens {
  /** Bound to the video id; goes in the /player request. */
  player: string;
  /** Bound to the visitor id; goes on the stream URLs as `pot`. */
  streaming: string;
}

/** The attested browser session, kept in storage so warmUp's attestation serves later calls too. */
interface Attestation {
  session: string;
  visitor: string;
  streaming: string;
  lowTrust: boolean;
  expiresAt: number;
}

let attestation: Attestation | undefined;
let loaded = false;
let unavailable = false;
let forceReattest = false;
let inFlight: Promise<Attestation> | undefined;

async function evaluate(session: string, script: string, timeoutMs = 20_000): Promise<string> {
  return (await mb.browser.evaluate({ session, script, timeoutMs })).value;
}

async function botguard(method: 'Create' | 'GenerateIT', body: string, userAgent: string): Promise<string> {
  const response = await mb.http.fetch({
    url: `https://www.youtube.com/api/jnn/v1/${method}`,
    method: 'POST',
    headers: {
      'user-agent': userAgent,
      accept: 'application/json',
      'content-type': 'application/json+protobuf',
      'x-goog-api-key': API_KEY,
      'x-user-agent': 'grpc-web-javascript/0.1',
    },
    body,
  });
  if (response.status !== 200) throw new Error(`BotGuard ${method} answered ${response.status}`);
  return response.body;
}

async function mintIn(session: string, identifier: string): Promise<string> {
  const bytes = JSON.parse(await evaluate(session, `return await obtainPoToken(${JSON.stringify(utf8Encode(identifier))});`)) as number[];
  return poTokenFromBytes(bytes);
}

/** One full attestation in a fresh page: Create, run BotGuard, GenerateIT, create the minter. */
async function attestPage(): Promise<{ session: string; expiresAt: number }> {
  const { id: session } = await mb.browser.open({ html: PAGE, baseUrl: ORIGIN, timeoutMs: 15_000 });
  try {
    const userAgent = await evaluate(session, 'return navigator.userAgent;');
    const challenge = parseChallenge(await botguard('Create', JSON.stringify([REQUEST_KEY]), userAgent));
    const response = await evaluate(session, `return await runBotGuard(${JSON.stringify(challenge)});`, 30_000);
    const integrity = parseIntegrityToken(await botguard('GenerateIT', JSON.stringify([REQUEST_KEY, response]), userAgent));
    await evaluate(session, `return await createPoTokenMinter(${JSON.stringify(integrity.bytes)});`);
    return { session, expiresAt: Date.now() + integrity.lifetimeSeconds * 1000 - EXPIRY_MARGIN_MS };
  } catch (error) {
    await mb.browser.close({ id: session }).catch(() => undefined);
    throw error;
  }
}

async function closeAttestation(): Promise<void> {
  const previous = attestation;
  attestation = undefined;
  await mb.storage.delete({ key: STATE_KEY });
  if (previous) await mb.browser.close({ id: previous.session }).catch(() => undefined);
}

/**
 * Attests until the streaming token is trusted (110+ characters), at most three times, as the
 * desktop minters do; a still-cold token is used for now and forces a redo next time.
 */
async function attest(visitor: string): Promise<Attestation> {
  await closeAttestation();
  let result: Attestation | undefined;
  for (let attempt = 0; attempt < STREAMING_TOKEN_ATTEMPTS; attempt++) {
    if (result) await mb.browser.close({ id: result.session }).catch(() => undefined);
    const page = await attestPage();
    const streaming = await mintIn(page.session, gvsBinding(visitor));
    result = { ...page, visitor, streaming, lowTrust: isLowTrust(streaming) };
    if (!result.lowTrust) break;
  }
  attestation = result as Attestation;
  await mb.storage.set({ key: STATE_KEY, value: JSON.stringify(attestation) });
  return attestation;
}

async function current(visitor: string): Promise<Attestation> {
  if (!loaded) {
    loaded = true;
    const stored = (await mb.storage.get({ key: STATE_KEY })).value;
    if (stored) attestation = JSON.parse(stored) as Attestation;
  }
  const forced = forceReattest;
  forceReattest = false;
  const stale = shouldReattest({
    forced,
    hasSession: !!attestation,
    expired: !attestation || Date.now() >= attestation.expiresAt,
    visitorChanged: attestation?.visitor !== visitor,
    lowTrust: attestation?.lowTrust ?? false,
  });
  if (!stale && attestation) return attestation;
  inFlight ??= attest(visitor).finally(() => {
    inFlight = undefined;
  });
  return inFlight;
}

/**
 * The player and streaming tokens for [videoId], bound to [visitor]; undefined when BotGuard or the
 * browser cannot deliver. A session that vanished (the host closed its browser) attests once more.
 */
export async function mintPoTokens(videoId: string, visitor: string): Promise<PoTokens | undefined> {
  if (unavailable) return undefined;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const session = await current(visitor);
      return { player: await mintIn(session.session, videoId), streaming: session.streaming };
    } catch (error) {
      const code = (error as { code?: string }).code;
      if (code === 'UNSUPPORTED') {
        unavailable = true;
        return undefined;
      }
      await mb.log.write({ level: 'WARN', message: `PO Token mint failed: ${error instanceof Error ? error.message : String(error)}` });
      await closeAttestation();
    }
  }
  return undefined;
}

/** GVS refused a token: the next mint attests again instead of reusing it. */
export function reportTokenRejected(): void {
  forceReattest = true;
}

/** warmUp: attest ahead of the first video that needs the web clients, as the app prewarmed. */
export async function prewarmPoTokens(visitor: string): Promise<void> {
  if (unavailable) return;
  try {
    await current(visitor);
  } catch (error) {
    if ((error as { code?: string }).code === 'UNSUPPORTED') unavailable = true;
  }
}
