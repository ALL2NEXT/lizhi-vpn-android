// Bindings can be absent in local tools; services retain their runtime checks.
export interface Env extends Partial<CloudflareBindings> {
  ADMIN_PATH?: string;
  ADMIN_TOKEN?: string;
}
