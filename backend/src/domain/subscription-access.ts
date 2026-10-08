import type { SubscriptionAccess, SubscriptionGroup } from "../types/models.ts";

export type SubscriptionGroupAccessError = "account_access_denied" | "app_version_too_low";

export function subscriptionGroupAccessError(
  group: SubscriptionGroup,
  access: SubscriptionAccess,
  isAppVersionAtLeast: (currentVersion: unknown, minimumVersion: unknown) => boolean,
): SubscriptionGroupAccessError | null {
  if (!group.enabled || !access.registered) return "account_access_denied";

  // 封禁状态和邮箱绑定类型是独立条件，分组必须同时匹配这两个维度。
  if (!group.allowedStatuses.includes(access.accountStatus)) return "account_access_denied";
  if (!group.allowedAccountTypes.includes(access.accountType)) {
    return "account_access_denied";
  }
  if (
    group.minAppVersion &&
    !isAppVersionAtLeast(access.appVersion, group.minAppVersion)
  ) {
    return "app_version_too_low";
  }
  return null;
}
