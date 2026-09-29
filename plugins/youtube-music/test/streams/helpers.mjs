// Shared test plumbing for the streams area: bundling a TypeScript module for its pure functions, the
// recorded fixtures, and an offline plugin whose InnerTube, SponsorBlock and player-script answers
// come from those fixtures.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { loadPlugin } from '@milkbeat/mbplugin/harness';
import { build } from 'esbuild';

const here = dirname(fileURLToPath(import.meta.url));
export const pluginDir = join(here, '..', '..');

/** Imports a module of src/ (TypeScript) for its pure functions. */
export async function importSource(path) {
  const result = await build({
    entryPoints: [join(pluginDir, 'src', path)],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    mainFields: ['module', 'main'],
    write: false,
    logLevel: 'error',
  });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
}

export const fixture = (name) => JSON.parse(readFileSync(join(pluginDir, 'test', 'fixtures', 'streams', name), 'utf8'));

export const PLAYER_ID = 'fb50cd46';
export const STS = 20719;

/** A stand-in for prepared solvers: `n` gains a prefix, a signature is reversed. */
export const FAKE_SOLVERS = `var __solvers = { player: '${PLAYER_ID}', n: function (n) { return 'solved_' + n; }, sig: function (s) { return s.split('').reverse().join(''); } };`;

const unplayable = { playabilityStatus: { status: 'UNPLAYABLE', reason: 'No fixture for this client' } };

/**
 * An offline plugin. [players] maps a clientName (or `clientName@site`) to the /player answer;
 * [prepared] seeds the code cache with FAKE_SOLVERS and the stored player state; [botguard] answers
 * BotGuard's Create and GenerateIT.
 */
export function offlinePlugin({
  players = {},
  next = {},
  sponsor,
  prepared = false,
  secrets = {},
  hostOverrides,
  botguard = false,
  playerJs = `var x={signatureTimestamp:${STS}};`,
} = {}) {
  const requests = [];
  const http = async (request) => {
    const url = new URL(request.url);
    const body = request.body ? JSON.parse(request.body) : undefined;
    requests.push({ ...request, host: url.host, path: url.pathname, json: body });
    const ok = (payload, status = 200) => ({ status, url: request.url, headers: {}, body: typeof payload === 'string' ? payload : JSON.stringify(payload) });
    if (url.pathname === '/sw.js_data') return ok(`)]}'\n[["x",null,["CgtGaXh0dXJlSWQxMSiAgICAgAY%3D"]]]`);
    if (url.pathname === '/iframe_api') return ok(`var scriptUrl = 'https:\\/\\/www.youtube.com\\/s\\/player\\/${PLAYER_ID}\\/www-widgetapi.vflset\\/www-widgetapi.js';`);
    if (url.pathname.endsWith('/base.js')) return ok(playerJs);
    if (url.pathname === '/youtubei/v1/player') {
      const name = body.context.client.clientName;
      const site = url.host.startsWith('music') ? 'music' : 'www';
      return ok(players[`${name}@${site}`] ?? players[name] ?? unplayable);
    }
    if (url.pathname === '/youtubei/v1/next') return ok(next);
    if (url.host === 'sponsor.ajay.app') return sponsor ? ok(sponsor) : ok('Not Found', 404);
    if (url.pathname === '/api/jnn/v1/Create' && botguard) return ok([['msg', [null, 'interpreter()'], null, 'hash', 'program-1', 'bgGlobal', null, 'blob']]);
    if (url.pathname === '/api/jnn/v1/GenerateIT' && botguard) return ok([Buffer.from('integrity').toString('base64url'), 43200]);
    if (url.host.endsWith('googlevideo.com')) return ok('', 200);
    if (url.pathname.startsWith('/api/stats/')) return ok('', 204);
    return ok('', 404);
  };
  const storage = prepared ? { 'streams.player': JSON.stringify({ id: PLAYER_ID, sts: STS, checkedAt: 0 }) } : {};
  const codeCache = new Map(prepared ? [[`player:${PLAYER_ID}:0.8.0`, FAKE_SOLVERS]] : []);
  const logs = [];
  const plugin = loadPlugin(pluginDir, { http, storage, secrets, codeCache, hostOverrides, log: (level, message) => logs.push(`${level} ${message}`) });
  return { plugin, requests, logs, codeCache, playerCalls: () => requests.filter((r) => r.path === '/youtubei/v1/player').map((r) => r.json.context.client.clientName) };
}

/**
 * A fake of the host browser running assets/po_token.html: the page's three functions answer with
 * canned values, so the plugin's attestation flow (Create, GenerateIT, minting) runs end to end.
 */
export function fakeBrowser({ tokenLength = 120 } = {}) {
  const calls = [];
  const sessions = new Map();
  let next = 0;
  return {
    calls,
    overrides: {
      'browser.open': ({ html, baseUrl }) => {
        const id = `session-${++next}`;
        const page = vm.createContext({
          navigator: { userAgent: 'FakeWebView/1.0' },
          runBotGuard: async (challenge) => `botguard-response-for-${challenge.program}`,
          createPoTokenMinter: async (bytes) => {
            page.minterBytes = bytes;
            return 'ok';
          },
          obtainPoToken: async (identifier) => Array.from({ length: Math.ceil((tokenLength * 3) / 4) }, (_, i) => (identifier[i % identifier.length] + i) & 255),
        });
        sessions.set(id, page);
        calls.push({ op: 'open', html, baseUrl, id });
        return { id };
      },
      'browser.evaluate': async ({ session, script }) => {
        const page = sessions.get(session);
        if (!page) throw Object.assign(new Error(`No browser session ${session}`), { code: 'NOT_FOUND' });
        calls.push({ op: 'evaluate', session, script });
        const value = await vm.runInContext(`(async () => { ${script} })()`, page);
        return { value: typeof value === 'string' ? value : JSON.stringify(value ?? null) };
      },
      'browser.close': ({ id }) => {
        sessions.delete(id);
        calls.push({ op: 'close', id });
        return {};
      },
    },
  };
}
