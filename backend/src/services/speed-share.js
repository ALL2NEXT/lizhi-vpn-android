import { cleanText, plainObject } from "../lib/values.js";

export function normalizeSpeedShareResult(value, location = {}) {
  if (!plainObject(value)) throw new Error("invalid_speed_share_result");
  const nodeId = cleanText(value.nodeId || value.id).slice(0, 128);
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/.test(nodeId)) {
    throw new Error("invalid_speed_share_node_id");
  }
  const result = {
    nodeId,
    serverHost: normalizeSpeedShareServerHost(
      value.serverHost || value.server || value.host,
    ),
    subscriptionGroupName: cleanText(
      value.subscriptionGroupName || value.groupName,
    ).slice(0, 200),
    tcpingLatencyMs: sharedLatency(value.tcpingLatencyMs ?? value.tcpingLatency),
    urlTestLatencyMs: sharedLatency(value.urlTestLatencyMs ?? value.urlTestLatency),
    downloadMbps: sharedMbps(value.downloadMbps ?? value.download),
    uploadMbps: sharedMbps(value.uploadMbps ?? value.upload),
    province: cleanText(location.province) || normalizeSpeedShareProvince(value.province),
    carrier: cleanText(location.carrier) || normalizeSpeedShareCarrier(value.carrier || value.operator),
  };
  return result.tcpingLatencyMs > 0 || result.urlTestLatencyMs > 0 ||
    result.downloadMbps > 0 || result.uploadMbps > 0
    ? result
    : null;
}

export function speedShareRequestLocation(request) {
  const cf = request && request.cf && typeof request.cf === "object" ? request.cf : {};
  return {

    province: normalizeSpeedShareProvince(cf.region, cf.regionCode),
    carrier: normalizeSpeedShareCarrier(cf.asOrganization),
  };
}

export function normalizeSpeedShareProvince(value, regionCode = "") {
  const text = cleanText(value).slice(0, 80);
  const code = cleanText(regionCode).toUpperCase().replace(/^CN-/, "");
  const codeNames = {
    AH: "安徽省", BJ: "北京市", CQ: "重庆市", FJ: "福建省", GS: "甘肃省",
    GD: "广东省", GX: "广西壮族自治区", GZ: "贵州省", HI: "海南省", HB: "河北省",
    HE: "河南省", HL: "黑龙江省", HN: "湖南省", HU: "湖北省", JL: "吉林省",
    JS: "江苏省", JX: "江西省", LN: "辽宁省", NM: "内蒙古自治区", NX: "宁夏回族自治区",
    QH: "青海省", SC: "四川省", SN: "陕西省", SD: "山东省", SH: "上海市", SX: "山西省",
    TJ: "天津市", XJ: "新疆维吾尔自治区", XZ: "西藏自治区", YN: "云南省", ZJ: "浙江省",
    HK: "香港特别行政区", MO: "澳门特别行政区", TW: "台湾省",
  };
  if (codeNames[code]) return codeNames[code];
  if (!text) return "";
  const aliases = {
    anhui: "安徽省", 安徽: "安徽省", beijing: "北京市", 北京: "北京市",
    chongqing: "重庆市", 重庆: "重庆市", fujian: "福建省", 福建: "福建省",
    gansu: "甘肃省", 甘肃: "甘肃省", guangdong: "广东省", 广东: "广东省",
    guangxi: "广西壮族自治区", 广西: "广西壮族自治区", guizhou: "贵州省", 贵州: "贵州省",
    hainan: "海南省", 海南: "海南省", hebei: "河北省", 河北: "河北省",
    henan: "河南省", 河南: "河南省", heilongjiang: "黑龙江省", 黑龙江: "黑龙江省",
    hunan: "湖南省", 湖南: "湖南省", hubei: "湖北省", 湖北: "湖北省",
    jilin: "吉林省", 吉林: "吉林省", jiangsu: "江苏省", 江苏: "江苏省",
    jiangxi: "江西省", 江西: "江西省", liaoning: "辽宁省", 辽宁: "辽宁省",
    "inner mongolia": "内蒙古自治区", 内蒙古: "内蒙古自治区", ningxia: "宁夏回族自治区", 宁夏: "宁夏回族自治区",
    qinghai: "青海省", 青海: "青海省", sichuan: "四川省", 四川: "四川省", shaanxi: "陕西省", 陕西: "陕西省",
    shandong: "山东省", 山东: "山东省", shanghai: "上海市", 上海: "上海市",
    shanxi: "山西省", 山西: "山西省", tianjin: "天津市", 天津: "天津市",
    xinjiang: "新疆维吾尔自治区", 新疆: "新疆维吾尔自治区", tibet: "西藏自治区", 西藏: "西藏自治区",
    yunnan: "云南省", 云南: "云南省", zhejiang: "浙江省", 浙江: "浙江省",
    "hong kong": "香港特别行政区", 香港: "香港特别行政区", macau: "澳门特别行政区", 澳门: "澳门特别行政区",
    taiwan: "台湾省", 台湾: "台湾省",
  };
  const normalized = text.toLowerCase();
  const alias = Object.keys(aliases).find((key) => normalized.includes(key));
  return alias ? aliases[alias] : text;
}

export function normalizeSpeedShareCarrier(value) {
  const text = cleanText(value).slice(0, 120);
  if (!text) return "";
  const normalized = text.toLowerCase().replace(/[\s_-]+/g, "");
  if (/(中国电信|电信|chinatelecom|telecom|chinanet)/i.test(normalized)) return "电信";
  if (/(中国联通|联通|chinaunicom|unicom)/i.test(normalized)) return "联通";
  if (/(中国移动|移动|chinamobile|cmcc|cmnet|mobile)/i.test(normalized)) return "移动";
  if (/(中国广电|广电|chinabroadnet|broadnet)/i.test(normalized)) return "广电";
  return text;
}

export function sharedLatency(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 && number <= 600000 ? number : 0;
}

export function sharedMbps(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 && number <= 1000000
    ? Math.round(number * 1000) / 1000
    : 0;
}

export function normalizeSpeedShareServerHost(value) {
  let host = cleanText(value).slice(0, 255);
  if (!host) return "";
  if (host.startsWith("[")) {
    const closingBracket = host.indexOf("]");
    if (closingBracket > 1) host = host.slice(1, closingBracket);
  } else {
    const firstColon = host.indexOf(":");
    const lastColon = host.lastIndexOf(":");
    if (firstColon === lastColon && lastColon > 0 &&
        /^\d+$/.test(host.slice(lastColon + 1))) {
      host = host.slice(0, lastColon);
    }
  }
  return host.trim().replace(/\.$/, "").toLowerCase();
}



export function publicAdminSpeedShareResult(row) {
  return {
    nodeId: cleanText(row.node_id),
    serverHost: cleanText(row.server_host),
    subscriptionGroupName: cleanText(row.subscription_group_name),
    province: cleanText(row.province),
    carrier: cleanText(row.carrier),
    tcpingLatencyMs: sharedLatency(row.tcping_latency_ms),
    urlTestLatencyMs: sharedLatency(row.url_test_latency_ms),
    downloadMbps: sharedMbps(row.download_mbps),
    uploadMbps: sharedMbps(row.upload_mbps),
    updatedAt: cleanText(row.updated_at),
    lastReportedAt: cleanText(row.last_reported_at),
    reportCount: Number(row.report_count) || 0,
  };
}

