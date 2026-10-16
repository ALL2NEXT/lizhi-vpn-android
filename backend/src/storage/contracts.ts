export interface DatabaseResult<T = unknown> {
  results: T[];
  meta?: { changes?: number };
}

export interface DatabaseExecResult {
  count: number;
  duration: number;
}

export interface PreparedStatement {
  bind(...values: unknown[]): PreparedStatement;
  first<T = Record<string, unknown>>(columnName?: string): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<DatabaseResult<T>>;
  run<T = Record<string, unknown>>(): Promise<DatabaseResult<T>>;
}

export interface Database {
  prepare(query: string): PreparedStatement;
  batch<T = unknown>(statements: PreparedStatement[]): Promise<DatabaseResult<T>[]>;
  exec(query: string): Promise<DatabaseExecResult>;
}

export interface ConfigStore {
  get(key: string): Promise<string | null>;
  get<T = unknown>(key: string, type: "json"): Promise<T | null>;
  put(key: string, value: string, options?: {
    expiration?: number;
    expirationTtl?: number;
  }): Promise<void>;
  delete(key: string): Promise<void>;
}
