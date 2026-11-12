import type {
  ConfigStore,
  Database,
  DatabaseExecResult,
  DatabaseResult,
  PreparedStatement,
} from "./contracts.ts";

class CloudflarePreparedStatement implements PreparedStatement {
  constructor(private readonly statement: D1PreparedStatement) {}

  nativeStatement() {
    return this.statement;
  }

  bind(...values: unknown[]): PreparedStatement {
    return new CloudflarePreparedStatement(this.statement.bind(...values));
  }

  first<T = Record<string, unknown>>(columnName?: string): Promise<T | null> {
    return columnName === undefined
      ? this.statement.first<T>()
      : this.statement.first<T>(columnName);
  }

  async all<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    const result = await this.statement.all<T>();
    return { results: result.results, meta: { changes: result.meta.changes } };
  }

  async run<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    const result = await this.statement.run<T>();
    return { results: result.results, meta: { changes: result.meta.changes } };
  }
}

class CloudflareDatabase implements Database {
  constructor(private readonly database: D1Database) {}

  prepare(query: string): PreparedStatement {
    return new CloudflarePreparedStatement(this.database.prepare(query));
  }

  async batch<T = unknown>(statements: PreparedStatement[]): Promise<DatabaseResult<T>[]> {
    const nativeStatements = statements.map((statement) => {
      if (!(statement instanceof CloudflarePreparedStatement)) {
        throw new TypeError("cloudflare_batch_received_foreign_statement");
      }
      return statement.nativeStatement();
    });
    const results = await this.database.batch<T>(nativeStatements);
    return results.map((result) => ({
      results: result.results,
      meta: { changes: result.meta.changes },
    }));
  }

  exec(query: string): Promise<DatabaseExecResult> {
    return this.database.exec(query);
  }
}

class CloudflareConfigStore implements ConfigStore {
  constructor(private readonly namespace: KVNamespace) {}

  get(key: string): Promise<string | null>;
  get<T = unknown>(key: string, type: "json"): Promise<T | null>;
  get<T = unknown>(key: string, type?: "json"): Promise<string | T | null> {
    return type === "json"
      ? this.namespace.get<T>(key, "json")
      : this.namespace.get(key, "text");
  }

  put(key: string, value: string, options?: { expiration?: number; expirationTtl?: number }) {
    return this.namespace.put(key, value, options);
  }

  delete(key: string) {
    return this.namespace.delete(key);
  }
}

export interface WorkerBindings extends Partial<CloudflareBindings> {
  CONFIG?: KVNamespace;
  USERS_DB?: D1Database;
  SUBSCRIPTIONS_DB?: D1Database;
  ADMIN_PATH?: string;
  ADMIN_TOKEN?: string;
  SERVICE_NAME?: string;
  CLIENT_TRANSPORT_PRIVATE_KEY?: string;
}

export function createWorkerEnv(bindings: WorkerBindings) {
  return {
    ...(bindings.CONFIG ? { CONFIG: new CloudflareConfigStore(bindings.CONFIG) } : {}),
    ...(bindings.USERS_DB ? { USERS_DB: new CloudflareDatabase(bindings.USERS_DB) } : {}),
    ...(bindings.SUBSCRIPTIONS_DB
      ? { SUBSCRIPTIONS_DB: new CloudflareDatabase(bindings.SUBSCRIPTIONS_DB) }
      : {}),
    ADMIN_PATH: bindings.ADMIN_PATH,
    ADMIN_TOKEN: bindings.ADMIN_TOKEN,
    SERVICE_NAME: bindings.SERVICE_NAME,
    CLIENT_TRANSPORT_PRIVATE_KEY: bindings.CLIENT_TRANSPORT_PRIVATE_KEY,
  };
}
