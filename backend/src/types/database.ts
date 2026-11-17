import type { UserIdentityType, UserStatus } from "./models.ts";

export interface DeviceFields {
  brand: string;
  model: string;
  android_version: string;
  android_sdk: number;
  app_version: string;
}

export interface TrafficFields {
  today_upload_bytes: number;
  today_download_bytes: number;
  month_upload_bytes: number;
  month_download_bytes: number;
  total_upload_bytes: number;
  total_download_bytes: number;
  last_report_at: string | null;
  active_today: number;
  active_this_month: number;
}

export interface UserRow extends Partial<DeviceFields>, Partial<TrafficFields> {
  id: number;
  uid: string;
  nickname: string;
  status: UserStatus;
  email: string | null;
  password_hash: string;
  vip_expires_at?: string | null;
  identity_type?: UserIdentityType;
  created_at: string;
  registration_ip?: string | null;
  device_brand?: string | null;
  device_model?: string | null;
  device_android_version?: string | null;
  device_android_sdk?: number | null;
  device_app_version?: string | null;
}

export type AccountRow = Pick<UserRow, "uid" | "nickname" | "status" | "email" | "created_at"> &
  Partial<Omit<UserRow, "uid" | "nickname" | "status" | "email" | "created_at">>;

export interface DeviceContextRow extends DeviceFields {
  device_row_id: number;
  device_id: string;
  user_id: number | null;
  uid: string | null;
  user_status: UserStatus | null;
  device_banned: number;
  block_reason: string;
}

export interface DeviceLinkRow extends DeviceFields {
  user_id: number | null;
  user_uid: string;
  device_id: string;
  first_bound_at: string;
  last_bound_at: string;
  last_seen_at: string;
  is_current: number;
  blocked: number;
  blocked_by_account: number;
}

export interface DeviceRelationRow extends Omit<DeviceLinkRow, "user_uid"> {
  account_uid: string;
  account_nickname: string | null;
  account_status: UserStatus | null;
}

export interface DeviceInventoryRow extends DeviceFields {
  device_id: string;
  current_uid: string | null;
  current_account_status: UserStatus | null;
  active_ban_count: number;
  active_bans: string;
  historical_account_count: number;
  linked_uids: string;
  created_at: string;
  last_seen_at: string;
}

export interface SecurityAccountRow extends AccountRow {
  id: number;
  historical_device_count: number;
  banned_device_count: number;
}

export interface GrowthRow { date_key: string; new_users: number }
export interface TrafficRow { date_key: string; upload_bytes: number; download_bytes: number }
export interface ActivityRow { date_key: string; user_id: number | null }

export interface RegistrationCodeRow {
  id: number;
  code: string;
  created_at: string;
  max_uses: number;
  use_count: number;
  used_at: string | null;
  used_by_uid: string;
  preview_uids: string;
}
