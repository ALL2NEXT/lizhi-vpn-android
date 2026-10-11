import { plainObject } from "../lib/values.ts";
import type { JsonObject, RequestUserIdentity } from "../types/models.ts";
import type { Env } from "../types/env.ts";

// JSON fields remain unknown until the existing domain validators accept them.
export async function readJsonObject(request: Request): Promise<JsonObject> {
  const value = await request.json<unknown>();
  return plainObject(value) ? value : {};
}

// 保存本次请求解析出的身份，避免设备随后切换账户时覆盖本次记录。
export function observeRequestUser(
  env: Env,
  identity: Pick<RequestUserIdentity, "userId" | "uid" | "deviceId">,
  source: RequestUserIdentity["source"] = "device",
) {
  env.__requestUserSink?.({
    userId: identity.userId,
    uid: identity.uid,
    deviceId: identity.deviceId,
    source,
  });
}
