import type { RequestUserIdentity, SystemLogDraft } from "./models.ts";
import type { ConfigStore, Database } from "../storage/contracts.ts";

// Bindings can be absent in local tools; services retain their runtime checks.
export interface Env {
  CONFIG?: ConfigStore;
  USERS_DB?: Database;
  SUBSCRIPTIONS_DB?: Database;
  ADMIN_PATH?: string;
  ADMIN_TOKEN?: string;
  SERVICE_NAME?: string;
  /** Base64 PKCS#8 RSA private key; provision as a secret, never in the APK. */
  CLIENT_TRANSPORT_PRIVATE_KEY?: string;
  __systemLogSink?: (entry: SystemLogDraft) => void;
  __requestUserSink?: (identity: RequestUserIdentity) => void;
}
