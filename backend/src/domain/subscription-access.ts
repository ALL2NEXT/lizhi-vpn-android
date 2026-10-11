import type { SubscriptionAccess, SubscriptionGroup } from "../types/models.ts";

export type SubscriptionGroupAccessError =
  | "account_access_denied"
  | "app_version_too_low"
  | "app_version_too_high";

export function subscriptionGroupAccessError(
  group: SubscriptionGroup,
  access: SubscriptionAccess,
  isAppVersionAtLeast: (currentVersion: unknown, minimumVersion: unknown) => boolean,
): SubscriptionGroupAccessError | null {
  if (!group.enabled || !access.registered) return "account_access_denied";

  // 封禁状态、邮箱绑定类型和后台账号身份是独立条件，分组必须同时匹配这些维度。
  if (!group.allowedStatuses.includes(access.accountStatus)) return "account_access_denied";
  if (!group.allowedAccountTypes.includes(access.accountType)) {
    return "account_access_denied";
  }
  if (!group.allowedIdentityTypes.includes(access.identityType)) {
    return "account_access_denied";
  }
  const membershipType = access.vipActive ? "member" : "non_member";
  if (!group.allowedMembershipTypes.includes(membershipType)) {
    return "account_access_denied";
  }
  if (
    group.minAppVersion &&
    !isAppVersionAtLeast(access.appVersion, group.minAppVersion)
  ) {
    return "app_version_too_low";
  }
  if (
    group.maxAppVersion &&
    !isAppVersionAtLeast(group.maxAppVersion, access.appVersion)
  ) {
    return "app_version_too_high";
  }
  return null;
}
