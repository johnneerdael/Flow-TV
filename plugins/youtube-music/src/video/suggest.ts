// Typeahead, as the app's parseSearchSuggestions read it: `[query, [[text, …, entity?], …]]`, historically
// wrapped in a JSONP callback, so the payload is read from the first bracket to the last.
import type { Suggestions } from '@milkbeat/plugin-sdk';

export function mapSuggestions(body: string): Suggestions {
  const start = body.indexOf('[');
  const end = body.lastIndexOf(']');
  if (start < 0 || end <= start) return { queries: [] };
  let root: unknown;
  try {
    root = JSON.parse(body.slice(start, end + 1));
  } catch {
    return { queries: [] };
  }
  const rows = Array.isArray(root) && Array.isArray(root[1]) ? (root[1] as unknown[]) : [];
  const seen = new Set<string>();
  const queries: string[] = [];
  for (const row of rows) {
    const text = typeof row === 'string' ? row : Array.isArray(row) && typeof row[0] === 'string' ? row[0] : undefined;
    if (text && !seen.has(text.toLowerCase())) {
      seen.add(text.toLowerCase());
      queries.push(text);
    }
  }
  return { queries };
}
