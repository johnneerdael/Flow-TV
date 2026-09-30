// A Node stand-in for Milkbeat's plugin host, for plugin tests: it loads a built plugin into a bare
// `vm` context (standard JavaScript only, like QuickJS: no URL, console, timers or fetch) and answers
// its host calls with the same rules as the app: HTTPS to granted hosts only, the {result}|{error}
// envelope, quota-bound storage. `http` can be replaced to serve recorded responses offline.
import { createHash, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import vm from 'node:vm';

function hostAllowed(host, patterns) {
  const name = host.toLowerCase();
  return patterns.some((raw) => {
    const pattern = raw.toLowerCase();
    return pattern.startsWith('*.') ? name.endsWith(pattern.slice(1)) : name === pattern;
  });
}

class HostFailure extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** Performs a request the way the app's PluginHttp does, on Node's fetch. */
export async function liveHttp(request, allowedHosts) {
  let url = new URL(request.url);
  const method = (request.method ?? 'GET').toUpperCase();
  for (let hop = 0; hop < 10; hop++) {
    if (url.protocol !== 'https:') throw new HostFailure('UNSUPPORTED', `Plugins may only use HTTPS: ${url.host}`);
    if (!hostAllowed(url.hostname, allowedHosts)) throw new HostFailure('UNSUPPORTED', `${url.hostname} is not in the plugin's permissions`);
    const response = await fetch(url, {
      method,
      headers: request.headers ?? {},
      body: method === 'GET' || method === 'HEAD' ? undefined : (request.body ?? ''),
      redirect: 'manual',
      signal: AbortSignal.timeout(Math.min(request.timeoutMs ?? 20000, 60000)),
    });
    const location = response.headers.get('location');
    if (request.followRedirects !== false && location && response.status >= 300 && response.status < 400) {
      url = new URL(location, url);
      continue;
    }
    const headers = {};
    response.headers.forEach((value, name) => {
      headers[name] = name === 'set-cookie' ? response.headers.getSetCookie().join('\n') : value;
    });
    return { status: response.status, url: url.toString(), headers, body: await response.text() };
  }
  throw new HostFailure('NETWORK', 'Too many redirects');
}

/**
 * Loads `<pluginDir>/build/<entry>` and returns `call(path, request)`, which resolves to the plugin's
 * result or throws an Error with `.code` set from the plugin's error.
 */
export function loadPlugin(pluginDir, options = {}) {
  const manifest = JSON.parse(readFileSync(join(pluginDir, 'manifest.json'), 'utf8'));
  const storage = new Map(Object.entries(options.storage ?? {}));
  const secrets = new Map(Object.entries(options.secrets ?? {}));
  const settings = options.settings ?? {};
  const http = options.http ?? ((request) => liveHttp(request, manifest.permissions?.network ?? []));
  const quota = manifest.permissions?.storage ?? 1048576;
  const log = options.log ?? (() => {});
  const codeCache = options.codeCache ?? new Map();
  const context = vm.createContext({});

  const handlers = {
    'http.fetch': (request) => http(request),
    'storage.get': ({ key }) => ({ value: storage.get(key) }),
    'storage.set': ({ key, value }) => {
      const next = new Map(storage).set(key, value);
      const size = [...next].reduce((sum, [k, v]) => sum + k.length + v.length, 0);
      if (size > quota) throw new HostFailure('UNSUPPORTED', `Storage quota of ${quota} bytes exceeded`);
      storage.set(key, value);
      return {};
    },
    'storage.delete': ({ key }) => (storage.delete(key), {}),
    'secrets.get': ({ key }) => ({ value: secrets.get(key) }),
    'secrets.set': ({ key, value }) => (secrets.set(key, value), {}),
    'secrets.delete': ({ key }) => (secrets.delete(key), {}),
    'crypto.hash': ({ algorithm, text }) => ({ hex: createHash(algorithm === 'SHA1' ? 'sha1' : 'sha256').update(text).digest('hex') }),
    'crypto.hmac': ({ algorithm, keyHex, messageHex }) => ({
      hex: createHmac(algorithm === 'SHA1' ? 'sha1' : 'sha256', Buffer.from(keyHex, 'hex')).update(Buffer.from(messageHex, 'hex')).digest('hex'),
    }),
    'code.load': ({ key, source }) => {
      const code = codeCache.get(key) ?? source;
      if (code === undefined) return { loaded: false };
      codeCache.set(key, code);
      vm.runInContext(code, context, { filename: `code-${key}.js` });
      return { loaded: true };
    },
    'assets.read': ({ path }) => ({ text: readFileSync(join(pluginDir, path), 'utf8') }),
    'env.get': () => ({ apiVersion: 2, appVersion: 'harness', locale: 'en-US', region: 'US', deviceClass: 'tv', pluginVersion: manifest.version }),
    'log.write': ({ level, message }) => (log(level, message), {}),
    'settings.get': () => Object.entries(settings).map(([key, value]) => ({ key, value })),
    'time.sleep': ({ ms }) => new Promise((done) => setTimeout(() => done({}), Math.min(ms, 60000))),
    ...(options.hostOverrides ?? {}),
  };

  context.__mbHost = async (path, requestJson) => {
    const handler = handlers[path];
    try {
      if (!handler) throw new HostFailure('UNSUPPORTED', `No host function ${path}`);
      return JSON.stringify({ result: await handler(JSON.parse(requestJson)) });
    } catch (error) {
      return JSON.stringify({ error: { code: error.code ?? 'NETWORK', message: error.message } });
    }
  };

  vm.runInContext(readFileSync(join(pluginDir, 'build', manifest.entry ?? 'plugin.js'), 'utf8'), context, { filename: manifest.entry ?? 'plugin.js' });

  return {
    manifest,
    storage,
    secrets,
    async call(path, request) {
      const envelope = JSON.parse(await context.__mbDispatch(path, JSON.stringify(request ?? {})));
      if (envelope.error) {
        const error = new Error(`${path}: ${envelope.error.code} ${envelope.error.message}`);
        Object.assign(error, envelope.error);
        throw error;
      }
      return envelope.result;
    },
  };
}
