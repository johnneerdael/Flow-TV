// A plugin that exercises every host function, one per entity id, for the app's instrumented tests.
import { definePlugin, fail, mb } from '@milkbeat/plugin-sdk';
import type { MetadataPage } from '@milkbeat/plugin-sdk';

const page = (id: string): MetadataPage => ({ id, blocks: [] });

async function probe(name: string): Promise<string> {
  switch (name) {
    case 'http-ok': {
      const response = await mb.http.fetch({ url: 'https://www.google.com/generate_204' });
      return `status:${response.status}`;
    }
    case 'http-denied':
      await mb.http.fetch({ url: 'https://example.org/' });
      return 'reached';
    case 'storage': {
      await mb.storage.set({ key: 'k', value: 'v' });
      const stored = await mb.storage.get({ key: 'k' });
      const missing = await mb.storage.get({ key: 'missing' });
      return `${stored.value}:${missing.value ?? 'none'}`;
    }
    case 'quota':
      await mb.storage.set({ key: 'big', value: 'x'.repeat(10_000) });
      return 'stored';
    case 'secret': {
      await mb.secrets.set({ key: 's', value: 'hidden' });
      return (await mb.secrets.get({ key: 's' })).value ?? 'none';
    }
    case 'hash':
      return (await mb.crypto.hash({ algorithm: 'SHA1', text: 'abc' })).hex;
    case 'code': {
      const source = 'globalThis.loads = (globalThis.loads || 0) + 1;';
      await mb.code.load({ key: 'counter', source });
      await mb.code.load({ key: 'counter', source });
      return `loads:${(globalThis as Record<string, unknown>).loads}`;
    }
    case 'browser': {
      const session = await mb.browser.open({ html: 'assets/page.html', baseUrl: 'https://milkbeat.invalid/' });
      try {
        return (await mb.browser.evaluate({ session: session.id, script: 'return String(window.answer());' })).value;
      } finally {
        await mb.browser.close(session);
      }
    }
    case 'sleep':
      await mb.time.sleep({ ms: 50 });
      return 'slept';
    case 'env':
      return `api:${(await mb.env.get()).apiVersion}`;
    case 'warm':
      return (await mb.storage.get({ key: 'warm' })).value ?? 'cold';
    case 'throw':
      throw new Error('boom');
    case 'loop':
      for (;;) {
        /* runs until the host's time limit stops it */
      }
    case 'unavailable':
      fail('UNAVAILABLE', 'not here', { userMessage: 'Not available' });
    default:
      fail('NOT_FOUND', `no probe ${name}`);
  }
}

definePlugin({
  lifecycle: {
    async warmUp() {
      await mb.storage.set({ key: 'warm', value: 'warm' });
    },
  },
  metadata: {
    async home(request) {
      return page(`home:${request.filterId ?? 'all'}`);
    },
    async entity(request) {
      return page(await probe(request.entity.providerId));
    },
  },
  audio: {
    async resolve(request) {
      return {
        url: `https://www.google.com/${request.track.ref.providerId}`,
        cacheKey: request.track.ref.providerId,
        renditionId: 'r1',
        mimeType: 'audio/mp4',
        expiresInMs: 60_000,
      };
    },
  },
});
