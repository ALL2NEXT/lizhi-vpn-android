import adminDocument from "./admin-ui/document.html";
import adminStyles from "./admin-ui/styles.css";
import coreScript from "./admin-ui/scripts/core.client.js";
import notificationsScript from "./admin-ui/scripts/notifications.client.js";
import systemLogsScript from "./admin-ui/scripts/system-logs.client.js";
import apiStatisticsScript from "./admin-ui/scripts/api-statistics.client.js";
import securityScript from "./admin-ui/scripts/security.client.js";
import deviceManagementScript from "./admin-ui/scripts/device-management.client.js";
import dashboardChartsScript from "./admin-ui/scripts/dashboard-charts.client.js";
import usersScript from "./admin-ui/scripts/users.client.js";
import userManagementScript from "./admin-ui/scripts/user-management.client.js";
import settingsScript from "./admin-ui/scripts/settings.client.js";
import settingsDraftsScript from "./admin-ui/scripts/settings-drafts.client.js";

const adminScript = [
  coreScript,
  notificationsScript,
  systemLogsScript,
  apiStatisticsScript,
  securityScript,
  deviceManagementScript,
  dashboardChartsScript,
  usersScript,
  userManagementScript,
  settingsDraftsScript,
  settingsScript,
].join("\n");

export function adminHtml(basePath: string): string {
  return adminDocument
    .replace("/* __ADMIN_STYLES__ */", () => adminStyles)
    .replace("/* __ADMIN_SCRIPT__ */", () => adminScript)
    .replaceAll("__ADMIN_BASE_PATH__", () => basePath);
}
