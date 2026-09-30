// Small helpers shared by the requests and the mapping; QuickJS has no URL or URLSearchParams.

/** A query string from [params], percent-encoded, without the undefined ones. */
export function query(params: Record<string, string | number | boolean | undefined>): string {
  return Object.entries(params)
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
}

/** The items of [list] whose [key] was not seen before, first occurrence kept. */
export function distinctBy<T>(list: T[], key: (item: T) => string): T[] {
  const seen = new Set<string>();
  return list.filter((item) => {
    const id = key(item);
    if (seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

export const isPresent = <T>(value: T | undefined | null): value is T => value !== undefined && value !== null;

/** "1 track", "20 tracks". */
export const counted = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`;
