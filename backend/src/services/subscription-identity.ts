import type { SubscriptionSource } from "../types/models.ts";

function contentSample(content: string) {
  if (content.length <= 512) return content;
  return `${content.slice(0, 256)}${content.slice(-256)}`;
}

export async function createSubscriptionAssignmentKey(source: SubscriptionSource) {
  // 沿用已有分配摘要规则，迁移到 D1 后用户仍固定使用原线路。
  const identity = source.url
    ? `url\u0000${source.url.trim()}\u0000${source.name.trim()}`
    : source.content
      ? `content\u0000${source.name.trim()}\u0000${source.content.length}\u0000${contentSample(source.content)}`
      : `content-id\u0000${source.id}\u0000${source.name.trim()}`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(identity));
  return Array.from(new Uint8Array(digest).slice(0, 16), (byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

export async function subscriptionAssignmentKey(source: SubscriptionSource) {
  return source.assignmentKey || createSubscriptionAssignmentKey(source);
}
