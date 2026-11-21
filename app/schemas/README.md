# Database schema histories through v37

Enhanced and upstream forked after the shared v25 schema. The v26..34 numbers
identify different schemas on the two branches. Enhanced also shipped a retired
v29 variant at 87195679. Enhanced v36 joined both histories through upstream v33.
Upstream v34 added shared-storage mounts, which the merge adds to Enhanced v37.

## Exports

- The canonical database directory keeps common v1..25 and upstream v26..34.
- Canonical v35 and v36 are previously shipped Enhanced exports.
- The `enhanced/` directory preserves Enhanced v26..35 byte-for-byte, including
  its alternate v34. The `enhanced-legacy29/` directory preserves the retired v29.
- Canonical v37 is the Room/KSP-generated export for the merged entities.

## Upgrade paths

[MigrationTo36](../src/main/java/me/rerere/rikkahub/data/db/migrations/MigrationTo36.kt)
provides shape-aware 25..35 → 36 bridges. Earlier versions follow the shared
migration chain through v25. The bridge adds the missing structures from both
branches, imports old memories into profile files, seeds consolidation markers,
backfills usage when the ledger is absent, and upgrades the message index. It
preserves existing profiles, markers, usage records, Enhanced jobs and workflows,
and the legacy MemoryEntity and retired workflow tables.

[Migration_36_37](../src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_36_37.kt)
adds `workspaces.storage_mounts` with the default `[]` when the column is absent.
Upstream v34 already has the column, so its mount lists survive both steps.
Room validates the final v37 schema after applying the migration chain.

## Regression tests

[MigrationTo36Test](../src/androidTest/java/me/rerere/rikkahub/data/db/migrations/MigrationTo36Test.kt)
loads 22 historical exports covering shared v25, upstream v26..34, Enhanced v26..36,
and retired v29. It inserts one sentinel row in every original table, validates
against Room's v37 export, compares every original column, and checks memory and
usage conversion, preserved and defaulted mount lists, and migration replay.
It also checks registration through the production factory. Room validation allows
extra legacy tables, whose contents are checked separately. The Android tests
require an emulator or device.

The [standalone verifier](../src/test/python/verify_merge_migration.py) runs the
production Kotlin migrations through a JVM adapter backed by SQLite JDBC. It
checks the same 22 histories and compares columns, defaults, keys, indexes, and
foreign keys against the KSP-generated v37 schema. Run from the repository root.

```sh
python3 app/src/test/python/verify_merge_migration.py
```

Requirements are Python 3.10+, kotlinc, Java, and sqlite-jdbc. The runner uses an
existing Gradle-cached JDBC jar or accepts --sqlite-jdbc /path/to/sqlite-jdbc.jar.
It builds its isolated migration harness in a temporary directory.
