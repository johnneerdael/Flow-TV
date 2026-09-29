// Sign-in: the host runs Google's login in its web view and hands over the cookies and the ytcfg
// values; the plugin keeps them sealed and reports the account the way the host expects.
import type { PluginDefinition, ProviderAccount, WebLoginResult } from '@milkbeat/plugin-sdk';
import { fail, mb } from '@milkbeat/plugin-sdk';
import { WEB_REMIX } from './innertube/clients';
import { innertube } from './innertube/request';
import { type AccountSession, accountSession, clearSession, resetCaches, saveSession } from './innertube/session';
import { artwork, dig, parseCookies, text } from './util';

/** A key that changes whenever another account signs in, and carries no credentials. */
async function accountKey(session: AccountSession): Promise<string> {
  if (session.dataSyncId) return session.dataSyncId;
  return (await mb.crypto.hash({ algorithm: 'SHA256', text: session.cookie })).hex.slice(0, 32);
}

async function describe(session: AccountSession | undefined): Promise<ProviderAccount> {
  if (!session) return { type: 'anonymous' };
  if (session.expired) return { type: 'expired' };
  return {
    type: 'signedIn',
    key: await accountKey(session),
    name: session.name,
    avatar: session.avatarUrl ? { url: session.avatarUrl } : undefined,
  };
}

async function complete(result: WebLoginResult): Promise<ProviderAccount> {
  if (!parseCookies(result.cookies).SAPISID) fail('SIGN_IN_REQUIRED', 'The sign-in did not finish');
  const session: AccountSession = {
    cookie: result.cookies,
    visitorData: result.extracted?.visitorData || undefined,
    dataSyncId: result.extracted?.dataSyncId?.split('||')[0] || undefined,
  };
  await saveSession(session);
  resetCaches();
  const menu = await innertube('account/account_menu', { client: WEB_REMIX, auth: true });
  const header = dig(menu, 'actions', 0, 'openPopupAction', 'popup', 'multiPageMenuRenderer', 'header', 'activeAccountHeaderRenderer');
  const named: AccountSession = { ...session, name: text(header?.accountName) || undefined, avatarUrl: artwork(header?.accountPhoto)?.url };
  await saveSession(named);
  return describe(named);
}

export const signIn: NonNullable<PluginDefinition['signIn']> = {
  complete,
  async account() {
    return describe(await accountSession());
  },
  async signOut() {
    await clearSession();
    resetCaches();
  },
};
