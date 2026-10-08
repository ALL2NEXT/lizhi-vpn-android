import type { AccountType, SubscriptionSource } from "../types/models.ts";
import { detectSubscriptionInput, toStandardUris } from "../domain/subscription-import/index.ts";
import { fetchExternalSubscription, normalizeManagedContent } from "./subscriptions.ts";

export interface ImportedSubscription extends Omit<SubscriptionSource, "id"> {
  nodeCount: number;
  /** 兼容旧版管理端的预览字段；持久化时由分组负责权限。 */
  minAccountType: AccountType;
  minAppVersion: string;
}
export interface SubscriptionImportResult {
  sources: ImportedSubscription[];
  failedSources: { url: string; error: string }[];
  nodeCount: number;
}

/** 仅生成待编辑的线路，不修改 KV；原有「推送」操作负责保存和缓存预热。 */
export async function importSubscriptionSources(input: unknown, accountType: unknown): Promise<SubscriptionImportResult> {
  if (typeof input !== "string") throw new Error("invalid_subscription_import_input");
  const content = normalizeManagedContent(input);
  const minAccountType: AccountType = accountType === "banned" || accountType === "formal" ? accountType : "guest";
  const source = (name: string, url: string, localContent: string, nodeCount: number): ImportedSubscription => ({
    name, url, content: localContent, nodeCount, minAccountType, enabled: true,
    minAppVersion: "", remark: "", autoRename: false,
    iconMode: "AUTO_REMOTE", customIconUrl: "", iconBase64: "", iconMimeType: "",
  });
  if (!content) return { sources: [source("新建线路", "", "", 0)], failedSources: [], nodeCount: 0 };
  const { urls, local } = detectSubscriptionInput(content);
  if (urls.length > 20) throw new Error("subscription_import_too_many_urls");
  const sources: ImportedSubscription[] = [];
  const localNodes = toStandardUris(local);
  if (localNodes.length) sources.push(source("导入内容", "", normalizeManagedContent(localNodes.join("\n")), localNodes.length));
  // 小批并发且保留输入顺序，独立失败不会抹掉已成功的来源。
  const remote: ({ value: ImportedSubscription } | { error: string; url: string })[] = [];
  for (let start = 0; start < urls.length; start += 3) {
    remote.push(...await Promise.all(urls.slice(start, start + 3).map(async url => {
      try {
        const result = await fetchExternalSubscription(url);
        return { value: source(result.name, url, "", result.nodeCount) };
      } catch (error) { return { url, error: error instanceof Error ? error.message : "external_subscription_unreachable" }; }
    })));
  }
  const failedSources: SubscriptionImportResult["failedSources"] = [];
  for (const result of remote) {
    if ("value" in result) sources.push(result.value);
    else failedSources.push(result);
  }
  if (!sources.length && !failedSources.length) throw new Error("subscription_import_no_nodes");
  return { sources, failedSources, nodeCount: sources.reduce((sum, item) => sum + item.nodeCount, 0) };
}
