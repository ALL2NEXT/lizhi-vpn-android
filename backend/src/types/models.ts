export type AccountType = "banned" | "guest" | "formal";
export type UserStatus = "active" | "banned";
export type SubscriptionAccountStatus = "normal" | "banned";
export type GrowthPeriod = "day" | "week" | "month";
export type JsonObject = Record<string, unknown>;

export interface SubscriptionSource {
  id: string;
  name: string;
  url: string;
  sourceType?: "url" | "content";
  contentKey?: string;
  assignmentKey?: string;
  enabled: boolean;
  autoRename: boolean;
  deduplicate: boolean;
  content: string;
  remark: string;
}

export interface SubscriptionGroup {
  id: string;
  name: string;
  groupType: "standard" | "load_balanced";
  encryptionMode: "none" | "encrypted";
  enabled: boolean;
  allowedStatuses: SubscriptionAccountStatus[];
  allowedAccountTypes: Exclude<AccountType, "banned">[];
  updateEnabled: boolean;
  cacheMinutes: number;
  minAppVersion: string;
  maxAppVersion: string;
  maxUsersPerSubscription: number;
  resetPeriodDays: number;
  subscriptions: SubscriptionSource[];
}

export interface Notice {
  hasNotice: boolean;
  title: string;
  content: string;
  noticeId: string;
  showOnce: boolean;
  startAt: string;
  endAt: string;
}

export interface NavigationItem {
  title: string;
  url: string;
  iconBase64?: string;
  iconMimeType?: string;
}

export interface NavigationGroup {
  title: string;
  items: NavigationItem[];
}

export interface DebugInterface {
  token: string;
  subscriptionIds: string[];
}

export interface Settings {
  websiteUrl: string;
  webNavigationGroups: NavigationGroup[];
  feedbackEmail: string;
  feedbackUrl: string;
  githubUrl: string;
  telegramUrl: string;
  registrationEnabled: boolean;
  requireAdminRegistrationCode: boolean;
  debugInterface: DebugInterface | null;
}

export interface AppState {
  version: 2;
  subscriptionGroups: SubscriptionGroup[];
  notice: Notice | null;
  // App update metadata is passed through for client version compatibility.
  appUpdate: JsonObject | null;
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

export interface DeviceContext {
  deviceRowId: number;
  deviceId: string;
  deviceBanned: boolean;
  blockReason: string;
  userId: number;
  uid: string;
  userStatus: string;
  brand: string;
  model: string;
  androidVersion: string;
  androidSdk: number;
  appVersion: string;
}

export interface SubscriptionAccess {
  accountStatus: SubscriptionAccountStatus;
  accountType: Exclude<AccountType, "banned">;
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
  deviceBlocked?: boolean;
  traffic?: AccountTraffic;
}

export type PublicAccount = Omit<User, "createdAt"> & { deviceBlocked: boolean };

export interface AdminLoginRecord {
  at: string;
  ip: string;
  userAgent: string;
  success: boolean;
  path: string;
  method: string;
  honeypot: boolean;
}

export interface AdminLoginDetails {
  path?: string;
  method?: string;
  honeypot?: boolean;
}
