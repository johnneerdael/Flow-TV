// The identity web PO Tokens are minted for: the signed-in account's visitor when there is one, so a
// single BotGuard session serves music and video alike (as SignedInPlayback.identity did), else the
// anonymous visitor. A request carrying a token must carry this same visitor.
import { usableSession, visitorData } from '../innertube/session';

export async function tokenVisitor(): Promise<string> {
  return (await usableSession())?.visitorData ?? (await visitorData());
}
