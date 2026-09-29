// The signed-in account's YouTube watch history (FEhistory), read from the main site as the app's
// AccountFeedClient read it: the WEB client, signed as the account, newest first, paging on.
import type { MetadataPage } from '@milkbeat/plugin-sdk';
import { fail } from '@milkbeat/plugin-sdk';
import { WEB, type YouTubeClient } from '../innertube/clients';
import { innertube } from '../innertube/request';
import { markExpired } from '../innertube/session';
import type { Json } from '../util';
import { toEntries } from './entries';
import { collection, toItem } from './items';
import { label, listContinuation } from './json';

const HISTORY_BROWSE_ID = 'FEhistory';
export const WATCH_HISTORY_BLOCK = 'watch-history';

/** WEB is anonymous everywhere else; only the account's own feeds sign it. */
const WEB_SIGNED_IN: YouTubeClient = { ...WEB, loginSupported: true };

/** The list the history arrives in: the first page's section list, or a continuation's appended items. */
function historyEntries(response: Json): Json[] {
  const tabs: Json[] = (response?.contents?.twoColumnBrowseResultsRenderer?.tabs ?? []).map((tab: Json) => tab?.tabRenderer).filter(Boolean);
  const content = (tabs.find((tab) => tab.selected === true) ?? tabs[0])?.content;
  const lists: Json[] = content
    ? [content.sectionListRenderer?.contents, content.richGridRenderer?.contents]
    : (response?.onResponseReceivedActions ?? []).map(
        (action: Json) => (action.appendContinuationItemsAction ?? action.reloadContinuationItemsCommand)?.continuationItems,
      );
  return lists.filter(Array.isArray).flat();
}

/** Whether YouTube answered as the account; a dead cookie gets a signed-out page rather than an error. */
function loggedIn(response: Json): boolean | undefined {
  for (const service of response?.responseContext?.serviceTrackingParams ?? []) {
    for (const param of service?.params ?? []) {
      if (param?.key === 'logged_in') return param.value === '1';
    }
  }
  return undefined;
}

/** One page of the history as a single run of videos; a later page extends the same block. */
export function watchHistoryPage(response: Json): MetadataPage {
  const entries = historyEntries(response);
  const items = entries.flatMap((entry) => entry?.itemSectionRenderer?.contents ?? [entry]);
  const title = label(entries.map((entry) => entry?.itemSectionRenderer?.header?.itemSectionHeaderRenderer?.title).find(Boolean));
  return {
    id: 'youtube/library/watchHistory',
    blocks: [collection(WATCH_HISTORY_BLOCK, toEntries(items).map((entry) => toItem(entry)), title ? { title } : null)],
    nextCursor: listContinuation(entries) ?? listContinuation(items),
  };
}

export async function watchHistory(cursor: string | null | undefined): Promise<MetadataPage> {
  const response = await innertube('browse', {
    client: WEB_SIGNED_IN,
    site: 'www',
    auth: true,
    body: cursor ? { continuation: cursor } : { browseId: HISTORY_BROWSE_ID },
  });
  if (loggedIn(response) === false) {
    await markExpired();
    fail('SIGN_IN_EXPIRED', 'YouTube no longer knows this account');
  }
  return watchHistoryPage(response);
}
