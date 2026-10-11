import type { RequestUserIdentity, SystemLogDraft } from "./models.ts";

// Bindings can be absent in local tools; services retain their runtime checks.
export interface Env extends Partial<CloudflareBindings> {
  SUBSCRIPTIONS_DB?: D1Database;
  ADMIN_PATH?: string;
  ADMIN_TOKEN?: string;
  __systemLogSink?: (entry: SystemLogDraft) => void;
  __requestUserSink?: (identity: RequestUserIdentity) => void;
}
