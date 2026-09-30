// The Beatport account: where the API's bearer token comes from, and how the host sees the account.
// Requests only ever ask the current TokenSource; the source that signs in with Beatport's own app
// client (and refreshes) replaces the stored-session one without the requests changing.
import type { PluginDefinition, ProviderAccount } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';

/** What the sign-in keeps sealed in the host's secrets. */
export interface Session {
  accessToken: string;
  /** Epoch milliseconds after which the token is no longer sent. */
  expiresAt?: number;
  refreshToken?: string;
  /** A stable, credential-free key for the account, such as a hash of its user id. */
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

export async function saveSession(session: Session): Promise<void> {
  await mb.secrets.set({ key: SESSION_KEY, value: JSON.stringify(session) });
}

const usable = (session: Session | undefined): session is Session =>
  !!session && !session.expired && (session.expiresAt === undefined || session.expiresAt > Date.now());

/** A token the sign-in stored; a refused or lapsed one marks the session expired, as nothing refreshes it. */
export const storedSessionSource: TokenSource = {
  async token() {
    const session = await readSession();
    return usable(session) ? session.accessToken : undefined;
  },
  async rejected(token) {
    const session = await readSession();
    if (session && session.accessToken === token) await saveSession({ ...session, expired: true });
    return undefined;
  },
  session: readSession,
  async signOut() {
    await mb.secrets.delete({ key: SESSION_KEY });
  },
};

let source: TokenSource = storedSessionSource;

export function tokenSource(): TokenSource {
  return source;
}

export function setTokenSource(next: TokenSource): void {
  source = next;
}

async function describe(session: Session | undefined): Promise<ProviderAccount> {
  if (!session) return { type: 'anonymous' };
  if (!usable(session)) return { type: 'expired' };
  const key = session.accountKey ?? (await mb.crypto.hash({ algorithm: 'SHA256', text: session.accessToken })).hex.slice(0, 32);
  return { type: 'signedIn', key, name: session.name };
}

export const signIn: NonNullable<PluginDefinition['signIn']> = {
  // TODO(coordinator): sign in with Beatport's app client and store the Session; manifest `signIn` is empty until then.
  async complete() {
    return fail('UNSUPPORTED', 'Beatport sign-in is not available yet');
  },
  async account() {
    return describe(await source.session());
  },
  async signOut() {
    await source.signOut();
  },
};
