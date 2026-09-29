// YouTube's player script solves the `n` throttling challenge and the signature cipher; yt-dlp's EJS
// solver (assets/solver, v0.8.0) finds those functions in it. Preprocessing one player takes ~13.5 s on
// the slowest TV, so it runs once per player version in warmUp, and its prepared solvers are kept in
// the host's code cache: every later start loads them as bytecode in ~70 ms
// (docs/research/plugin-runtime-spike-2026-09-29.md). Playback never waits for it unless nothing
// else can play.
import { fail, mb } from '@milkbeat/plugin-sdk';

export const SOLVER_VERSION = '0.8.0';

const PLAYER_STATE_KEY = 'streams.player';
const LIBRARY_KEY = `ejs:${SOLVER_VERSION}`;
const LIBRARY_FILES = ['assets/solver/yt.solver.lib.js', 'assets/solver/yt.solver.core.js'];

export interface PlayerState {
  id: string;
  sts?: number;
  checkedAt: number;
}

interface Solvers {
  player: string;
  n: (challenge: string) => string;
  sig: (challenge: string) => string;
}

type JscOutput = { type: string; preprocessed_player?: string; error?: string };

const solverGlobal = globalThis as unknown as {
  __solvers?: Solvers;
  jsc?: (input: unknown) => JscOutput;
};

let active: Solvers | undefined;
let cachedState: PlayerState | undefined;
const solved = new Map<string, string>();

/** The player version named in `iframe_api`, e.g. `fb50cd46`. */
export function parsePlayerId(iframeApi: string): string | undefined {
  return /player\\?\/([0-9a-f]{8})\\?\//.exec(iframeApi)?.[1];
}

/** The signature timestamp the player script was built with; web clients send it with /player. */
export function parseSignatureTimestamp(playerJs: string): number | undefined {
  const match = /(?:signatureTimestamp|sts)\s*:\s*(\d{5,})/.exec(playerJs);
  return match ? Number(match[1]) : undefined;
}

export const playerJsUrl = (id: string): string => `https://www.youtube.com/s/player/${id}/player_ias.vflset/en_US/base.js`;

export const preparedKey = (id: string): string => `player:${id}:${SOLVER_VERSION}`;

/** The cached script: runs the preprocessed player once and keeps its two solvers as `__solvers`. */
export function preparedSource(id: string, preprocessed: string): string {
  return (
    'var __solvers = (function () {\n  var _result = { n: null, sig: null };\n  (function (_result) {\n' +
    `${preprocessed}\n  })(_result);\n  _result.player = ${JSON.stringify(id)};\n  return _result;\n})();\n`
  );
}

async function fetchText(url: string): Promise<string> {
  const response = await mb.http.fetch({ url, timeoutMs: 20_000 });
  if (response.status !== 200) fail('NETWORK', `${url} answered ${response.status}`);
  return response.body;
}

async function saveState(state: PlayerState): Promise<PlayerState> {
  cachedState = state;
  await mb.storage.set({ key: PLAYER_STATE_KEY, value: JSON.stringify(state) });
  return state;
}

/** The current player: remembered, or looked up when unknown or [refresh] asks (warmUp does). */
export async function currentPlayer(refresh = false): Promise<PlayerState> {
  if (!cachedState) {
    const stored = (await mb.storage.get({ key: PLAYER_STATE_KEY })).value;
    if (stored) cachedState = JSON.parse(stored) as PlayerState;
  }
  if (cachedState && !refresh) return cachedState;
  const id = parsePlayerId(await fetchText('https://www.youtube.com/iframe_api'));
  if (!id) fail('UNAVAILABLE', 'No player version in iframe_api');
  if (cachedState?.id === id) return saveState({ ...cachedState, checkedAt: Date.now() });
  return saveState({ id, checkedAt: Date.now() });
}

