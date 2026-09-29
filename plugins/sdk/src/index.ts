// The SDK every Milkbeat plugin is built with: typed plugin definitions, the `mb` host object and the
// glue the host calls into. The types come from plugin-api's schema, so they match the host exactly.
//
// Wire protocol (the host implements the other side):
// - the host exposes `__mbHost(path, requestJson)`, resolving to `{"result": …}` or `{"error": PluginError}`;
// - the plugin exposes `__mbDispatch(path, requestJson)`, answering in the same envelope.
import { HOST_OPERATIONS } from '../generated/plugin-api';
import type { MilkbeatPluginApi, PluginError, PluginErrorCode } from '../generated/plugin-api';

export type * from '../generated/plugin-api';

type Operations = MilkbeatPluginApi['operations'];
type HostCalls = MilkbeatPluginApi['host'];

type RequestOf<O> = O extends { request: infer R } ? R : undefined;
type ResponseOf<O> = O extends { response: infer R } ? R : void;
type Call<O> = RequestOf<O> extends undefined
  ? () => Promise<ResponseOf<O>>
  : (request: RequestOf<O>) => Promise<ResponseOf<O>>;
type AreaOf<P> = P extends `${infer Area}.${string}` ? Area : never;
type Area<T, A extends string> = { [P in keyof T as P extends `${A}.${infer Name}` ? Name : never]: Call<T[P]> };

/** A plugin: one object per role, each with the operations its manifest declares. */
export type PluginDefinition = { [A in AreaOf<keyof Operations>]?: Partial<Area<Operations, A>> };

/** The host functions, grouped as `mb.http.fetch`, `mb.storage.get` and so on. */
export type Host = { [A in AreaOf<keyof HostCalls>]: Area<HostCalls, A> };

const FAILURE_BRAND = '__milkbeatPluginFailure';

/** A failure the host understands; anything else thrown reaches it as `INTERNAL`. */
export class PluginFailure extends Error {
  readonly [FAILURE_BRAND] = true;

  constructor(
    readonly code: PluginErrorCode,
    message: string,
    readonly userMessage?: string,
    readonly retryAfterMs?: number,
  ) {
    super(message);
  }
}

export function fail(code: PluginErrorCode, message: string, options: { userMessage?: string; retryAfterMs?: number } = {}): never {
  throw new PluginFailure(code, message, options.userMessage, options.retryAfterMs);
}

function isFailure(error: unknown): error is PluginFailure {
  return typeof error === 'object' && error !== null && (error as Record<string, unknown>)[FAILURE_BRAND] === true;
}

// Provided by the host: runs one host function and resolves to its envelope.
declare const __mbHost: (path: string, request: string) => Promise<string>;

type Envelope = { result?: unknown; error?: PluginError };

async function callHost(path: string, request: unknown): Promise<unknown> {
  const envelope = JSON.parse(await __mbHost(path, JSON.stringify(request ?? {}))) as Envelope;
  if (envelope.error) {
    const { code, message, userMessage, retryAfterMs } = envelope.error;
    throw new PluginFailure(code, message, userMessage ?? undefined, retryAfterMs ?? undefined);
  }
  return envelope.result;
}

function buildHost(): Host {
  const host: Record<string, Record<string, (request?: unknown) => Promise<unknown>>> = {};
  for (const path of HOST_OPERATIONS) {
    const [area, name] = path.split('.');
    (host[area] ??= {})[name] = (request?: unknown) => callHost(path, request);
  }
  return host as unknown as Host;
}

export const mb: Host = buildHost();

type Handler = (request?: unknown) => Promise<unknown>;
const hasOwn = (target: object, key: string) => Object.prototype.hasOwnProperty.call(target, key);

/** Registers the plugin with the host. Call it once, from the plugin's entry file. */
export function definePlugin(plugin: PluginDefinition): PluginDefinition {
  const registry = plugin as Record<string, Record<string, Handler>>;
  (globalThis as Record<string, unknown>).__mbDispatch = async (path: string, requestJson: string): Promise<string> => {
    const [area, name] = path.split('.');
    try {
      const role = hasOwn(registry, area) ? registry[area] : undefined;
      const handler = role && hasOwn(role, name) ? role[name] : undefined;
      if (typeof handler !== 'function') fail('UNSUPPORTED', `${path} is not implemented`);
      const result = await handler(requestJson ? JSON.parse(requestJson) : undefined);
      return JSON.stringify({ result: result ?? {} });
    } catch (error) {
      const failure: PluginError = isFailure(error)
        ? { code: error.code, message: error.message, userMessage: error.userMessage, retryAfterMs: error.retryAfterMs }
        : {
            code: 'INTERNAL',
            message: error instanceof Error ? error.message : String(error),
            detail: error instanceof Error ? error.stack : undefined,
          };
      return JSON.stringify({ error: failure });
    }
  };
  return plugin;
}
