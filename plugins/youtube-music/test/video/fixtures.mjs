// Recorded YouTube responses for the video-page tests, served from test/fixtures/video; an unrecorded
// request fails the test. `RECORD=1` records the missing ones live, sanitized: no tracking or session
// data, no client IP, comment and chat authors replaced. Existing ones are kept, because a page's
// continuation tokens only replay against the recording that issued them; delete the directory to
// record everything afresh.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { liveHttp, loadPlugin } from '@milkbeat/mbplugin/harness';

export const PLUGIN_DIR = fileURLToPath(new URL('../..', import.meta.url));
const FIXTURES = fileURLToPath(new URL('../fixtures/video/', import.meta.url));
const RECORD = process.env.RECORD === '1';

/** A made-up visitor id, so no request depends on (or records) a real one. */
const VISITOR = JSON.stringify({ value: 'CgtGaXh0dXJlVmlzaQ%3D%3D', at: Date.now() });

export function fixtureName(request) {
  const url = new URL(request.url);
  const body = request.body ? JSON.parse(request.body) : {};
  delete body.context;
  const endpoint = url.pathname.replace(/^\/(youtubei\/v1\/)?/, '').replace(/\//g, '_');
  const key = `${url.host}${url.pathname}?${url.searchParams.get('q') ?? ''}|${JSON.stringify(body)}`;
  return `${endpoint}-${createHash('sha1').update(key).digest('hex').slice(0, 12)}.json`;
}

const DROPPED = new Set([
  'responseContext',
  'trackingParams',
  'clickTrackingParams',
  'loggingDirectives',
  'loggingContext',
  'watchEndpointSupportedOnesieConfig',
  'visitorData',
  'topbar',
  'adSlots',
  'playerOverlays',
  'engagementPanels',
  'menu',
  'accessibility',
  'accessibilityData',
  'contextMenuEndpoint',
  'contextMenuAccessibility',
  'beforeContentButtons',
  'authorExternalChannelId',
  'channelCommand',
  'authorButtonA11y',
  'liveChatStreamingResponseExtension',
  // Bulk the pages never read: menus, hover overlays, share and playlist-edit commands, Shorts.
  'menuButton',
  'thumbnailHoverOverlayToggleActionsViewModel',
  'thumbnailHoverOverlayViewModel',
  'themedPalette',
  'signalServiceEndpoint',
  'addToPlaylistCommand',
  'playlistEditEndpoint',
  'shareEntityServiceEndpoint',
  'panelLoadingStrategy',
  'shortsLockupViewModel',
  'reelItemRenderer',
  'lottieData',
  'richThumbnail',
  'inlinePlaybackEndpoint',
  'expandableMetadata',
  'merchandiseShelfRenderer',
  'videoActions',
  'subscribeButton',
  'topLevelButtons',
  'styleRuns',
]);

const CHAT_PANELS = ['actionPanel', 'itemList', 'header', 'ticker', 'participantsList', 'popoutMessage', 'clientMessages', 'engagementPanel'];
const IPV4 = /\b\d{1,3}(\.\d{1,3}){3}\b/g;

function sanitize(json) {
  let people = 0;
  const person = () => ++people;
  // Of the entity store, only the comments themselves are read; the rest repeats their authors.
  const batch = json?.frameworkUpdates?.entityBatchUpdate;
  if (batch) batch.mutations = (batch.mutations ?? []).filter((mutation) => mutation.payload?.commentEntityPayload);
  const chat = json?.continuationContents?.liveChatContinuation;
  if (chat) {
    for (const panel of CHAT_PANELS) delete chat[panel];
    chat.actions = (chat.actions ?? []).filter((action) => {
      const item = action.addChatItemAction?.item ?? {};
      return item.liveChatTextMessageRenderer || item.liveChatPaidMessageRenderer || item.liveChatMembershipItemRenderer;
    });
  }
  const visit = (node) => {
    if (Array.isArray(node)) return node.map(visit);
    if (node && typeof node === 'object') {
      const out = {};
      for (const [key, value] of Object.entries(node)) {
        if (DROPPED.has(key)) continue;
        out[key] = visit(value);
      }
      const entity = out.commentEntityPayload;
      if (entity) {
        const n = person();
        const { isVerified, isCreator, isArtist } = entity.author ?? {};
        entity.author = {
          displayName: `@viewer${n}`,
          channelId: `UCviewer${n}`,
          avatarThumbnailUrl: `https://yt3.ggpht.com/viewer${n}=s88`,
          isVerified,
          isCreator,
          isArtist,
        };
        delete entity.avatar;
        if (entity.properties?.content) entity.properties.content = { content: `Comment text ${n}` };
      }
      for (const key of ['liveChatTextMessageRenderer', 'liveChatPaidMessageRenderer', 'liveChatMembershipItemRenderer']) {
        const message = out[key];
        if (!message) continue;
        const n = person();
        message.authorName = { simpleText: `@viewer${n}` };
        message.authorPhoto = { thumbnails: [{ url: `https://yt4.ggpht.com/viewer${n}=s64`, width: 64, height: 64 }] };
        if (message.message) message.message = { runs: [{ text: `Chat message ${n}` }] };
      }
      return out;
    }
    return typeof node === 'string' ? node.replace(IPV4, '0.0.0.0') : node;
  };
  return visit(json);
}

async function record(request, hosts) {
  const response = await liveHttp(request, hosts);
  let body;
  try {
    body = sanitize(JSON.parse(response.body));
  } catch {
    body = response.body;
  }
  mkdirSync(FIXTURES, { recursive: true });
  writeFileSync(`${FIXTURES}${fixtureName(request)}`, JSON.stringify({ status: response.status, url: request.url.split('?')[0], body }));
  return { status: response.status, url: response.url, headers: {}, body: typeof body === 'string' ? body : JSON.stringify(body) };
}

function replay(request) {
  const file = `${FIXTURES}${fixtureName(request)}`;
  if (!existsSync(file)) {
    const error = new Error(`No fixture ${fixtureName(request)} for ${request.url}`);
    error.code = 'NOT_FOUND';
    throw error;
  }
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  return { status: saved.status, url: request.url, headers: {}, body: typeof saved.body === 'string' ? saved.body : JSON.stringify(saved.body) };
}

/** The plugin with recorded responses (or live ones, recorded, under `RECORD=1`); [log] collects its requests. */
export function offlinePlugin(log = []) {
  let hosts = [];
  const plugin = loadPlugin(PLUGIN_DIR, {
    storage: { visitor: VISITOR },
    http: (request) => {
      log.push(request);
      return RECORD && !existsSync(`${FIXTURES}${fixtureName(request)}`) ? record(request, hosts) : replay(request);
    },
  });
  hosts = plugin.manifest.permissions.network;
  return plugin;
}

/** The plugin against YouTube itself, for `LIVE=1` smoke tests. */
export function livePlugin(log = []) {
  let hosts = [];
  const plugin = loadPlugin(PLUGIN_DIR, {
    storage: { visitor: VISITOR },
    http: (request) => {
      log.push(request);
      return liveHttp(request, hosts);
    },
  });
  hosts = plugin.manifest.permissions.network;
  return plugin;
}

/** Every item of every collection on a page. */
export const itemsOf = (page) => page.blocks.filter((block) => block.type === 'collection').flatMap((block) => block.items);
