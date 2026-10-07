import adminDocument from "./admin-ui/document.html";
import adminStyles from "./admin-ui/styles.css";
import coreScript from "./admin-ui/scripts/core.client.js";
import adminAccessScript from "./admin-ui/scripts/admin-access.client.js";
import securityScript from "./admin-ui/scripts/security.client.js";
import userTableScript from "./admin-ui/scripts/user-table.client.js";
import dashboardChartsScript from "./admin-ui/scripts/dashboard-charts.client.js";
import dashboardTablesScript from "./admin-ui/scripts/dashboard-tables.client.js";
import usersScript from "./admin-ui/scripts/users.client.js";
import userManagementScript from "./admin-ui/scripts/user-management.client.js";
import settingsScript from "./admin-ui/scripts/settings.client.js";
import subscriptionImportScript from "./admin-ui/scripts/subscription-import.client.js";

const adminScript = [
  coreScript,
  adminAccessScript,
  securityScript,
  userTableScript,
  dashboardChartsScript,
  dashboardTablesScript,
  usersScript,
  userManagementScript,
  settingsScript,
  subscriptionImportScript,
].join("\n");

export function adminHtml(basePath: string): string {
  return adminDocument
    .replace("/* __ADMIN_STYLES__ */", () => adminStyles)
    .replace("/* __ADMIN_SCRIPT__ */", () => adminScript)
    .replaceAll("__ADMIN_BASE_PATH__", () => basePath);
}
