// Beatport serves every image at any size from geo-media.beatport.com through a `{w}x{h}` template;
// the TV asks for large ones. Portraits keep their landscape aspect, whatever size is asked.
import type { Artwork } from '@milkbeat/plugin-sdk';
import type { Image } from '../api/types';

/** Covers on the player and page headers. */
export const LARGE = 1400;
/** Cards and rows. */
export const CARD = 600;

const TEMPLATE = '{w}x{h}';

/** [template] (a `dynamic_uri` or `image_url`) at [size], or undefined without one. */
export function sized(template: string | null | undefined, size: number): Artwork | undefined {
  if (!template) return undefined;
  return { url: template.split(TEMPLATE).join(`${size}x${size}`) };
}

export function artwork(image: Image | null | undefined, size: number): Artwork | undefined {
  if (!image) return undefined;
  return sized(image.dynamic_uri, size) ?? (image.uri ? { url: image.uri } : undefined);
}

/** An image Beatport lists only at a fixed size, such as a playlist's release covers. */
export function fixed(url: string | null | undefined, size: number): Artwork | undefined {
  if (!url) return undefined;
  return { url: url.replace(/\/image_size\/\d+x\d+\//, `/image_size/${size}x${size}/`) };
}
