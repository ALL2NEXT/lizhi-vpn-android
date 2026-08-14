/*
 * Admin access and login history routes.
 */

export function createAdminAccessRoutes(deps) {
  const { json, readAdminLoginRecords } = deps;

  return async function handleAdminAccess(path, request, env) {
    if (path === "/api/admin-login-records" && request.method === "GET") {
      return json({
        ok: true,
        records: await readAdminLoginRecords(env),
      });
    }

    return null;
  };
}
