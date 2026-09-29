// Which InnerTube clients are asked, in which order, and which ones GVS is refusing right now. Pure
// policy, ported from MusicPlayerUtils (music), InnerTubeVideoStreamExtractor and ClientGateTracker
// (video) and StreamDenialClassifier (what a failed URL says).
import {
  ANDROID_CREATOR,
  ANDROID_VR_1_43_32,
  ANDROID_VR_1_61_48,
  ANDROID_VR_NO_AUTH,
  MOBILE,
  MWEB,
  TVHTML5,
  TVHTML5_DOWNGRADED,
  TVHTML5_SIMPLY_EMBEDDED_PLAYER,
  VISIONOS,
  WEB,
  WEB_CREATOR,
  WEB_REMIX,
  type YouTubeClient,
} from '../innertube/clients';
import { queryParam } from './urls';

/** The only ANDROID_VR build still answering on the main site; the video ladder leads with it. */
export const ANDROID_VR_1_65_10: YouTubeClient = {
  clientName: 'ANDROID_VR',
  clientVersion: '1.65.10',
  clientId: '28',
  userAgent: 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
  osName: 'Android',
  osVersion: '12L',
  deviceMake: 'Oculus',
  deviceModel: 'Quest 3',
  androidSdkVersion: '32',
  attestation: 'DROIDGUARD',
};

// ---- Music ----

/** Tried last on the fast path and first in the rescue; the signed-in account's client too. */
export const MUSIC_MAIN_CLIENT = WEB_REMIX;

/**
 * VISIONOS leads: the only direct client GVS still serves past ~60 s without a PO Token and without
 * an `n` challenge, so the first track never waits for the solver.
 */
export const MUSIC_FAST_CLIENTS: YouTubeClient[] = [VISIONOS, ANDROID_VR_1_43_32, ANDROID_VR_1_61_48, ANDROID_VR_NO_AUTH, MOBILE, ANDROID_CREATOR];

export const MUSIC_FALLBACK_CLIENTS: YouTubeClient[] = [
  TVHTML5_SIMPLY_EMBEDDED_PLAYER,
  TVHTML5,
  ANDROID_VR_1_43_32,
  ANDROID_VR_1_61_48,
  ANDROID_CREATOR,
  ANDROID_VR_NO_AUTH,
  MOBILE,
  WEB,
  WEB_CREATOR,
];

/** After a failed stream the next resolve skips the fast clients for this long. */
export const ESCALATION_WINDOW_MS = 120_000;

const clientKey = (client: YouTubeClient) => `${client.clientName}:${client.clientVersion}:${client.clientId}`;

/**
 * Clients the anonymous ladder can use. The music stream lookup never runs signed in (the account's
 * own request runs beside it), so login-only clients are skipped as the app skipped them.
 */
const anonymous = (clients: YouTubeClient[]) => clients.filter((client) => !client.loginRequired);

