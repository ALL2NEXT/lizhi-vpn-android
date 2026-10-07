import { plainObject } from "../lib/values.ts";
import type { JsonObject } from "../types/models.ts";

// JSON fields remain unknown until the existing domain validators accept them.
export async function readJsonObject(request: Request): Promise<JsonObject> {
  const value = await request.json<unknown>();
  return plainObject(value) ? value : {};
}
