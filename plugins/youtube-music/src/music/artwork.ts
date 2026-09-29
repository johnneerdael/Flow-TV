// Track artwork at the size a row shows it, ported from the app's ThumbnailUrlResolver: a video's
// ytimg still becomes its hq720 frame, a Google CDN cover is asked for at the row's size.

const YOUTUBE_VIDEO_THUMBNAIL = /(?:https?:)?\/\/(?:i\d*\.ytimg\.com|img\.youtube\.com)\/(?:vi|vi_webp)\/([^/?#]+)\/[^/?#]+/;
const GOOGLE_CDN_SIZE = /w\d+-h\d+/g;
const GOOGLE_CDN_PARAM_START = /=(?:w|s|h)/;

function isGoogleCdn(url: string): boolean {
  return url.includes('googleusercontent.com') || url.includes('ggpht.com');
}

function videoThumbnail(videoId: string, raw: string): string {
  const id = YOUTUBE_VIDEO_THUMBNAIL.exec(raw)?.[1] || videoId.trim();
  if (!id) return raw;
  return raw.toLowerCase().includes('maxresdefault') ? `https://i.ytimg.com/vi/${id}/maxresdefault.jpg` : `https://i.ytimg.com/vi/${id}/hq720.jpg`;
}

function resizeGoogleCdn(raw: string, size: number): string {
  if (/w\d+-h\d+/.test(raw)) return raw.replace(GOOGLE_CDN_SIZE, `w${size}-h${size}`);
  const start = GOOGLE_CDN_PARAM_START.exec(raw)?.index;
  const base = start !== undefined ? raw.slice(0, start) : raw;
  return `${base}=w${size}-h${size}-p-l90-rj`;
}

/** The artwork of track [videoId] from its served thumbnail [raw], at [size] pixels where resizable. */
export function musicThumbnail(videoId: string, raw: string | undefined, size = 1080): string {
  const url = raw?.trim() ?? '';
  const id = videoId.trim();
  if (!url) return id ? `https://i.ytimg.com/vi/${id}/hq720.jpg` : '';
  if (YOUTUBE_VIDEO_THUMBNAIL.test(url)) return videoThumbnail(id, url);
  if (isGoogleCdn(url)) return resizeGoogleCdn(url, size);
  return url;
}
