// Who the plugin is to YouTube: the anonymous visitor identity, the listener's locale, and the signed-in
// account when there is one. The account lives in the host's sealed secrets and is read once per start.
import { fail, mb } from '@milkbeat/plugin-sdk';
import { parseCookies } from '../util';

export interface AccountSession {
  cookie: string;
  visitorData?: string;
  dataSyncId?: string;
  name?: string;
  avatarUrl?: string;
  expired?: boolean;
}

const SESSION_KEY = 'session';
const VISITOR_KEY = 'visitor';
const VISITOR_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const VISITOR_DATA = /^Cg[ts]/;

let cachedSession: AccountSession | null | undefined;
let cachedVisitor: string | undefined;

export async function accountSession(): Promise<AccountSession | undefined> {
  if (cachedSession === undefined) {
    const stored = (await mb.secrets.get({ key: SESSION_KEY })).value;
    cachedSession = stored ? (JSON.parse(stored) as AccountSession) : null;
  }
  return cachedSession ?? undefined;
}

/** The session when it can make authenticated calls: signed in, not expired, with a SAPISID cookie. */
export async function usableSession(): Promise<AccountSession | undefined> {
  const session = await accountSession();
  return session && !session.expired && parseCookies(session.cookie).SAPISID ? session : undefined;
}

export async function saveSession(session: AccountSession): Promise<void> {
  cachedSession = session;
  await mb.secrets.set({ key: SESSION_KEY, value: JSON.stringify(session) });
}

export async function clearSession(): Promise<void> {
  cachedSession = null;
  await mb.secrets.delete({ key: SESSION_KEY });
}

export async function markExpired(): Promise<void> {
  const session = await accountSession();
  if (session && !session.expired) await saveSession({ ...session, expired: true });
}

/** The anonymous visitor id YouTube issues through `sw.js_data`; kept for a week, as the app did. */
export async function visitorData(): Promise<string> {
  if (cachedVisitor) return cachedVisitor;
  const stored = (await mb.storage.get({ key: VISITOR_KEY })).value;
  if (stored) {
    const { value, at } = JSON.parse(stored) as { value: string; at: number };
    if (Date.now() - at < VISITOR_MAX_AGE_MS) return (cachedVisitor = value);
  }
  const response = await mb.http.fetch({ url: 'https://music.youtube.com/sw.js_data' });
  if (response.status !== 200) fail('NETWORK', `sw.js_data answered ${response.status}`);
  const entries: unknown[] = JSON.parse(response.body.slice(5))[0][2];
  const value = entries.find((entry): entry is string => typeof entry === 'string' && VISITOR_DATA.test(entry));
  if (!value) fail('NETWORK', 'No visitor data in sw.js_data');
  await mb.storage.set({ key: VISITOR_KEY, value: JSON.stringify({ value, at: Date.now() }) });
  return (cachedVisitor = value);
}

export interface Locale {
  hl: string;
  gl: string;
}

let cachedLocale: Locale | undefined;

/** The listener's language and region, from the TV's locale. */
export async function locale(): Promise<Locale> {
  if (!cachedLocale) {
    const environment = await mb.env.get();
    cachedLocale = { hl: environment.locale.split('-')[0] || 'en', gl: environment.region || 'US' };
  }
  return cachedLocale;
}

/** Forgets what `settingsChanged` or a sign-in makes stale. */
export function resetCaches(): void {
  cachedLocale = undefined;
}