/** The signature timestamp of the current player, fetching its script once when it is not known yet. */
export async function signatureTimestamp(): Promise<number | undefined> {
  const state = await currentPlayer();
  if (state.sts) return state.sts;
  const sts = parseSignatureTimestamp(await fetchText(playerJsUrl(state.id)));
  if (sts) await saveState({ ...state, sts });
  return sts;
}

function adopt(id: string): boolean {
  const candidate = solverGlobal.__solvers;
  if (!candidate || candidate.player !== id || typeof candidate.n !== 'function') return false;
  if (active !== candidate) solved.clear();
  active = candidate;
  return true;
}

async function loadLibrary(): Promise<void> {
  if (typeof solverGlobal.jsc === 'function') return;
  if (!(await mb.code.load({ key: LIBRARY_KEY })).loaded) {
    const [library, core] = await Promise.all(LIBRARY_FILES.map(async (path) => (await mb.assets.read({ path })).text));
    const source = `${library}\nvar meriyah = lib.meriyah, astring = lib.astring;\n${core}`;
    await mb.code.load({ key: LIBRARY_KEY, source });
  }
  if (typeof solverGlobal.jsc !== 'function') fail('INTERNAL', 'The EJS solver did not load');
}

/** Preprocesses [id]'s player script and caches its solvers: the expensive step, for warmUp. */
export async function preparePlayer(id: string, playerJs?: string): Promise<void> {
  await loadLibrary();
  const js = playerJs ?? (await fetchText(playerJsUrl(id)));
  const jsc = solverGlobal.jsc as (input: unknown) => JscOutput;
  const output = jsc({ type: 'player', player: js, requests: [], output_preprocessed: true });
  if (output.type !== 'result' || !output.preprocessed_player) fail('INTERNAL', `Solver failed on player ${id}: ${output.error ?? 'no output'}`);
  await mb.code.load({ key: preparedKey(id), source: preparedSource(id, output.preprocessed_player) });
  if (!adopt(id)) fail('INTERNAL', `Prepared solvers for player ${id} did not load`);
}

/**
 * Makes the solvers usable: from memory, else from the code cache. With [prepare], a cache miss runs
 * the full preprocessing inline — the last resort when no direct URL exists at all.
 */
export async function ensureSolvers(prepare = false): Promise<boolean> {
  try {
    const { id } = await currentPlayer();
    if (active?.player === id) return true;
    if ((await mb.code.load({ key: preparedKey(id) })).loaded && adopt(id)) return true;
    if (!prepare) return false;
    await mb.log.write({ level: 'WARN', message: `Preparing player ${id} on the playback path` });
    await preparePlayer(id);
    return true;
  } catch (error) {
    await mb.log.write({ level: 'WARN', message: `Solvers unavailable: ${error instanceof Error ? error.message : String(error)}` });
    return false;
  }
}

export const solversReady = (): boolean => active !== undefined;

function solve(type: 'n' | 'sig', challenge: string): string | undefined {
  if (!active) return undefined;
  const key = `${type}:${challenge}`;
  const known = solved.get(key);
  if (known !== undefined) return known;
  try {
    const result = active[type](challenge);
    if (typeof result !== 'string' || !result) return undefined;
    if (solved.size >= 500) solved.clear();
    solved.set(key, result);
    return result;
  } catch {
    return undefined;
  }
}

export const solveN = (challenge: string): string | undefined => solve('n', challenge);
export const solveSignature = (challenge: string): string | undefined => solve('sig', challenge);

/** warmUp: find the current player, remember its signature timestamp, and prepare its solvers once. */
export async function warmUpSolvers(): Promise<void> {
  const state = await currentPlayer(true);
  const cached = (await mb.code.load({ key: preparedKey(state.id) })).loaded && adopt(state.id);
  if (cached && state.sts) return;
  const js = await fetchText(playerJsUrl(state.id));
  const sts = parseSignatureTimestamp(js);
  if (sts && sts !== state.sts) await saveState({ ...state, sts });
  if (!cached) await preparePlayer(state.id, js);
}
