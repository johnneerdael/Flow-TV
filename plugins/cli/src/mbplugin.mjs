#!/usr/bin/env node
// mbplugin: keygen, build and pack Milkbeat plugins (plugin API v1, docs/plugin-architecture.md §3).
//
//   mbplugin keygen <key.pem>                 a new author key (ECDSA P-256); keep it secret
//   mbplugin build <plugin-dir>               bundles src/plugin.ts into build/plugin.js
//   mbplugin pack <plugin-dir> --key <pem>    writes build/<name>.mbplugin, signed with the key
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { build } from 'esbuild';
import { zipSync } from 'fflate';

const [command, target, ...rest] = process.argv.slice(2);
const option = (name) => {
  const index = rest.indexOf(`--${name}`);
  return index >= 0 ? rest[index + 1] : undefined;
};

// Every entry gets the same timestamp, so packing the same plugin twice gives the same bytes.
const FIXED_TIME = new Date('2026-01-01T00:00:00Z');

function readManifest(dir) {
  const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
  for (const field of ['format', 'api', 'id', 'name', 'version', 'versionCode', 'roles']) {
    if (manifest[field] === undefined) throw new Error(`manifest.json has no ${field}`);
  }
  return manifest;
}

function filesUnder(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

async function buildPlugin(dir) {
  const manifest = readManifest(dir);
  mkdirSync(join(dir, 'build'), { recursive: true });
  await build({
    entryPoints: [join(dir, 'src', 'plugin.ts')],
    outfile: join(dir, 'build', manifest.entry ?? 'plugin.js'),
    bundle: true,
    format: 'iife',
    target: 'es2020',
    platform: 'neutral',
    mainFields: ['module', 'main'],
    charset: 'utf8',
    legalComments: 'inline',
    logLevel: 'warning',
  });
  console.log(`built ${manifest.id} ${manifest.version}`);
}

function packPlugin(dir, keyPath) {
  const manifest = readManifest(dir);
  const entry = manifest.entry ?? 'plugin.js';
  const files = new Map();
  files.set('manifest.json', readFileSync(join(dir, 'manifest.json')));
  files.set(entry, readFileSync(join(dir, 'build', entry)));
  if (manifest.icon) files.set(manifest.icon, readFileSync(join(dir, manifest.icon)));
  for (const path of filesUnder(join(dir, 'assets'))) {
    files.set(relative(dir, path).split(sep).join('/'), readFileSync(path));
  }

  const listing = [...files.keys()]
    .sort()
    .map((path) => `${createHash('sha256').update(files.get(path)).digest('hex')}  ${path}\n`)
    .join('');
  const privateKey = createPrivateKey(readFileSync(keyPath));
  const signature = {
    algorithm: 'ECDSA_P256_SHA256',
    publicKey: createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).toString('base64'),
    signature: sign('sha256', Buffer.from(listing), { key: privateKey, dsaEncoding: 'der' }).toString('base64'),
  };

  const entries = {};
  for (const [path, bytes] of files) entries[path] = [bytes, { mtime: FIXED_TIME }];
  entries['META-INF/CONTENTS'] = [Buffer.from(listing), { mtime: FIXED_TIME }];
  entries['META-INF/SIGNATURE'] = [Buffer.from(JSON.stringify(signature)), { mtime: FIXED_TIME }];
  const name = manifest.id.split('.').pop();
  const out = option('out') ?? join(dir, 'build', `${name}.mbplugin`);
  writeFileSync(out, zipSync(entries, { level: 9 }));
  const fingerprint = createHash('sha256').update(createPublicKey(privateKey).export({ type: 'spki', format: 'der' })).digest('hex');
  console.log(`packed ${out} (${files.size} files, signer ${fingerprint.slice(0, 16)}…)`);
}

function keygen(path) {
  if (existsSync(path)) throw new Error(`${path} exists; refusing to overwrite a key`);
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  writeFileSync(path, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  console.log(`wrote ${path}`);
}

try {
  switch (command) {
    case 'keygen':
      keygen(resolve(target));
      break;
    case 'build':
      await buildPlugin(resolve(target));
      break;
    case 'pack': {
      const key = option('key');
      if (!key) throw new Error('pack needs --key <pem>');
      packPlugin(resolve(target), resolve(key));
      break;
    }
    default:
      console.error('usage: mbplugin keygen <key.pem> | build <plugin-dir> | pack <plugin-dir> --key <pem> [--out file]');
      process.exit(2);
  }
} catch (error) {
  console.error(`mbplugin: ${error.message}`);
  process.exit(1);
}
