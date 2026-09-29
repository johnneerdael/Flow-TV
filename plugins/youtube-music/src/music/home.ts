// YouTube Music's home feed as a catalog page: its mood chips, then every shelf as served. Ported from
// the app's HomePage.fromBrowseResponse/fromContinuationResponse and YouTubeHomeMapper.
import type { FilterControl, MetadataPage } from '@milkbeat/plugin-sdk';
import { type Json, dig } from '../util';
import { firstRun, nextContinuation } from './renderers';
import { carousel, uniqueBlocks } from './shelves';

export const HOME_PAGE_ID = 'youtube-music/home';

function filters(chips: Json): FilterControl | undefined {
  if (!Array.isArray(chips)) return undefined;
  const options = chips
    .map((chip) => chip?.chipCloudChipRenderer)
    .map((chip) => ({ id: chip?.navigationEndpoint?.browseEndpoint?.params, label: firstRun(chip?.text) }))
    .filter((option): option is { id: string; label: string } => typeof option.id === 'string' && option.label !== undefined);
  return options.length > 0 ? { options } : undefined;
}

function page(sectionList: Json, withChips: boolean): MetadataPage {
  const contents: Json[] = Array.isArray(sectionList?.contents) ? sectionList.contents : [];
  return {
    id: HOME_PAGE_ID,
    blocks: uniqueBlocks(contents.map((content) => (content?.musicCarouselShelfRenderer ? carousel(content.musicCarouselShelfRenderer) : undefined))),
    filters: withChips ? filters(dig(sectionList, 'header', 'chipCloudRenderer', 'chips')) : undefined,
    nextCursor: nextContinuation(sectionList?.continuations),
  };
}

/** The first page of the home feed, with its chips. */
export function homePage(response: Json): MetadataPage {
  return page(dig(response, 'contents', 'singleColumnBrowseResultsRenderer', 'tabs', 0, 'tabRenderer', 'content', 'sectionListRenderer'), true);
}

/** A further page of the home feed; continuations carry no chips. */
export function homeContinuationPage(response: Json): MetadataPage {
  return page(dig(response, 'continuationContents', 'sectionListContinuation'), false);
}
