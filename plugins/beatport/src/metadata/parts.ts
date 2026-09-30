// A page built from several requests shows what arrived: a part that failed is left out, unless the
// failure is the account's (signed out, expired), which the whole page reports, or every part failed.
import type { PluginErrorCode } from '@milkbeat/plugin-sdk';

const PAGE_WIDE: PluginErrorCode[] = ['SIGN_IN_REQUIRED', 'SIGN_IN_EXPIRED'];

const codeOf = (reason: unknown) => (reason as { code?: PluginErrorCode } | undefined)?.code;

export async function settled<T extends readonly unknown[]>(tasks: { [K in keyof T]: Promise<T[K]> }): Promise<{ [K in keyof T]: T[K] | undefined }> {
  const results = await Promise.allSettled(tasks as readonly Promise<unknown>[]);
  const failures = results.filter((result): result is PromiseRejectedResult => result.status === 'rejected').map((result) => result.reason);
  const pageWide = failures.find((reason) => PAGE_WIDE.includes(codeOf(reason) as PluginErrorCode));
  if (pageWide !== undefined) throw pageWide;
  if (failures.length > 0 && failures.length === results.length) throw failures[0];
  return results.map((result) => (result.status === 'fulfilled' ? result.value : undefined)) as { [K in keyof T]: T[K] | undefined };
}
