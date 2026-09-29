// InnerTube clients, as YouTube expects them today; ported from the app's YouTubeClient.kt, whose
// comments explain the version-sensitive choices (VISIONOS especially). Keep in step with yt-dlp.

export type Attestation = 'WEB' | 'DROIDGUARD' | 'IOSGUARD';

export interface YouTubeClient {
  clientName: string;
  clientVersion: string;
  clientId: string;
  userAgent: string;
  osName?: string;
  osVersion?: string;
  deviceMake?: string;
  deviceModel?: string;
  androidSdkVersion?: string;
  originalUrl?: string;
  platform?: string;
  utcOffsetMinutes?: number;
  loginSupported?: boolean;
  loginRequired?: boolean;
  useSignatureTimestamp?: boolean;
  isEmbedded?: boolean;
  useWebPoTokens?: boolean;
  attestation?: Attestation;
  sendUserAgentInContext?: boolean;
}

export const USER_AGENT_WEB = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0';
const USER_AGENT_MWEB =
  'Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)';

export const WEB_REMIX: YouTubeClient = {
  clientName: 'WEB_REMIX',
  clientVersion: '1.20260213.01.00',
  clientId: '67',
  userAgent: USER_AGENT_WEB,
  loginSupported: true,
  useSignatureTimestamp: true,
  useWebPoTokens: true,
};

export const WEB: YouTubeClient = {
  clientName: 'WEB',
  clientVersion: '2.20260710.06.00',
  clientId: '1',
  userAgent: USER_AGENT_WEB,
  originalUrl: 'https://www.youtube.com',
  platform: 'DESKTOP',
  utcOffsetMinutes: 0,
};

export const MWEB: YouTubeClient = {
  clientName: 'MWEB',
  clientVersion: '2.20250122.04.00',
  clientId: '2',
  userAgent: USER_AGENT_MWEB,
  platform: 'MOBILE',
  utcOffsetMinutes: 0,
  useSignatureTimestamp: true,
  useWebPoTokens: true,
  sendUserAgentInContext: true,
};

export const TVHTML5: YouTubeClient = {
  clientName: 'TVHTML5',
  clientVersion: '7.20260213.00.00',
  clientId: '7',
  userAgent:
    'Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15',
  loginSupported: true,
  loginRequired: true,
  useSignatureTimestamp: true,
  useWebPoTokens: true,
};

export const TVHTML5_SIMPLY_EMBEDDED_PLAYER: YouTubeClient = {
  clientName: 'TVHTML5_SIMPLY_EMBEDDED_PLAYER',
  clientVersion: '2.0',
  clientId: '85',
  userAgent: 'Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15',
  loginSupported: true,
  useSignatureTimestamp: true,
  isEmbedded: true,
};

export const IOS: YouTubeClient = {
  clientName: 'IOS',
  clientVersion: '21.03.1',
  clientId: '5',
  userAgent: 'com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)',
  osVersion: '18.2.22C152',
  attestation: 'IOSGUARD',
};

/** The Android app's identity; the app calls this client MOBILE. */
export const MOBILE: YouTubeClient = {
  clientName: 'ANDROID',
  clientVersion: '21.03.38',
  clientId: '3',
  userAgent: 'com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip',
  loginSupported: true,
  useSignatureTimestamp: true,
  attestation: 'DROIDGUARD',
};

export const ANDROID_VR_NO_AUTH: YouTubeClient = {
  clientName: 'ANDROID_VR',
  clientVersion: '1.61.48',
  clientId: '28',
  userAgent:
    'com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)',
  attestation: 'DROIDGUARD',
};

const androidVr = (version: string, cronet: string): YouTubeClient => ({
  clientName: 'ANDROID_VR',
  clientVersion: version,
  clientId: '28',
  userAgent: `com.google.android.apps.youtube.vr.oculus/${version} (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/${cronet})`,
  osName: 'Android',
  osVersion: '12',
  deviceMake: 'Oculus',
  deviceModel: 'Quest 3',
  androidSdkVersion: '32',
  attestation: 'DROIDGUARD',
});

export const ANDROID_VR_1_61_48 = androidVr('1.61.48', '132.0.6808.3');
export const ANDROID_VR_1_43_32 = androidVr('1.43.32', '107.0.5284.2');

export const ANDROID_CREATOR: YouTubeClient = {
  clientName: 'ANDROID_CREATOR',
  clientVersion: '25.03.101',
  clientId: '14',
  userAgent:
    'com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)',
  osName: 'Android',
  osVersion: '15',
  deviceMake: 'Google',
  deviceModel: 'Pixel 9 Pro Fold',
  androidSdkVersion: '35',
  loginSupported: true,
  useSignatureTimestamp: true,
  attestation: 'DROIDGUARD',
};

/**
 * The primary direct-URL client: its formats need neither a PO Token nor an `n` transform. Its values
 * are version-sensitive (98 formats and every dubbed track versus a 17-format stub); see YouTubeClient.kt.
 */
export const VISIONOS: YouTubeClient = {
  clientName: 'VISIONOS',
  clientVersion: '1.02',
  clientId: '101',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
  osName: 'visionOS',
  osVersion: '26.5.23O471',
  deviceMake: 'Apple',
  deviceModel: 'RealityDevice17,1',
  attestation: 'IOSGUARD',
};

export const WEB_CREATOR: YouTubeClient = {
  clientName: 'WEB_CREATOR',
  clientVersion: '1.20260213.00.00',
  clientId: '62',
  userAgent: USER_AGENT_WEB,
  loginSupported: true,
  loginRequired: true,
  useSignatureTimestamp: true,
};
