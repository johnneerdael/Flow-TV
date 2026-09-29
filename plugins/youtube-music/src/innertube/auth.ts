// The signed-in request headers, as YouTube's own web player (and yt-dlp) send them: one hash per
// API SID cookie, each salted with the user session id, plus the account index and, for a brand
// account, the page it acts as. A bare SAPISIDHASH is refused by the TV clients ("The page needs to
// be reloaded").
import { mb } from '@milkbeat/plugin-sdk';
import { parseCookies } from '../util';
import type { AccountSession } from './session';

/** DATASYNC_ID is `USER||` for the primary account and `DELEGATED||USER` for a brand account. */
export function sessionIds(session: AccountSession): { user?: string; delegated?: string } {
  const raw = session.datasyncIdRaw ?? (session.dataSyncId ? `${session.dataSyncId}||` : undefined);
  if (!raw) return {};
  const [first, second] = raw.split('||');
  return second ? { user: second, delegated: first } : { user: first || undefined };
}

/** The API SID YouTube hashes; it falls back to the third-party one when SAPISID is missing. */
export function apiSid(cookie: string): string | undefined {
  const cookies = parseCookies(cookie);
  return cookies.SAPISID ?? cookies['__Secure-3PAPISID'];
}

export async function accountHeaders(session: AccountSession, origin: string): Promise<Record<string, string>> {
  const cookies = parseCookies(session.cookie);
  const { user, delegated } = sessionIds(session);
  const now = Math.floor(Date.now() / 1000);
  const hashes: string[] = [];
  for (const [scheme, sid] of [
    ['SAPISIDHASH', apiSid(session.cookie)],
    ['SAPISID1PHASH', cookies['__Secure-1PAPISID']],
    ['SAPISID3PHASH', cookies['__Secure-3PAPISID']],
  ] as const) {
    if (!sid) continue;
    const text = user ? `${user} ${now} ${sid} ${origin}` : `${now} ${sid} ${origin}`;
    const { hex } = await mb.crypto.hash({ algorithm: 'SHA1', text });
    hashes.push(user ? `${scheme} ${now}_${hex}_u` : `${scheme} ${now}_${hex}`);
  }
  const headers: Record<string, string> = {
    cookie: session.cookie,
    authorization: hashes.join(' '),
    'x-origin': origin,
    'x-goog-authuser': session.sessionIndex ?? '0',
    'x-youtube-bootstrap-logged-in': 'true',
  };
  if (delegated) headers['x-goog-pageid'] = delegated;
  return headers;
}
