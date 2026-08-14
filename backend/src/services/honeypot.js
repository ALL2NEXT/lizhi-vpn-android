/*
 * 常见后台路径蜜罐。
 *
 * 这些路径与真实后台 UUID 完全分离：只记录访问行为，不读取或保存密码，
 * 所有登录提交都固定失败，避免误把蜜罐请求放行到真实管理接口。
 */

const HONEYPOT_PATHS = new Set([
  "/admin",
  "/admin/login",
  "/admin/login.php",
  "/admin/index.php",
  "/administrator",
  "/administrator/login",
  "/backend",
  "/backend/login",
  "/dashboard/login",
  "/login",
  "/login.php",
  "/manage",
  "/manage/login",
  "/manager",
  "/manager/login",
  "/panel",
  "/panel/login",
  "/wp-admin",
  "/wp-login.php",
  "/api/login",
  "/api/admin/login",
]);

export function isHoneypotPath(path) {
  return HONEYPOT_PATHS.has(String(path || "").toLowerCase());
}

function fakeLoginPage() {
  return [
    "<!doctype html>",
    "<html lang=\"zh-CN\">",
    "<head>",
    "  <meta charset=\"utf-8\">",
    "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
    "  <title>管理后台登录</title>",
    "  <style>",
    "    body{margin:0;background:#0f172a;color:#e2e8f0;font:16px system-ui,sans-serif}",
    "    main{max-width:360px;margin:15vh auto;padding:28px;background:#1e293b;border:1px solid #334155;border-radius:14px}",
    "    h1{margin:0 0 22px;font-size:22px}",
    "    label{display:block;margin:14px 0 6px;color:#94a3b8}",
    "    input{box-sizing:border-box;width:100%;padding:11px;border:1px solid #475569;border-radius:8px;background:#0f172a;color:#e2e8f0}",
    "    button{width:100%;margin-top:20px;padding:11px;border:0;border-radius:8px;background:#2563eb;color:white;font-weight:700}",
    "  </style>",
    "</head>",
    "<body>",
    "  <main>",
    "    <h1>管理后台</h1>",
    "    <form method=\"post\">",
    "      <label for=\"username\">用户名</label>",
    "      <input id=\"username\" name=\"username\" autocomplete=\"username\">",
    "      <label for=\"password\">密码</label>",
    "      <input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\">",
    "      <button type=\"submit\">登录</button>",
    "    </form>",
    "  </main>",
    "</body>",
    "</html>",
  ].join("\n");
}

export async function handleHoneypotRoute(path, request, env, recordAdminLogin, json) {
  await recordAdminLogin(env, request, false, {
    honeypot: true,
    path,
    method: request.method,
  });

  if (request.method === "GET" || request.method === "HEAD") {
    return new Response(request.method === "HEAD" ? null : fakeLoginPage(), {
      status: 200,
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store, no-cache, must-revalidate",
      },
    });
  }

  return json({ ok: false, error: "invalid_credentials" }, 401);
}
