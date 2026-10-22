import type { AccountType, SubscriptionSource } from "../types/models.ts";
import type { Env } from "../types/env.ts";
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
interface PreparedSubscriptionImport extends SubscriptionImportResult {
  urls: string[];
}

function normalizeImportAccountType(value: unknown): AccountType {
  return value === "banned" || value === "formal" ? value : "guest";
}

function importedSource(
  name: string,
  url: string,
  content: string,
  nodeCount: number,
  minAccountType: AccountType,
): ImportedSubscription {
  return {
    name,
    url,
    content,
    nodeCount,
    minAccountType,
    minAppVersion: "",
    enabled: true,
    remark: "",
    autoRename: false,
    deduplicate: true,
  };
}

/** 解析输入并转换本地节点；远程地址继续交给 Worker 拉取。 */
function prepareSubscriptionImport(
  input: unknown,
  accountType: unknown,
): PreparedSubscriptionImport {
  if (typeof input !== "string") throw new Error("invalid_subscription_import_input");
  const content = normalizeManagedContent(input);
  const minAccountType = normalizeImportAccountType(accountType);
  if (!content) {
    return {
      urls: [],
      sources: [importedSource("新建线路", "", "", 0, minAccountType)],
      failedSources: [],
      nodeCount: 0,
    };
  }

  const { urls, local } = detectSubscriptionInput(content);
  if (urls.length > 20) throw new Error("subscription_import_too_many_urls");
  const localNodes = toStandardUris(local);
  const localContent = localNodes.length
    ? normalizeManagedContent(localNodes.join("\n"))
    : "";
  const sources = localContent
    ? [importedSource("导入内容", "", localContent, localNodes.length, minAccountType)]
    : [];
  if (!urls.length && !sources.length) throw new Error("subscription_import_no_nodes");
  return {
    urls,
    sources,
    failedSources: [],
    nodeCount: localNodes.length,
  };
}

/** 仅生成待编辑的线路，不修改 KV；原有「推送」操作负责保存和缓存预热。 */
export async function importSubscriptionSources(input: unknown, accountType: unknown, env?: Env): Promise<SubscriptionImportResult> {
  const plan = prepareSubscriptionImport(input, accountType);
  if (!plan.urls.length) return plan;

  const minAccountType = normalizeImportAccountType(accountType);
  const sources: ImportedSubscription[] = [...plan.sources];
  const failedSources: SubscriptionImportResult["failedSources"] = [];
  // 小批并发且保留输入顺序，独立失败不会抹掉已成功的来源。
  const remote: ({ value: ImportedSubscription } | { error: string; url: string })[] = [];
  for (let start = 0; start < plan.urls.length; start += 3) {
    remote.push(...await Promise.all(plan.urls.slice(start, start + 3).map(async url => {
      try {
        const result = await fetchExternalSubscription(url, { env });
        return {
          value: importedSource(result.name, url, "", result.nodeCount, minAccountType),
        };
      } catch (error) {
        return { url, error: error instanceof Error ? error.message : "external_subscription_unreachable" };
      }
    })));
  }
  for (const result of remote) {
    if ("value" in result) sources.push(result.value);
    else failedSources.push(result);
  }
  return {
    sources,
    failedSources,
    nodeCount: sources.reduce((sum, item) => sum + item.nodeCount, 0),
  };
}