/** The fast direct-URL pass: the fast clients, then the fallbacks, each once. */
export function musicFastLadder(): YouTubeClient[] {
  const seen = new Set<string>();
  return anonymous([...MUSIC_FAST_CLIENTS, ...MUSIC_FALLBACK_CLIENTS]).filter((client) => {
    const key = clientKey(client);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export const musicRescueLadder = (): YouTubeClient[] => anonymous(MUSIC_FALLBACK_CLIENTS);

/**
 * Whether a direct URL is probed with HEAD before it is handed over. Android and Apple clients skip
 * it: their URLs play as returned, and the probe would sit on the path to first audio.
 */
export function needsValidation(client: YouTubeClient): boolean {
  return !client.clientName.startsWith('ANDROID') && client.clientName !== 'IOS' && client.clientName !== 'VISIONOS';
}

/** Remembers which tracks failed recently, so the next resolve goes straight past the fast clients. */
export class Escalations {
  private readonly failedAt = new Map<string, number>();

  constructor(
    private readonly windowMs = ESCALATION_WINDOW_MS,
    private readonly now: () => number = Date.now,
  ) {}

  mark(videoId: string): void {
    this.failedAt.set(videoId, this.now());
    if (this.failedAt.size > 200) this.failedAt.delete(this.failedAt.keys().next().value as string);
  }

  isEscalated(videoId: string): boolean {
    const at = this.failedAt.get(videoId);
    return at !== undefined && this.now() - at < this.windowMs;
  }
}

// ---- Video ----

/** Token-free, n-free direct URLs GVS honours for the whole video. */
export const VIDEO_FAST_CLIENTS: YouTubeClient[] = [VISIONOS];

/** Signed in, asked as the account: every format it may watch, including what VISIONOS refuses. */
export const VIDEO_SIGNED_IN_CLIENTS: YouTubeClient[] = [TVHTML5_DOWNGRADED];

/** Web clients whose PO Token the plugin can mint; ranked above every unattested client. */
export const VIDEO_WEB_CLIENTS: YouTubeClient[] = [MWEB, WEB];

/** Direct clients GVS cuts off ~60 s in without a DroidGuard token; below anything attested. */
export const VIDEO_GATED_CLIENTS: YouTubeClient[] = [ANDROID_VR_1_65_10, ANDROID_VR_1_61_48, ANDROID_VR_NO_AUTH, ANDROID_VR_1_43_32];

export const VIDEO_LAST_RESORT_CLIENTS: YouTubeClient[] = [MOBILE];

/** ANDROID_VR leads for live: the only client returning a DASH manifest beside the HLS one. */
export const LIVE_MANIFEST_CLIENTS: YouTubeClient[] = [ANDROID_VR_1_65_10, VISIONOS];

const GATE_TTL_MS = 30 * 60 * 1000;
const REFUSALS_BEFORE_DEMOTION = 2;

/**
 * The clients GVS is refusing, by `clientName` (what a failed URL's `c=` reports). Entries lapse after
 * 30 minutes. Kept in memory only: a gate belongs to this network and visitor identity.
 */
export class ClientGates {
  private readonly gatedUntil = new Map<string, number>();
  private readonly strikes = new Map<string, number>();

  constructor(
    private readonly ttlMs = GATE_TTL_MS,
    private readonly now: () => number = Date.now,
  ) {}

  reportGated(clientName: string | undefined): void {
    if (clientName) this.gatedUntil.set(clientName.toUpperCase(), this.now() + this.ttlMs);
  }

  /** GVS refused the client's PO Token; the second strike demotes, since one can be a cold attestation. */
  reportRefused(clientName: string | undefined): boolean {
    if (!clientName) return false;
    const key = clientName.toUpperCase();
    const strikes = (this.strikes.get(key) ?? 0) + 1;
    if (strikes < REFUSALS_BEFORE_DEMOTION) {
      this.strikes.set(key, strikes);
      return false;
    }
    this.strikes.delete(key);
    this.gatedUntil.set(key, this.now() + this.ttlMs);
    return true;
  }

  isGated(clientName: string): boolean {
    const key = clientName.toUpperCase();
    const until = this.gatedUntil.get(key);
    if (until === undefined) return false;
    if (this.now() >= until) {
      this.gatedUntil.delete(key);
      return false;
    }
    return true;
  }

  ungated(clients: YouTubeClient[]): YouTubeClient[] {
    return clients.filter((client) => !this.isGated(client.clientName));
  }

  gated(clients: YouTubeClient[]): YouTubeClient[] {
    return clients.filter((client) => this.isGated(client.clientName));
  }

  clear(): void {
    this.gatedUntil.clear();
    this.strikes.clear();
  }
}

export type Denial = 'URL_EXPIRED' | 'ATTESTATION_GATED' | 'TOKEN_REJECTED' | 'UNKNOWN';

const EXPIRY_GRACE_SECONDS = 30;

/** Why GVS refused a googlevideo URL, as far as the URL itself tells: expired, unattested, or its token refused. */
export function classifyDenial(url: string, nowSeconds: number): Denial {
  const expire = Number(queryParam(url, 'expire'));
  if (!Number.isFinite(expire) || expire <= 0) return 'UNKNOWN';
  if (expire - nowSeconds <= EXPIRY_GRACE_SECONDS) return 'URL_EXPIRED';
  return queryParam(url, 'pot') ? 'TOKEN_REJECTED' : 'ATTESTATION_GATED';
}

/** The `c=` client that minted a googlevideo URL, e.g. `VISIONOS`. */
export const clientOfUrl = (url: string): string | undefined => queryParam(url, 'c')?.toUpperCase() || undefined;
