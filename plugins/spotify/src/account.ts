import { fail, mb } from '@milkbeat/plugin-sdk';
import type { PluginDefinition, ProviderAccount, WebLoginResult } from '@milkbeat/plugin-sdk';
import { queryWithToken, resetCatalog, USER_AGENT } from './api';
import { totp } from './data';
import { at, number, object, text } from './util';

interface Session {
  accessToken: string;
  expiresAt: number;
  cookies: string;
  accountKey: string;
  name?: string;
  expired?: boolean;
}
let epoch = 0;
export const generation = () => epoch;
let writes = Promise.resolve();
let renewal: { epoch: number; promise: Promise<Session> } | undefined;
let anonymous: { accessToken: string; expiresAt: number } | undefined;
let signingIn: number | undefined;

function checkAccount(expected: number): void {
  if (epoch !== expected || signingIn !== undefined) fail('SIGN_IN_REQUIRED', 'The Spotify account is changing. Try again shortly.');
}

async function readSession(): Promise<Session | undefined> {
  try {
    const value = JSON.parse((await mb.secrets.get({ key: 'session' })).value ?? 'null');
    return text(value?.accessToken) && text(value?.cookies) && text(value?.accountKey) ? value as Session : undefined;
  } catch { return undefined; }
}

function persist(session: Session | undefined, expected: number): Promise<void> {
  const write = writes.then(async () => {
    if (epoch !== expected) fail('SIGN_IN_REQUIRED', 'The Spotify account changed');
    if (session) await mb.secrets.set({ key: 'session', value: JSON.stringify(session) });
    else await mb.secrets.delete({ key: 'session' });
  });
  writes = write.catch(() => {});
  return write;
}

export async function requireAccount(): Promise<void> {
  const expected = epoch;
  const session = await readSession();
  checkAccount(expected);
  if (!session) fail('SIGN_IN_REQUIRED', 'Sign in with Spotify to see your Home and library');
  if (session.expired) fail('SIGN_IN_EXPIRED', 'Sign in with Spotify again');
}

async function acquire(cookies?: string, forceData = false): Promise<{ accessToken: string; expiresAt: number }> {
  const time = await mb.http.fetch({ url: 'https://open.spotify.com/api/server-time', timeoutMs: 10_000, headers: { 'User-Agent': USER_AGENT } });
  if (time.status !== 200) fail('NETWORK', 'Spotify server time is unavailable');
  let timestamp: number | undefined;
  try { timestamp = number(at(JSON.parse(time.body), 'serverTime')); } catch { /* Reject malformed server time below. */ }
  if (!timestamp || timestamp <= 0) fail('NETWORK', 'Spotify returned invalid server time');
  const otp = await totp(timestamp, forceData);
  const response = await mb.http.fetch({
    url: `https://open.spotify.com/api/token?reason=init&productType=web-player&totp=${otp.code}&totpServer=${otp.code}&totpVer=${otp.version}`,
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json', Referer: 'https://open.spotify.com/', ...(cookies ? { Cookie: cookies } : {}) },
    timeoutMs: 15_000,
  });
  let payload: Record<string, unknown>;
  try { payload = object(JSON.parse(response.body)); } catch { payload = {}; }
  if (payload.totpVerExpired !== undefined || payload.error === 'invalid_totp') {
    if (!forceData) return acquire(cookies, true);
    fail('UNAVAILABLE', 'Spotify sign-in data needs an update. Try again later.');
  }
  if (response.status === 429) fail('RATE_LIMITED', 'Spotify is busy. Try again shortly.');
  if (response.status >= 500) fail('NETWORK', 'Spotify sign-in is temporarily unavailable');
  if (cookies && (payload.isAnonymous === true || response.status === 401)) fail('SIGN_IN_EXPIRED', 'Sign in with Spotify again');
  const token = text(payload.accessToken);
  const expiresAt = number(payload.accessTokenExpirationTimestampMs);
  if (response.status !== 200 || !token || !expiresAt || expiresAt <= Date.now() || (cookies && payload.isAnonymous !== false)) {
    fail('UNAVAILABLE', 'Spotify did not provide a usable session');
  }
  return { accessToken: token, expiresAt };
}

async function renew(session: Session): Promise<Session> {
  const expected = epoch;
  if (renewal?.epoch === expected) return renewal.promise;
  const promise = (async () => {
    try {
      const token = await acquire(session.cookies);
      const next = { ...session, ...token, expired: false };
      await persist(next, expected);
      return next;
    } catch (error) {
      if (object(error).code === 'SIGN_IN_EXPIRED') await persist({ ...session, expired: true }, expected);
      throw error;
    }
  })().finally(() => { if (renewal?.promise === promise) renewal = undefined; });
  renewal = { epoch: expected, promise };
  return promise;
}

let anonymousRenewal: Promise<{ accessToken: string; expiresAt: number }> | undefined;
export async function accessToken(rejected?: string): Promise<string> {
  const expected = epoch;
  const session = await readSession();
  checkAccount(expected);
  if (session) {
    if (session.expired) fail('SIGN_IN_EXPIRED', 'Sign in with Spotify again');
    if (session.expiresAt > Date.now() + 60_000 && session.accessToken !== rejected) return session.accessToken;
    return (await renew(session)).accessToken;
  }
  if (anonymous && anonymous.expiresAt > Date.now() + 60_000 && anonymous.accessToken !== rejected) return anonymous.accessToken;
  anonymousRenewal ??= acquire().then((next) => anonymous = next).finally(() => { anonymousRenewal = undefined; });
  return (await anonymousRenewal).accessToken;
}

const describe = (session: Session | undefined): ProviderAccount => !session ? { type: 'anonymous' } : session.expired ? { type: 'expired' } : { type: 'signedIn', key: session.accountKey, name: session.name };

async function complete(result: WebLoginResult): Promise<ProviderAccount> {
  if (result.method !== 'spotify') fail('UNSUPPORTED', 'Unknown Spotify sign-in method');
  const cookies = result.cookies.split(';').map((part) => part.trim()).find((part) => /^sp_dc=[^;\r\n]+$/.test(part));
  if (!cookies) fail('SIGN_IN_REQUIRED', 'Spotify sign-in did not finish');
  const expected = ++epoch;
  signingIn = expected;
  resetCatalog();
  try {
    const token = await acquire(cookies);
    const data = await queryWithToken('profileAttributes', {}, token.accessToken);
    const profile = object(at(data, 'me', 'profile'));
    const identity = text(profile.uri) ?? cookies;
    const accountKey = (await mb.crypto.hash({ algorithm: 'SHA256', text: `spotify:${identity}` })).hex.slice(0, 32);
    const session = { ...token, cookies, accountKey, name: text(profile.name) };
    await persist(session, expected);
    anonymous = undefined;
    return describe(session);
  } finally {
    if (signingIn === expected) signingIn = undefined;
  }
}

export const signIn: NonNullable<PluginDefinition['signIn']> = {
  complete,
  async account() { return describe(await readSession()); },
  async signOut() {
    const expected = ++epoch;
    signingIn = undefined;
    resetCatalog();
    anonymous = undefined;
    await persist(undefined, expected);
  },
};
