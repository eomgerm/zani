# Flyway Migration Guide

## Scope

This guide is the canonical instruction set for creating, reviewing, testing, and deploying database migrations in the backend repository. Read it before changing files under `src/main/resources/db/migration` or changing database initialization settings.

## Supported Database

- MySQL: `9.7.1`, matching the Docker development environment
- Storage engine: `InnoDB`
- Character set: `utf8mb4`
- Collation: `utf8mb4_0900_ai_ci`
- Migration location: `src/main/resources/db/migration`

Do not introduce syntax that is unsupported by the configured MySQL version. Keep schema behavior consistent across local, test, development, and production environments.

## Source of Truth

Flyway migrations are the only source of truth for database schema changes. Hibernate must use `ddl-auto: validate` and must never create or update the schema.

The initial schema is `V1__create_initial_schema.sql`. Once any migration has been applied to a shared environment:

- Never edit, rename, reorder, or delete it.
- Never change its checksum in `flyway_schema_history` manually.
- Never use `repair` to disguise an unintended content change.
- Add a new forward migration for every correction, including a change that restores an earlier schema shape.

For example, if V4 must be reversed, create V5 containing the compensating change. Do not roll the schema history back to V2.

## File Naming and Ordering

Use versioned migrations for schema changes and one-time data changes:

```text
V{sequential_integer}__{lower_snake_case_description}.sql
```

Examples:

```text
V2__add_member_last_login_at.sql
V3__backfill_member_last_login_at.sql
V4__make_member_last_login_at_not_null.sql
```

Rules:

- Allocate the next version against the latest target branch immediately before merge.
- Resolve duplicate versions by renumbering migrations that have not reached a shared environment.
- Keep `out-of-order` disabled; migrations run in ascending version order.
- Use a clear action-oriented description. Do not put issue keys, author names, dates, or environment names in filenames.
- Keep each file focused on one deployable change.

Repeatable migrations (`R__description.sql`) are allowed only for database objects whose complete definition must be reapplied when their checksum changes, such as views or stored procedures. Do not use repeatable migrations for tables, constraints, ordinary seed data, or rollback logic.

One exception exists: `src/main/resources/db/seed/R__demo_seed.sql`, the demonstration data set. It is repeatable because it declares a desired data state rather than a one-time change — editing it must reapply on the next start, which a versioned migration cannot do. It stays safe to reapply because it deletes its own ID band (`1000000000000`–`1000000999999`) in foreign-key reverse order before inserting. It also lives outside `db/migration`, and only the `local` and `dev` profiles add `classpath:db/seed` to `spring.flyway.locations`, so it never reaches production. Do not treat this exception as permission to add other seed files: any new one needs the same band isolation, the same profile scoping, and its own entry here.

## Authoring Rules

- Write forward-only, deterministic SQL.
- Use explicit names for primary keys, foreign keys, unique constraints, check constraints, and indexes.
- Use `InnoDB`, `utf8mb4`, and `utf8mb4_0900_ai_ci` for new tables.
- Do not add `DROP TABLE IF EXISTS` or disable `FOREIGN_KEY_CHECKS` in bootstrap or normal migrations.
- Do not depend on an environment-specific schema name, local file, session state, or application-generated value.
- Remember that MySQL DDL commonly performs implicit commits. Do not assume a multi-statement DDL migration is transactionally reversible.
- Separate lock-heavy or destructive DDL into a dedicated migration so its deployment risk is visible.
- Make data backfills bounded and restart-safe. For large datasets, perform the backfill through an explicitly planned application or batch process rather than one unbounded SQL statement.

For breaking changes, use expand-migrate-contract:

1. Expand: add the new nullable column, table, or compatible structure.
2. Migrate: deploy compatible application code and backfill existing data.
3. Contract: enforce constraints or remove the old structure only after all readers and writers have moved.

## Configuration Safety Rules

Keep these defaults enabled unless a separately reviewed operational plan requires a change:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    validate-migration-naming: true
    out-of-order: false
    clean-disabled: true
    baseline-on-migrate: false
```

`locations` is the one value a profile may extend: `local` and `dev` append `classpath:db/seed` for the demonstration data set described above. `prod` keeps `classpath:db/migration` alone, and no other profile may add a location.

`baseline-on-migrate` must remain disabled. A pre-existing non-empty database requires an environment-specific, audited onboarding plan; do not silently mark it as migrated. Flyway `clean` must remain disabled outside isolated disposable test databases.

## Development Workflow

1. Inspect the latest migration and the affected JPA mappings.
2. Add a failing integration test when the change affects schema compatibility or migration behavior.
3. Create the next versioned SQL file.
4. Start from an empty MySQL 9.7.1 database and run all migrations.
5. Verify Hibernate schema validation succeeds.
6. Run the full test suite against an empty configured MySQL database.
7. Review the SQL for locks, destructive operations, data volume, and compatibility with the current and next application releases.
8. When your migration touches a table the demonstration seed writes to, update `db/seed/R__demo_seed.sql` in the same change. This is an obligation, not a courtesy: the seed runs as a migration on `local` and `dev`, so a seed that no longer matches the schema fails the migration and the application does not start. Renaming a column, tightening a constraint, or narrowing an enum all qualify. `backend/scripts/verify-demo-seed.sql` reports the per-table row counts to confirm the seed still applies.

Use these commands from the backend directory:

```powershell
.\gradlew.bat test
```

## Deployment and Recovery

Before production migration, confirm a recoverable database backup or snapshot, review Flyway `info` and `validate`, and estimate the locking and runtime impact. Apply migrations once as a controlled deployment step, run database smoke checks, and then roll out application instances that expect the new schema.

Recovery is forward-only by default. Restore from backup only when the operational incident plan requires full database recovery. Otherwise, create and review a new compensating migration. Never delete Flyway history or reuse a version number to simulate rollback.

## Agent Checklist

Before completing migration work, verify all of the following:

- The filename follows the version and naming convention.
- Previously applied migration files are unchanged.
- The migration runs successfully on an empty MySQL 9.7.1 database.
- Running Flyway again applies zero migrations.
- Hibernate validates the resulting schema.
- New tables use the required engine, character set, and collation.
- Constraints and indexes have explicit, stable names.
- Destructive and lock-heavy operations have a reviewed rollout and recovery plan.
- Focused and full test commands and their exact results are reported.
