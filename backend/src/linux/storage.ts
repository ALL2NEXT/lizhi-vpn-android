import { mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { DatabaseSync, type SQLInputValue } from "node:sqlite";
import type {
  ConfigStore,
  Database,
  DatabaseExecResult,
  DatabaseResult,
  PreparedStatement,
} from "../storage/contracts.ts";

type SqliteStatement = ReturnType<DatabaseSync["prepare"]>;
type KvPutOptions = {
  expiration?: number;
  expirationTtl?: number;
};

function asResult<T>(results: T[] = [], changes = 0): DatabaseResult<T> {
  return {
    results,
    meta: { changes },
  };
}

function normalizeSqliteValue(value: unknown): SQLInputValue {
  if (value === null || value === undefined) return null;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (typeof value === "string" || typeof value === "number" || typeof value === "bigint") {
    return value;
  }
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    const bytes = new Uint8Array(value.byteLength);
    bytes.set(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
    return bytes;
  }
  throw new TypeError("sqlite_unsupported_bind_value");
}

class SqlitePreparedStatement implements PreparedStatement {
  private values: SQLInputValue[] = [];

  constructor(
    private readonly database: DatabaseSync,
    private readonly sql: string,
  ) {}

  bind(...values: unknown[]): PreparedStatement {
    this.values = values.map(normalizeSqliteValue);
    return this;
  }

  private statement(): SqliteStatement {
    return this.database.prepare(this.sql);
  }

  first<T = Record<string, unknown>>(columnName?: string): Promise<T | null> {
    const row = this.statement().get(...this.values) as Record<string, unknown> | undefined;
    if (!row) return Promise.resolve(null);
    return Promise.resolve((columnName ? row[columnName] : row) as T);
  }

  all<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    const rows = this.statement().all(...this.values) as T[];
    return Promise.resolve(asResult(rows));
  }

  run<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    return Promise.resolve(this.executeRun<T>());
  }

  executeAll<T = Record<string, unknown>>() {
    const rows = this.statement().all(...this.values) as T[];
    return asResult(rows);
  }

  executeRun<T = Record<string, unknown>>() {
    const result = this.statement().run(...this.values);
    return asResult<T>([], Number(result.changes));
  }

  executeForBatch<T = Record<string, unknown>>() {
    const statement = this.statement();
    return statement.columns().length > 0
      ? asResult(statement.all(...this.values) as T[])
      : this.executeRun<T>();
  }
}

class SqliteDatabase implements Database {
  constructor(readonly database: DatabaseSync) {}

  prepare(query: string): PreparedStatement {
    return new SqlitePreparedStatement(this.database, query);
  }

  batch<T = unknown>(statements: PreparedStatement[]): Promise<DatabaseResult<T>[]> {
    const transaction = this.database.isTransaction;
    if (transaction) throw new Error("sqlite_batch_nested_transaction");
    this.database.exec("BEGIN IMMEDIATE");
    try {
      const results = statements.map((statement) => {
        if (!(statement instanceof SqlitePreparedStatement)) {
          throw new TypeError("sqlite_batch_received_foreign_statement");
        }
        return statement.executeForBatch<T>();
      });
      this.database.exec("COMMIT");
      return Promise.resolve(results);
    } catch (error) {
      this.database.exec("ROLLBACK");
      throw error;
    }
  }

  exec(query: string): Promise<DatabaseExecResult> {
    const startedAt = performance.now();
    this.database.exec(query);
    return Promise.resolve({ count: 0, duration: performance.now() - startedAt });
  }

  close() {
    this.database.close();
  }
}

class SqliteConfigStore implements ConfigStore {
  private readonly readStatement: SqliteStatement;
  private readonly writeStatement: SqliteStatement;
  private readonly deleteStatement: SqliteStatement;

  constructor(private readonly database: DatabaseSync) {
    database.exec(`
      CREATE TABLE IF NOT EXISTS linux_config_kv (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        expires_at INTEGER
      )
    `);
    this.readStatement = database.prepare(
      "SELECT value, expires_at FROM linux_config_kv WHERE key = ?",
    );
    this.writeStatement = database.prepare(
      `INSERT INTO linux_config_kv (key, value, expires_at) VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, expires_at = excluded.expires_at`,
    );
    this.deleteStatement = database.prepare("DELETE FROM linux_config_kv WHERE key = ?");
  }

  get(key: string): Promise<string | null>;
  get<T = unknown>(key: string, type: "json"): Promise<T | null>;
  async get<T = unknown>(key: string, type?: "json"): Promise<string | T | null> {
    const row = this.readStatement.get(key) as { value: string; expires_at: number | null } | undefined;
    if (!row) return null;
    if (row.expires_at !== null && row.expires_at <= Date.now()) {
      this.deleteStatement.run(key);
      return null;
    }
    if (type === "json") return JSON.parse(row.value) as T;
    return row.value;
  }

  async put(key: string, value: string, options: KvPutOptions = {}) {
    const text = value;
    const expiresAt = options.expiration
      ? Math.floor(options.expiration * 1000)
      : options.expirationTtl
        ? Date.now() + Math.floor(options.expirationTtl * 1000)
        : null;
    this.writeStatement.run(key, text, expiresAt);
  }

  async delete(key: string) {
    this.deleteStatement.run(key);
  }
}

function openDatabase(path: string) {
  const database = new DatabaseSync(path);
  database.exec(`
    PRAGMA foreign_keys = ON;
    PRAGMA busy_timeout = 5000;
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
  `);
  return database;
}

function applySubscriptionMigrations(database: DatabaseSync, directory: string) {
  database.exec(`
    CREATE TABLE IF NOT EXISTS linux_backend_migrations (
      name TEXT PRIMARY KEY,
      applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
  `);
  const migrations = readdirSync(directory)
    .filter((name) => /^\d+.*\.sql$/i.test(name))
    .sort((left, right) => left.localeCompare(right));
  for (const name of migrations) {
    const applied = database.prepare(
      "SELECT name FROM linux_backend_migrations WHERE name = ?",
    ).get(name);
    if (applied) continue;
    const sql = readFileSync(join(directory, name), "utf8");
    database.exec("BEGIN IMMEDIATE");
    try {
      database.exec(sql);
      database.prepare("INSERT INTO linux_backend_migrations (name) VALUES (?)").run(name);
      database.exec("COMMIT");
    } catch (error) {
      database.exec("ROLLBACK");
      throw new Error(`subscription_migration_failed:${name}`, { cause: error });
    }
  }
}

export function createLinuxStorage(dataDirectory: string, migrationDirectory: string) {
  mkdirSync(dataDirectory, { recursive: true });
  const users = new SqliteDatabase(openDatabase(join(dataDirectory, "users.sqlite")));
  const subscriptions = new SqliteDatabase(openDatabase(join(dataDirectory, "subscriptions.sqlite")));
  const configDatabase = openDatabase(join(dataDirectory, "config.sqlite"));
  try {
    applySubscriptionMigrations(subscriptions.database, migrationDirectory);
    const CONFIG = new SqliteConfigStore(configDatabase);
    return {
      USERS_DB: users,
      SUBSCRIPTIONS_DB: subscriptions,
      CONFIG,
      close() {
        users.close();
        subscriptions.close();
        configDatabase.close();
      },
    };
  } catch (error) {
    users.close();
    subscriptions.close();
    configDatabase.close();
    throw error;
  }
}
