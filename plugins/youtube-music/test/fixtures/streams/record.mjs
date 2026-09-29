// Records the offline fixtures for test/streams: real InnerTube answers, stripped to the fields the
// stream code reads and sanitized (no IPs, signatures, visitor data or session ids; every URL points
// at a fixture host). Run by hand when YouTube's shapes change: `node test/fixtures/streams/record.mjs`.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const out = dirname(fileURLToPath(import.meta.url));
const WEB_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0';
const CLIENTS = {
  VISIONOS: {
    id: '101',
    ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
    client: { clientName: 'VISIONOS', clientVersion: '1.02', osName: 'visionOS', osVersion: '26.5.23O471', deviceMake: 'Apple', deviceModel: 'RealityDevice17,1' },
  },
  WEB_REMIX: { id: '67', ua: WEB_UA, client: { clientName: 'WEB_REMIX', clientVersion: '1.20260213.01.00' } },
};

const KEEP_STREAM_PARAMS = ['expire', 'itag', 'c', 'mime', 'dur', 'n'];

function sanitizeUrl(raw, host = 'rr1---sn-fixture.googlevideo.com') {
  const url = new URL(raw);
  const kept = new URLSearchParams();
  for (const name of KEEP_STREAM_PARAMS) if (url.searchParams.has(name)) kept.set(name, url.searchParams.get(name));
  if (url.searchParams.has('n')) kept.set('n', 'nChallengeAbc123');
  return `https://${host}${url.pathname}?${kept}`;
}

function sanitizeFormat(format) {
  const { url, signatureCipher, ...rest } = format;
  const clean = { ...rest };
  if (url) clean.url = sanitizeUrl(url);
  if (signatureCipher) {
    const params = new URLSearchParams(signatureCipher);
    clean.signatureCipher = new URLSearchParams({ s: `SigChallenge${format.itag}xyz`, sp: params.get('sp') ?? 'sig', url: sanitizeUrl(params.get('url')) }).toString();
  }
  return clean;
}

function sanitizePlayer(response) {
  const streaming = response.streamingData;
  return {
    playabilityStatus: { status: response.playabilityStatus?.status, reason: response.playabilityStatus?.reason },
    streamingData: streaming && {
      expiresInSeconds: streaming.expiresInSeconds,
      adaptiveFormats: (streaming.adaptiveFormats ?? []).map(sanitizeFormat),
      hlsManifestUrl: streaming.hlsManifestUrl ? 'https://manifest.googlevideo.com/api/manifest/hls_variant/fixture/file/index.m3u8' : undefined,
      dashManifestUrl: streaming.dashManifestUrl ? 'https://manifest.googlevideo.com/api/manifest/dash/fixture' : undefined,
    },
    playbackTracking: response.playbackTracking && {
      videostatsPlaybackUrl: { baseUrl: 'https://s.youtube.com/api/stats/playback?docid=fixture&ns=yt' },
    },
    captions: response.captions && {
      playerCaptionsTracklistRenderer: {
        captionTracks: response.captions.playerCaptionsTracklistRenderer.captionTracks.map((track) => ({
          ...track,
          baseUrl: `https://www.youtube.com/api/timedtext?v=${response.videoDetails.videoId}&caps=asr&lang=${track.languageCode}${track.kind ? `&kind=${track.kind}` : ''}&fmt=srv3`,
        })),
        translationLanguages: response.captions.playerCaptionsTracklistRenderer.translationLanguages?.slice(0, 40),
      },
    },
    videoDetails: response.videoDetails && { ...response.videoDetails, shortDescription: response.videoDetails.shortDescription?.slice(0, 120) },
    playerConfig: response.playerConfig && { audioConfig: response.playerConfig.audioConfig },
  };
}

async function visitorData() {
  const body = await (await fetch('https://music.youtube.com/sw.js_data')).text();
  return JSON.parse(body.slice(5))[0][2].find((entry) => typeof entry === 'string' && /^Cg[ts]/.test(entry));
}

async function signatureTimestamp() {
  const api = await (await fetch('https://www.youtube.com/iframe_api')).text();
  const id = /player\\?\/([0-9a-f]{8})\\?\//.exec(api)[1];
  const js = await (await fetch(`https://www.youtube.com/s/player/${id}/player_ias.vflset/en_US/base.js`)).text();
  return Number(/signatureTimestamp:(\d+)/.exec(js)[1]);
}

