#!/usr/bin/env bash
# Fetches what the solver benchmark needs into build/spike-assets (never committed): YouTube's current
# player script and a pinned yt-dlp/ejs solver release. Node then solves the same challenges, so the
# device run is checked against a reference result.
set -euo pipefail
cd "$(dirname "$0")"
EJS_VERSION=0.8.0
out=build/spike-assets
mkdir -p "$out"

player=$(curl -fsS https://www.youtube.com/iframe_api | grep -oE 'player\\?/[0-9a-f]{8}\\?/' | head -1 | grep -oE '[0-9a-f]{8}')
curl -fsS -o "$out/base.js" "https://www.youtube.com/s/player/$player/player_ias.vflset/en_US/base.js"
for file in yt.solver.lib.js yt.solver.core.js; do
  curl -fsSL -o "$out/$file" "https://github.com/yt-dlp/ejs/releases/download/$EJS_VERSION/$file"
done

node - "$out" <<'JS'
const fs = require('fs'), vm = require('vm'), dir = process.argv[2];
const ctx = vm.createContext({});
vm.runInContext(fs.readFileSync(`${dir}/yt.solver.lib.js`, 'utf8'), ctx);
vm.runInContext('var meriyah = lib.meriyah, astring = lib.astring;', ctx);
vm.runInContext(fs.readFileSync(`${dir}/yt.solver.core.js`, 'utf8'), ctx);
ctx.input = {
  type: 'player',
  player: fs.readFileSync(`${dir}/base.js`, 'utf8'),
  requests: [
    { type: 'n', challenges: ['ZdZIqFPQK-Ty8wId', 'dmAa3SiX2TeDqpxK'] },
    { type: 'sig', challenges: ['AOq0QJ8wRQIgWJbUgnE4Wt04xXsC5ceshDuUzqsI4ahBoIOHrKs2RzICIQCy7CKn8u9HqzNqtFWP7GHHsfUmr4Ut3eVZEqWbGt7rJw==AOq0QJ8wRQ'] },
  ],
};
const started = process.hrtime.bigint();
const output = vm.runInContext('jsc(input)', ctx);
const ms = Number(process.hrtime.bigint() - started) / 1e6;
fs.writeFileSync(`${dir}/expected.json`, JSON.stringify(output.responses));
console.log(`player ${fs.statSync(`${dir}/base.js`).size} bytes, node solve ${ms.toFixed(0)} ms`);
JS
echo "player $player ready in $out"
