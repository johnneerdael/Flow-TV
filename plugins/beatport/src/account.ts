// The Beatport account, signed in on the web store. The store's own session (its NextAuth cookies)
// is what the host keeps; the bearer token Beatport's API takes lives ten minutes and is read again
// from the store's session endpoint, in the host's web view, whenever it is about to lapse.
import type { PluginDefinition, ProviderAccount, WebLoginResult } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';

export const SIGN_IN_METHOD = 'beatport';
const SESSION_COOKIE = '__Secure-next-auth.session-token';
/** A token this close to its end is replaced before it is sent. */
const REFRESH_MARGIN_MS = 60_000;

/** What the sign-in keeps sealed in the host's secrets. */
export interface Session {
  accessToken: string;
  /** Epoch milliseconds at which Beatport stops taking the token. */
  expiresAt?: number;
  /** The web store's cookies, which the host hands back to its web view to read a fresh token. */
  cookies?: string;
  /** A stable, credential-free key for the account: a hash of its user id. */
  accountKey?: string;
  name?: string;
  expired?: boolean;
}

export interface TokenSource {
  /** A token to send, or undefined when nobody is signed in or the session has lapsed. */
  token(): Promise<string | undefined>;
  /** Beatport refused [token]: answer another one to retry with once, or undefined to give up. */
  rejected(token: string): Promise<string | undefined>;
  session(): Promise<Session | undefined>;
  signOut(): Promise<void>;
}

const SESSION_KEY = 'session';

async function readSession(): Promise<Session | undefined> {
  const stored = (await mb.secrets.get({ key: SESSION_KEY })).value;
  if (!stored) return undefined;
  try {
    const session = JSON.parse(stored) as Session;
    return typeof session.accessToken === 'string' && session.accessToken ? session : undefined;
  } catch {
    return undefined;
  }
}

async function saveSession(session: Session): Promise<void> {
  await mb.secrets.set({ key: SESSION_KEY, value: JSON.stringify(session) });
}

const fresh = (session: Session) => session.expiresAt === undefined || session.expiresAt - REFRESH_MARGIN_MS > Date.now();

/** The session the web store's extracted values describe, or undefined when it holds no signed-in token. */
async function sessionFrom(extracted: Record<string, string> | null | undefined, cookies: string): Promise<Session | undefined> {
  const accessToken = extracted?.accessToken;
  if (!accessToken || extracted?.anon === 'true') return undefined;
  const expiresAt = Number(extracted?.expires);
  const user = extracted?.user;
  return {
    accessToken,
    expiresAt: Number.isFinite(expiresAt) && expiresAt > 0 ? expiresAt : undefined,
    cookies,
    accountKey: user ? (await mb.crypto.hash({ algorithm: 'SHA256', text: `beatport:${user}` })).hex.slice(0, 32) : undefined,
    name: extracted?.name || undefined,
  };
}

let refreshing: Promise<Session | undefined> | undefined;

/** Reads a new token through the host's web view; the session is marked expired when the store has signed it out. */
function refreshed(session: Session): Promise<Session | undefined> {
  refreshing ??= (async () => {
    try {
      const cookies = session.cookies;
      const result = cookies ? await mb.signIn.refresh({ method: SIGN_IN_METHOD, cookies }) : undefined;
      const next = cookies && result ? await sessionFrom(result.extracted, result.cookies || cookies) : undefined;
      await saveSession(next ? { ...next, accountKey: next.accountKey ?? session.accountKey, name: next.name ?? session.name } : { ...session, expired: true });
      return next;
    } finally {
      refreshing = undefined;
    }
  })();
  return refreshing;
}

const webSession: TokenSource = {
  async token() {
    const session = await readSession();
    if (!session || session.expired) return undefined;
    if (fresh(session)) return session.accessToken;
    return (await refreshed(session))?.accessToken;
  },
  async rejected(token) {
    const session = await readSession();
    if (!session || session.expired) return undefined;
    if (session.accessToken !== token) return session.accessToken;
    return (await refreshed(session))?.accessToken;
  },
  session: readSession,
  async signOut() {
    await mb.secrets.delete({ key: SESSION_KEY });
  },
};

export function tokenSource(): TokenSource {
  return webSession;
}

async function describe(session: Session | undefined): Promise<ProviderAccount> {
  if (!session) return { type: 'anonymous' };
  if (session.expired) return { type: 'expired' };
  const key = session.accountKey ?? (await mb.crypto.hash({ algorithm: 'SHA256', text: session.accessToken })).hex.slice(0, 32);
  return { type: 'signedIn', key, name: session.name };
}

async function complete(result: WebLoginResult): Promise<ProviderAccount> {
  if (!result.cookies.includes(`${SESSION_COOKIE}=`)) fail('SIGN_IN_REQUIRED', 'The Beatport sign-in did not finish');
  // The store may have moved on from the page the values were read on; its cookies still hold the session.
  const session =
    (await sessionFrom(result.extracted, result.cookies)) ??
    (await (async () => {
      const read = await mb.signIn.refresh({ method: SIGN_IN_METHOD, cookies: result.cookies });
      return sessionFrom(read.extracted, read.cookies || result.cookies);
    })());
  if (!session) fail('SIGN_IN_REQUIRED', 'Beatport did not sign this account in');
  await saveSession(session);
  return describe(session);
}

export const signIn: NonNullable<PluginDefinition['signIn']> = {
  complete,
  async account() {
    return describe(await readSession());
  },
  async signOut() {
    await webSession.signOut();
  },
};
