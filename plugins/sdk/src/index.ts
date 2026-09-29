// The SDK every Milkbeat plugin is built with: typed plugin definitions, the `mb` host object and the
// glue the host calls into. The types come from plugin-api's schema, so they match the host exactly.
import type { MilkbeatPluginApi, PluginErrorCode } from '../generated/plugin-api';

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

/** A failure the host understands; anything else thrown reaches it as `INTERNAL`. */
export class PluginFailure extends Error {
  constructor(
    readonly code: PluginErrorCode,
    message: string,
    readonly retryAfterMs?: number,
  ) {
    super(message);
  }
}

export function fail(code: PluginErrorCode, message: string, retryAfterMs?: number): never {
  throw new PluginFailure(code, message, retryAfterMs);
}

// Provided by the host: runs one host function and resolves to its JSON response.
declare const __mbHost: (path: string, request: string) => Promise<string>;

async function callHost(path: string, request: unknown): Promise<unknown> {
  const response = await __mbHost(path, JSON.stringify(request ?? {}));
  const parsed = JSON.parse(response);
  return parsed === null || (typeof parsed === 'object' && Object.keys(parsed).length === 0) ? undefined : parsed;
}

export const mb: Host = new Proxy({} as Host, {
  get: (_, area: string) =>
    new Proxy(
      {},
      { get: (__, name: string) => (request?: unknown) => callHost(`${area}.${name}`, request) },
    ),
});

type Handler = (request?: unknown) => Promise<unknown>;

/** Registers the plugin with the host. Call it once, from the plugin's entry file. */
export function definePlugin(plugin: PluginDefinition): PluginDefinition {
  const registry = plugin as Record<string, Record<string, Handler> | undefined>;
  (globalThis as Record<string, unknown>).__mbDispatch = async (path: string, requestJson: string): Promise<string> => {
    const [area, name] = path.split('.');
    try {
      const handler = registry[area]?.[name];
      if (!handler) fail('UNSUPPORTED', `${path} is not implemented`);
      const result = await handler(requestJson ? JSON.parse(requestJson) : undefined);
      return JSON.stringify({ result: result ?? {} });
    } catch (error) {
      const failure =
        error instanceof PluginFailure
          ? { code: error.code, message: error.message, retryAfterMs: error.retryAfterMs }
          : { code: 'INTERNAL', message: error instanceof Error ? `${error.message}\n${error.stack ?? ''}` : String(error) };
      return JSON.stringify({ error: failure });
    }
  };
  return plugin;
}
