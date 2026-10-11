// 规范化可信入口提供的地址，让不同写法的同一 IPv6 共用一条关联记录。
export function normalizeClientIp(value: unknown): string {
  if (typeof value !== "string") return "";
  const input = value.trim().toLowerCase();
  if (!input || input.length > 45) return "";
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(input)) {
    const parts = input.split(".").map(Number);
    return parts.every((part) => part <= 255) ? parts.join(".") : "";
  }
  if (!input.includes(":") || !/^[a-f0-9:.]+$/.test(input)) return "";
  try {
    const address = new URL("http://[" + input + "]/").hostname.slice(1, -1);
    const mapped = /^::ffff:([a-f0-9]{1,4}):([a-f0-9]{1,4})$/.exec(address);
    if (!mapped) return address;
    const high = Number.parseInt(mapped[1], 16);
    const low = Number.parseInt(mapped[2], 16);
    return [high >> 8, high & 255, low >> 8, low & 255].join(".");
  } catch {
    return "";
  }
}
