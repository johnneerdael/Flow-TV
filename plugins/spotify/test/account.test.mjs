import assert from 'node:assert/strict';
import test from 'node:test';
import { ALBUM, ARTIST, SESSION, deferred, offline, op, path, renewalRoutes } from './helpers.mjs';

test('Sign-in computes the RFC 6238 token via host HMAC and keeps credentials in secrets', async () => {
  const p = offline({ secrets: {}, routes: renewalRoutes() });
  const account = await p.call('signIn.complete', { method: 'spotify', cookies: 'other=discard; sp_dc=fixture-cookie' });
  assert.equal(account.type, 'signedIn');
  assert.equal(account.name, 'Fixture Listener');
  const request = p.calls.find(path('/api/token'));
  assert.equal(request.query.totp, '287082');
  assert.equal(request.query.totpVer, '1');
  assert.equal(request.headers.Cookie, 'sp_dc=fixture-cookie');
  assert.equal(p.storage.has('session'), false);
  assert.equal(JSON.parse(p.secrets.get('session')).accessToken, 'renewed');
  assert.ok(!JSON.stringify(account).includes('fixture-cookie'));
});

test('Concurrent expired-session requests renew once', async () => {
  const p = offline({ secrets: { session: JSON.stringify({ ...SESSION, expiresAt: 1 }) }, routes: renewalRoutes() });
  await Promise.all([p.call('metadata.entity', { entity: ALBUM }), p.call('metadata.entity', { entity: ARTIST })]);
  assert.equal(p.calls.filter(path('/api/token')).length, 1);
  assert.ok(p.calls.filter((c) => c.op).every((c) => c.headers.Authorization === 'Bearer renewed'));
});

test('Logout while renewal is pending cannot restore the session', async () => {
  const started = deferred();
  const finish = deferred();
  const p = offline({ secrets: { session: JSON.stringify({ ...SESSION, expiresAt: 1 }) }, routes: renewalRoutes(async () => {
    started.resolve();
    await finish.promise;
    return { body: { accessToken: 'late', accessTokenExpirationTimestampMs: Date.now() + 3_600_000, isAnonymous: false } };
  }) });
  const pending = p.call('metadata.entity', { entity: ALBUM });
  const rejected = assert.rejects(pending, { code: 'SIGN_IN_REQUIRED' });
  await started.promise;
  await p.call('signIn.signOut');
  finish.resolve();
  await rejected;
  assert.equal(p.secrets.has('session'), false);
  assert.equal((await p.call('signIn.account')).type, 'anonymous');
});

test('Transient token failure preserves credentials; an anonymous renewal marks expiry', async () => {
  const session = { ...SESSION, expiresAt: 1 };
  const p = offline({ secrets: { session: JSON.stringify(session) }, routes: [
    [path('/api/token'), { status: 503, body: {} }], ...renewalRoutes(),
  ] });
  await assert.rejects(p.call('metadata.entity', { entity: ALBUM }), { code: 'NETWORK' });
  assert.deepEqual(JSON.parse(p.secrets.get('session')), session);
  const expired = offline({ secrets: { session: JSON.stringify(session) }, routes: renewalRoutes({ accessToken: 'anonymous', isAnonymous: true }) });
  await assert.rejects(expired.call('metadata.entity', { entity: ALBUM }), { code: 'SIGN_IN_EXPIRED' });
  assert.equal((await expired.call('signIn.account')).type, 'expired');
});

test('A rejected bearer retries once with a new token', async () => {
  let tries = 0;
  const p = offline({ routes: [
    [op('getAlbum'), () => ++tries === 1 ? { status: 401, body: {} } : { body: { data: { albumUnion: { name: 'Album', uri: ALBUM.providerId, tracksV2: { items: [], totalCount: 0 } } } } }],
    ...renewalRoutes(),
  ] });
  await p.call('metadata.entity', { entity: ALBUM });
  assert.equal(tries, 2);
  assert.equal(p.calls.filter(path('/api/token')).length, 1);
});

test('Changing accounts cannot cache the previous account under the new session', async () => {
  const started = deferred();
  const finish = deferred();
  const p = offline({ routes: renewalRoutes(async () => {
    started.resolve();
    await finish.promise;
    return { body: { accessToken: 'next-account', accessTokenExpirationTimestampMs: Date.now() + 3_600_000, isAnonymous: false } };
  }) });
  const signingIn = p.call('signIn.complete', { method: 'spotify', cookies: 'sp_dc=next-cookie' });
  await started.promise;
  await assert.rejects(p.call('metadata.entity', { entity: ALBUM }), { code: 'SIGN_IN_REQUIRED' });
  finish.resolve();
  await signingIn;
  await p.call('metadata.entity', { entity: ALBUM });
  assert.equal(p.calls.find(op('getAlbum')).headers.Authorization, 'Bearer next-account');
});

test('Malformed public-data cache shapes use bundled snapshots', async () => {
  for (const cached of ['null', '[]', '"invalid"', '{"expires":"tomorrow"}']) {
    const p = offline();
    p.storage.set('data/hashes', cached);
    assert.ok((await p.call('metadata.entity', { entity: ALBUM })).blocks.length);
  }
});

test('Unusable remote TOTP versions retain the valid bundled snapshot', async () => {
  for (const v of [undefined, 0, -1]) {
    const p = offline({ secrets: {}, routes: [
      [path('/johnneerdael/Milkbeat/spotify-data/totp.json'), { body: [{ v, keyHex: '12' }] }],
      ...renewalRoutes(),
    ] });
    assert.equal((await p.call('signIn.complete', { method: 'spotify', cookies: 'sp_dc=fixture-cookie' })).type, 'signedIn');
    assert.ok(Number(p.calls.find(path('/api/token')).query.totpVer) > 0);
    assert.equal(p.storage.has('data/totp'), false);
    assert.ok(p.calls.filter((c) => c.url.includes('raw.githubusercontent.com')).every((c) => c.timeoutMs <= 2000));
  }
});