async function player(name, videoId, site, sts) {
  const { id, ua, client } = CLIENTS[name];
  const origin = site === 'music' ? 'https://music.youtube.com' : 'https://www.youtube.com';
  const visitor = await visitorData();
  const response = await fetch(`${origin}/youtubei/v1/player?prettyPrint=false`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'user-agent': ua, 'x-youtube-client-name': id, 'x-youtube-client-version': client.clientVersion, 'x-origin': origin, 'x-goog-visitor-id': visitor },
    body: JSON.stringify({
      context: { client: { ...client, hl: 'en', gl: 'US', visitorData: visitor } },
      videoId,
      playbackContext: sts ? { contentPlaybackContext: { signatureTimestamp: sts } } : undefined,
      contentCheckOk: true,
      racyCheckOk: true,
    }),
  });
  return sanitizePlayer(await response.json());
}

async function watchNext(videoId) {
  const response = await fetch('https://www.youtube.com/youtubei/v1/next?prettyPrint=false', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'user-agent': WEB_UA },
    body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: '2.20260710.06.00', hl: 'en', gl: 'US' } }, videoId }),
  });
  const json = await response.json();
  const contents = json.contents?.twoColumnWatchNextResults?.results?.results?.contents ?? [];
  return {
    contents: {
      twoColumnWatchNextResults: {
        results: { results: { contents: contents.filter((entry) => entry.videoPrimaryInfoRenderer || entry.videoSecondaryInfoRenderer).map(stripSecondary) } },
      },
    },
    engagementPanels: (json.engagementPanels ?? [])
      .filter((panel) => panel.engagementPanelSectionListRenderer?.panelIdentifier === 'engagement-panel-macro-markers-description-chapters')
      .map(stripChapters),
  };
}

function stripChapters(panel) {
  const renderer = panel.engagementPanelSectionListRenderer;
  const items = renderer.content.macroMarkersListRenderer.contents.map(({ macroMarkersListItemRenderer: item }) => ({
    macroMarkersListItemRenderer: {
      title: item.title,
      timeDescription: item.timeDescription,
      onTap: { watchEndpoint: { startTimeSeconds: item.onTap?.watchEndpoint?.startTimeSeconds } },
    },
  }));
  return {
    engagementPanelSectionListRenderer: {
      panelIdentifier: renderer.panelIdentifier,
      content: { macroMarkersListRenderer: { contents: items } },
    },
  };
}

function stripSecondary(entry) {
  if (!entry.videoSecondaryInfoRenderer) return { videoPrimaryInfoRenderer: pick(entry.videoPrimaryInfoRenderer, ['viewCount', 'dateText', 'relativeDateText']) };
  const owner = entry.videoSecondaryInfoRenderer.owner?.videoOwnerRenderer;
  return { videoSecondaryInfoRenderer: { owner: { videoOwnerRenderer: { thumbnail: owner?.thumbnail, title: owner?.title } } } };
}

const pick = (object, keys) => Object.fromEntries(keys.filter((key) => object?.[key]).map((key) => [key, object[key]]));

const write = (name, value) => writeFileSync(join(out, name), `${JSON.stringify(value, null, 1)}\n`);

const sts = await signatureTimestamp();
write('player-music-visionos.json', await player('VISIONOS', 'g6LvR32cdyQ', 'music'));
write('player-music-web-remix.json', await player('WEB_REMIX', 'g6LvR32cdyQ', 'music', sts));
write('player-video-visionos.json', await player('VISIONOS', 'dQw4w9WgXcQ', 'www'));
write('player-live-visionos.json', await player('VISIONOS', 'rFZHOHl-L8A', 'www'));
write('next-chapters.json', await watchNext('xF_QkfZI1mM'));
const categories = encodeURIComponent(JSON.stringify(['sponsor', 'intro', 'outro', 'selfpromo', 'interaction', 'music_offtopic']));
const segments = await (await fetch(`https://sponsor.ajay.app/api/skipSegments?videoID=xF_QkfZI1mM&categories=${categories}`)).json();
write('sponsorblock.json', segments.map(({ segment, category, actionType }) => ({ segment, category, actionType })));
console.log(`recorded with signature timestamp ${sts}`);
