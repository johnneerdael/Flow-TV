// A player response's adaptive formats: what each one is, and which one plays. Ported from the app's
// MusicPlayerUtils (audio choice), MusicVideoFormats (a music video's picture) and PlayerResponse's
// format flags (xtags, DRC, original track). Pure, so the choices are unit-tested.
import type { AudioQuality, AudioTrackInfo, ByteRange, MediaFormat } from '@milkbeat/plugin-sdk';
import { base64ToBytes, readVarint, utf8Decode } from './bytes';

export interface RawRange {
  start?: string | number;
  end?: string | number;
}

/** One entry of `streamingData.adaptiveFormats`, as InnerTube sends it. */
export interface RawFormat {
  itag: number;
  url?: string;
  signatureCipher?: string;
  cipher?: string;
  mimeType: string;
  bitrate?: number;
  averageBitrate?: number;
  width?: number;
  height?: number;
  fps?: number;
  qualityLabel?: string;
  contentLength?: string;
  approxDurationMs?: string;
  lastModified?: string;
  initRange?: RawRange;
  indexRange?: RawRange;
  loudnessDb?: number;
  isDrc?: boolean;
  xtags?: string;
  audioTrack?: { id?: string; displayName?: string; audioIsDefault?: boolean; isAutoDubbed?: boolean };
  colorInfo?: { transferCharacteristics?: string };
}

const MEDIUM_BITRATE_TARGET = 128_000;
const WEBM_BOOST = 10_240;
const TAG_FIELD = 0x0a;
const VALUE_FIELD = 0x12;

/** The `xtags` blob: a base64url protobuf of `{key, value}` pairs such as `acont`, `drc` and `lang`. */
export function decodeXtags(raw: string | undefined): Record<string, string> {
  if (!raw) return {};
  const tags: Record<string, string> = {};
  try {
    const bytes = base64ToBytes(raw);
    let offset = 0;
    while (offset < bytes.length && bytes[offset] === TAG_FIELD) {
      const length = readVarint(bytes, offset + 1);
      if (!length) break;
      const end = length.next + length.value;
      if (end > bytes.length) break;
      let at = length.next;
      let key: string | undefined;
      let value: string | undefined;
      while (at < end) {
        const field = bytes[at];
        const size = readVarint(bytes, at + 1);
        if ((field !== TAG_FIELD && field !== VALUE_FIELD) || !size || size.next + size.value > end) break;
        const text = utf8Decode(bytes, size.next, size.next + size.value);
        if (field === TAG_FIELD) key = text;
        else value = text;
        at = size.next + size.value;
      }
      if (key !== undefined && value !== undefined) tags[key] = value;
      offset = end;
    }
  } catch {
    return {};
  }
  return tags;
}

export const isAudio = (format: RawFormat): boolean => format.mimeType.startsWith('audio/');
export const isVideo = (format: RawFormat): boolean => format.mimeType.startsWith('video/');

/** Dynamic-range compressed: YouTube ships a DRC twin of some tracks at almost the same bitrate. */
export function isDrc(format: RawFormat): boolean {
  return format.isDrc === true || decodeXtags(format.xtags).drc === '1';
}

/** The original (not dubbed) audio track: xtags first, then the audioTrack hints, as the app read it. */
export function isOriginal(format: RawFormat): boolean {
  const acont = decodeXtags(format.xtags).acont;
  if (acont) return acont.toLowerCase() === 'original';
  const track = format.audioTrack;
  if (!track) return true;
  if (track.isAutoDubbed === true) return false;
  if (typeof track.audioIsDefault === 'boolean') return track.audioIsDefault;
  if (track.displayName?.toLowerCase().includes('original')) return true;
  if (!track.id) return true;
  const dot = track.id.lastIndexOf('.');
  return (dot < 0 ? '4' : track.id.slice(dot + 1)) === '4';
}

export function isHdr(format: RawFormat): boolean {
  const transfer = format.colorInfo?.transferCharacteristics;
  return transfer === 'COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084' || transfer === 'COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67';
}

export function codecsOf(mimeType: string): string {
  const match = /codecs="([^"]*)"/.exec(mimeType);
  return match ? match[1] : '';
}

/** The app's codec keys: h264, vp9, vp8, hevc, av1. */
export function codecKey(mimeType: string): string {
  const mime = mimeType.toLowerCase();
  const codecs = codecsOf(mime);
  if (codecs.includes('av01')) return 'av1';
  if (codecs.includes('vp09') || codecs.includes('vp9')) return 'vp9';
  if (codecs.includes('vp08') || codecs.includes('vp8')) return 'vp8';
  if (codecs.includes('hev1') || codecs.includes('hvc1')) return 'hevc';
  if (codecs.includes('avc1')) return 'h264';
  return mime.includes('webm') ? 'vp9' : 'h264';
}

export const audioBitrate = (format: RawFormat): number =>
  format.averageBitrate && format.averageBitrate > 0 ? format.averageBitrate : (format.bitrate ?? 0);

/** Formats in the listener's language, else the original track, else all; as preferredAudioFormats did. */
export function preferredAudioFormats(formats: RawFormat[], language: string | null | undefined): RawFormat[] {
  const wanted = (language ?? '').trim().toLowerCase();
  const originals = () => formats.filter(isOriginal);
  if (wanted && wanted !== 'original') {
    const matches = formats.filter((format) => {
      const id = (format.audioTrack?.id ?? '').toLowerCase();
      const name = (format.audioTrack?.displayName ?? '').toLowerCase();
      return id === wanted || id.startsWith(wanted) || name.includes(wanted);
    });
    if (matches.length) return matches;
  }
  const original = originals();
  return original.length ? original : formats;
}

