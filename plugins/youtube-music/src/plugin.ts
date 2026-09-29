// YouTube Music for Milkbeat. Each area lives in its own directory and exports its part of the plugin.
import { definePlugin } from '@milkbeat/plugin-sdk';
import { signIn } from './account';
import { resetCaches } from './innertube/session';
import { metadata } from './music';
import { audio, lifecycle, videoStreams } from './streams';
import { videoPages } from './video';

definePlugin({
  lifecycle: {
    ...lifecycle,
    async settingsChanged() {
      resetCaches();
    },
  },
  metadata,
  audio,
  video: { ...videoPages, ...videoStreams },
  signIn,
});
