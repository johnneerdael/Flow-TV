// The web store sign-in: the host's captured cookies and the session endpoint's values become the
// stored session, a token about to lapse is read again through the host's web view, and a store
// that answers with an anonymous session signs the account out.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { offlinePlugin, pathIs } from './helpers.mjs';

const COOKIES = '__Secure-next-auth.session-token=fixture-cookie; __Host-next-auth.csrf-token=fixture-csrf';
const inTenMinutes = () => String(Date.now() + 600_000);
const extracted = (overrides = {}) => ({ accessToken: 'web-token', expires: inTenMinutes(), anon: 'false', user: '1234', name: 'listener', ...overrides });
const stored = (plugin) => JSON.parse(plugin.plugin.secrets.get('session'));

describe('sign-in', () => {
  test('the captured session is kept, named, and keyed by a hash of the user id', async () => {
    const signingIn = offlinePlugin({ secrets: {} });
    const account = await signingIn.call('signIn.complete', { method: 'beatport', cookies: COOKIES, extracted: extracted() });
    assert.equal(account.type, 'signedIn');
    assert.equal(account.name, 'listener');
    assert.match(account.key, /^[0-9a-f]{32}$/);
    assert.ok(!account.key.includes('1234'));
    assert.deepEqual([stored(signingIn).accessToken, stored(signingIn).cookies], ['web-token', COOKIES]);
  });

  test('when the page moved on before its values were read, the host’s web view reads them again', async () => {
    const refreshes = [];
    const signingIn = offlinePlugin({
      secrets: {},
      hostOverrides: { 'signIn.refresh': (request) => (refreshes.push(request), { method: 'beatport', cookies: COOKIES, extracted: extracted() }) },
    });
    const account = await signingIn.call('signIn.complete', { method: 'beatport', cookies: COOKIES, extracted: {} });
    assert.equal(account.type, 'signedIn');
    assert.deepEqual(refreshes, [{ method: 'beatport', cookies: COOKIES }]);
  });

  test('an anonymous store session or missing cookie is no sign-in', async () => {
    const anonymous = offlinePlugin({
      secrets: {},
      hostOverrides: { 'signIn.refresh': () => ({ method: 'beatport', cookies: COOKIES, extracted: extracted({ anon: 'true' }) }) },
    });
    await assert.rejects(anonymous.call('signIn.complete', { method: 'beatport', cookies: COOKIES, extracted: extracted({ anon: 'true' }) }), { code: 'SIGN_IN_REQUIRED' });
    await assert.rejects(anonymous.call('signIn.complete', { method: 'beatport', cookies: 'other=1', extracted: extracted() }), { code: 'SIGN_IN_REQUIRED' });
    assert.equal(anonymous.plugin.secrets.get('session'), undefined);
  });
});

describe('the token', () => {
  const lapsing = (expiresAt) => ({ session: JSON.stringify({ accessToken: 'old-token', expiresAt, cookies: COOKIES, accountKey: 'k', name: 'listener' }) });

  test('one about to lapse is read again, once, keeping the store’s rolled cookies and the account’s key', async () => {
    let refreshes = 0;
    const plugin = offlinePlugin({
      secrets: lapsing(Date.now() + 30_000),
      hostOverrides: {
        'signIn.refresh': async () => {
          refreshes += 1;
          await new Promise((done) => setTimeout(done, 5));
          return { method: 'beatport', cookies: `${COOKIES}; rolled=1`, extracted: extracted({ accessToken: 'new-token', user: '', name: '' }) };
        },
      },
    });
    await Promise.all([plugin.call('metadata.home', {}), plugin.call('metadata.home', {})]);
    assert.equal(refreshes, 1);
    assert.ok(plugin.calls.every((call) => call.authorization === 'Bearer new-token'));
    assert.deepEqual([stored(plugin).cookies.endsWith('rolled=1'), stored(plugin).accountKey, stored(plugin).name], [true, 'k', 'listener']);
  });

  test('a refused token is read again and the request retried with it', async () => {
    const plugin = offlinePlugin({
      secrets: lapsing(Date.now() + 600_000),
      routes: [[(call) => call.path === '/v4/catalog/genres/' && call.authorization === 'Bearer old-token', { status: 401, body: { detail: 'expired' } }]],
      hostOverrides: { 'signIn.refresh': () => ({ method: 'beatport', cookies: COOKIES, extracted: extracted({ accessToken: 'new-token' }) }) },
    });
    await plugin.call('metadata.home', {});
    assert.deepEqual(
      plugin.calls.filter(pathIs('/v4/catalog/genres/')).map((call) => call.authorization),
      ['Bearer old-token', 'Bearer new-token'],
    );
  });

  test('a store that has signed the account out expires the session', async () => {
    const plugin = offlinePlugin({
      secrets: lapsing(Date.now() - 1),
      hostOverrides: { 'signIn.refresh': () => ({ method: 'beatport', cookies: COOKIES, extracted: extracted({ accessToken: 'anon-token', anon: 'true' }) }) },
    });
    await assert.rejects(plugin.call('metadata.home', {}), { code: 'SIGN_IN_REQUIRED' });
    assert.deepEqual(await plugin.call('signIn.account', {}), { type: 'expired' });
    assert.equal(plugin.calls.length, 0);
  });
});