function best<T>(items: T[], score: (item: T) => number): T | undefined {
  let chosen: T | undefined;
  let chosenScore = -Infinity;
  for (const item of items) {
    const value = score(item);
    if (value > chosenScore) {
      chosen = item;
      chosenScore = value;
    }
  }
  return chosen;
}

/**
 * The music audio stream: audio only, never auto-dubbed, in the preferred language or the original,
 * then by quality — AUTO and HIGH take the best (webm/opus gets a 10 KB/s edge), MEDIUM the one
 * closest to 128 kb/s, LOW the smallest.
 */
export function selectAudioFormat(
  formats: RawFormat[],
  options: { quality?: AudioQuality; language?: string | null; requireDirectUrl?: boolean },
): RawFormat | undefined {
  const audio = formats.filter(
    (format) => isAudio(format) && format.audioTrack?.isAutoDubbed !== true && (!options.requireDirectUrl || !!format.url),
  );
  if (!audio.length) return undefined;
  const preferred = preferredAudioFormats(audio, options.language);
  const known = preferred.filter((format) => audioBitrate(format) > 0);
  const candidates = known.length ? known : preferred;
  switch (options.quality ?? 'AUTO') {
    case 'MEDIUM':
      return best(candidates, (format) => -Math.abs(audioBitrate(format) - MEDIUM_BITRATE_TARGET));
    case 'LOW':
      return best(candidates, (format) => -audioBitrate(format));
    default:
      return best(candidates, (format) => audioBitrate(format) + (format.mimeType.includes('webm') ? WEBM_BOOST : 0));
  }
}

export const MUSIC_VIDEO_MAX_HEIGHT = 1080;

/**
 * A music video's picture: the tallest SDR stream within min(maxHeight, 1080) in a codec the TV
 * decodes, its preference order breaking ties, then the higher bitrate. H.264 is assumed when the
 * host names no codecs, as the app always counted it playable.
 */
export function selectMusicVideoFormat(formats: RawFormat[], maxHeight: number | null | undefined, codecs: string[]): RawFormat | undefined {
  const ceiling = Math.min(maxHeight ?? MUSIC_VIDEO_MAX_HEIGHT, MUSIC_VIDEO_MAX_HEIGHT);
  const order = codecs.length ? codecs.map((codec) => codec.toLowerCase()) : ['h264'];
  const candidates = formats.filter((format) => {
    const height = format.height ?? 0;
    return isVideo(format) && height >= 1 && height <= ceiling && !isHdr(format) && order.includes(codecKey(format.mimeType));
  });
  candidates.sort(
    (a, b) =>
      (b.height ?? 0) - (a.height ?? 0) ||
      order.indexOf(codecKey(a.mimeType)) - order.indexOf(codecKey(b.mimeType)) ||
      audioBitrate(b) - audioBitrate(a),
  );
  return candidates[0];
}

/** The exact encoding a URL carries: itag plus its last-modified stamp, and DRC when it is the twin. */
export function renditionId(format: RawFormat): string {
  return `${format.itag}${format.lastModified ? `:${format.lastModified}` : ''}${isDrc(format) ? ':drc' : ''}`;
}

export function toNumber(value: string | number | undefined): number | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function range(value: RawRange | undefined): ByteRange | undefined {
  const start = toNumber(value?.start);
  const end = toNumber(value?.end);
  return start === undefined || end === undefined ? undefined : { start, end };
}

/** What the host shows and picks audio tracks by: the dub's id, name and language, original, DRC. */
export function audioTrackInfo(format: RawFormat): AudioTrackInfo | undefined {
  const track = format.audioTrack;
  const tags = decodeXtags(format.xtags);
  const drc = isDrc(format);
  if (!track && !tags.lang && !drc) return undefined;
  const id = track?.id ?? tags.lang ?? 'default';
  return {
    id: drc ? `${id}:drc` : id,
    name: track?.displayName,
    language: tags.lang ?? (track?.id ? track.id.split('.')[0] : undefined),
    original: isOriginal(format),
    drc,
  };
}

/** A resolved format as the host's MediaFormat; [url] is the playable URL, already deciphered. */
export function toMediaFormat(format: RawFormat, url: string, headers: Record<string, string> = {}): MediaFormat {
  const audio = isAudio(format);
  return {
    id: renditionId(format),
    type: audio ? 'AUDIO' : 'VIDEO',
    url,
    mimeType: format.mimeType.split(';')[0].trim(),
    codecs: codecsOf(format.mimeType) || undefined,
    width: format.width,
    height: format.height,
    fps: format.fps,
    bitrate: format.bitrate,
    averageBitrate: format.averageBitrate,
    contentLength: toNumber(format.contentLength),
    durationMs: toNumber(format.approxDurationMs),
    initRange: range(format.initRange),
    indexRange: range(format.indexRange),
    qualityLabel: format.qualityLabel,
    hdr: !audio && isHdr(format),
    audioTrack: audio ? audioTrackInfo(format) : undefined,
    headers,
  };
}
