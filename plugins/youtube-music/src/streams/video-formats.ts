// Which of a video's resolved formats the host is offered, and in what order. Pure.
import { audioBitrate, codecKey, isDrc, isOriginal, type RawFormat } from './formats';

type Resolved = { format: RawFormat; url: string };

/**
 * The picture formats this TV can play: within [maxHeight] and in one of [codecs] (the host's decoder
 * list, app codec keys such as h264/vp9/av1/hevc). A filter that would leave nothing is not applied,
 * so an unusual ladder still plays at some quality. Tallest first, then the codec order, then bitrate.
 */
export function filterVideoFormats(formats: Resolved[], maxHeight: number | null | undefined, codecs: string[]): Resolved[] {
  const order = codecs.map((codec) => codec.toLowerCase());
  let chosen = formats;
  if (maxHeight) {
    const within = chosen.filter(({ format }) => (format.height ?? 0) <= maxHeight);
    if (within.length) chosen = within;
  }
  if (order.length) {
    const decodable = chosen.filter(({ format }) => order.includes(codecKey(format.mimeType)));
    if (decodable.length) chosen = decodable;
  }
  const rank = (format: RawFormat) => {
    const index = order.indexOf(codecKey(format.mimeType));
    return index < 0 ? order.length : index;
  };
  return [...chosen].sort(
    (a, b) => (b.format.height ?? 0) - (a.format.height ?? 0) || rank(a.format) - rank(b.format) || audioBitrate(b.format) - audioBitrate(a.format),
  );
}

/**
 * Every audio format, the listener's language first, then the original track, then the rest — each
 * group by bitrate, with the DRC twin after its normal copy. The host picks among them by audioTrack.
 */
export function orderAudioFormats(formats: Resolved[], language: string | null | undefined): Resolved[] {
  const wanted = (language ?? '').trim().toLowerCase();
  const group = ({ format }: Resolved) => {
    const id = (format.audioTrack?.id ?? '').toLowerCase();
    const name = (format.audioTrack?.displayName ?? '').toLowerCase();
    if (wanted && wanted !== 'original' && (id === wanted || id.startsWith(wanted) || name.includes(wanted))) return 0;
    return isOriginal(format) ? 1 : 2;
  };
  const drc = ({ format }: Resolved) => (isDrc(format) ? 1 : 0);
  return [...formats].sort((a, b) => group(a) - group(b) || drc(a) - drc(b) || audioBitrate(b.format) - audioBitrate(a.format));
}
