// Compile-time checks: `npm run check` fails if the SDK's typing stops constraining plugins.
import { definePlugin, fail, mb } from '../src/index';

definePlugin({
  metadata: {
    async home(request) {
      const response = await mb.http.fetch({ url: 'https://example.org', headers: {}, method: 'GET', followRedirects: true });
      if (response.status !== 200) fail('NETWORK', `HTTP ${response.status}`);
      return {
        id: `home:${request.filterId ?? ''}`,
        blocks: [
          {
            type: 'collection',
            id: 'quick-picks',
            header: { title: 'Quick picks' },
            layout: 'MULTI_COLUMN_LIST',
            defaultItemView: 'TRACK_ROW',
            items: [{ id: 'q1', entity: { kind: 'TRACK', providerId: 'abc' }, title: 'Aria', artists: [{ name: 'Argy' }] }],
          },
        ],
      };
    },
  },
  signIn: {
    async account() {
      return { type: 'anonymous' };
    },
  },
});

definePlugin({
  metadata: {
    // @ts-expect-error a page must have blocks
    async home() {
      return { id: 'home' };
    },
  },
});

definePlugin({
  // @ts-expect-error there is no such role
  lyrics: {},
});
