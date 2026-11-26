export type AccountType = "banned" | "formal";
export type UserIdentityType = "human" | "bot";
export type UserStatus = "active" | "banned";
export type SubscriptionAccountStatus = "normal" | "banned";
export type SubscriptionMembershipStatus = "member" | "non_member";
export type GrowthPeriod = "day" | "week" | "month";
export type JsonObject = Record<string, unknown>;

export interface SubscriptionSource {
  id: string;
  name: string;
  url: string;
  sourceType?: "url" | "content";
  contentKey?: string;
  enabled: boolean;
  updateEnabled: boolean;
  cacheMinutes: number;
  autoRename: boolean;
  deduplicate: boolean;
  content: string;
  remark: string;
}

export interface SubscriptionGroup {
  id: string;
  name: string;
  enabled: boolean;
  allowedStatuses: SubscriptionAccountStatus[];
  allowedIdentityTypes: UserIdentityType[];
  allowedMembershipTypes: SubscriptionMembershipStatus[];
  minAppVersion: string;
  maxAppVersion: string;
  subscriptions: SubscriptionSource[];
}

export interface Settings {
  websiteUrl: string;
  feedbackEmail: string;
  feedbackUrl: string;
  githubUrl: string;
  telegramUrl: string;
  latestAppVersion: string;
  appDownloadUrl: string;
  registrationEnabled: boolean;
  requireAdminRegistrationCode: boolean;
}

export interface AppState {
  version: 2;
  /** 按客户端可见的订阅 ID 分别记录版本号。 */
  subscriptionVersions: Record<string, number>;
  subscriptionGroups: SubscriptionGroup[];
  settings: Settings;
  updatedAt?: string;
}

export interface DeviceProfile {
  brand?: unknown;
  model?: unknown;
  androidVersion?: unknown;
  androidSdk?: unknown;
  appVersion?: unknown;
}


export interface RequestUserIdentity {
  userId: number;
  uid: string;
  deviceId: string;
  source: "device" | "login";
}

export interface SubscriptionAccess {
  accountStatus: SubscriptionAccountStatus;
  identityType: UserIdentityType;
  vipActive: boolean;
  appVersion: string;
  registered: boolean;
}

export interface TrafficPeriod {
  uploadBytes: number;
  downloadBytes: number;
  totalBytes: number;
}

export interface AccountTraffic {
  today: TrafficPeriod;
  month: TrafficPeriod;
  total: TrafficPeriod;
  lastReportAt: string | null;
}

export interface User {
  uid: string;
  nickname: string;
  accountType: AccountType;
  email: string;
  brand: string;
  model: string;
  androidVersion: string;
  androidSdk: number;
  appVersion: string;
  createdAt: string;
  vipActive: boolean;
  vipExpiresAt: string | null;
  deviceBlocked?: boolean;
  traffic?: AccountTraffic;
}

export type PublicAccount = Omit<User, "createdAt"> & { deviceBlocked: boolean };

export type SystemLogCategory = "request" | "outbound";
export type SystemLogCategorySettings = Record<SystemLogCategory, boolean>;

export type SystemLogDetailKey =
  | "requestUrl"
  | "requestHeaders"
  | "requestBody"
  | "responseHeaders"
  | "responseBody";

export type SystemLogDetailSettings = Record<SystemLogDetailKey, boolean>;

export interface SystemLogRecordingSchedule {
  enabled: boolean;
  startTime: string;
  endTime: string;
}

export interface SystemLogRecordingSettings {
  categories: SystemLogCategorySettings;
  details: SystemLogDetailSettings;
  schedule: SystemLogRecordingSchedule;
  endpointRecording: Record<string, boolean>;
}

export interface SystemLogHttpBodyCapture {
  contentType: string;
  encoding: "utf-8" | "base64";
  data: string;
}

export interface SystemLogHttpCapture {
  request?: {
    method: string;
    url?: string;
    headers?: [string, string][];
    body?: SystemLogHttpBodyCapture;
  };
  response?: {
    status: number;
    statusText?: string;
    headers?: [string, string][];
    body?: SystemLogHttpBodyCapture;
  };
}

export interface SystemLogDraft {
  category: SystemLogCategory;
  requestId?: string;
  actor?: string;
  method?: string;
  path?: string;
  status?: number | null;
  durationMs?: number | null;
  ip?: string;
  userUid?: string;
  deviceId?: string;
  identitySource?: string;
  userAgent?: string;
  message: string;
  details?: string;
  capture?: SystemLogHttpCapture;
  occurredAt?: string;
}

export interface SystemLogRecord extends Required<Omit<SystemLogDraft, "occurredAt" | "status" | "durationMs" | "capture">> {
  id: number;
  occurredAt: string;
  status: number | null;
  durationMs: number | null;
}
