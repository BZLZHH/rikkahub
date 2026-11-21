#!/usr/bin/env python3
"""Run the real fork-merge migrations through v37 on SQLite without Android/Gradle.

Requires kotlinc, java and sqlite-jdbc (auto-detected in ~/.gradle/caches, or pass
--sqlite-jdbc). Android interfaces are minimal test adapters; migration SQL and
branching execute from the production Kotlin sources, not a reimplementation.
Room's full generated validation and factory path are covered by MigrationTo36Test.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "settings.gradle.kts").exists())
SCHEMAS = ROOT / "app/schemas"
DATABASE = "me.rerere.rikkahub.data.db.AppDatabase"
MOUNT_LIST = '[{"path":"/storage/emulated/0/Documents","writable":true}]'
MIGRATIONS = ROOT / "app/src/main/java/me/rerere/rikkahub/data/db/migrations"
STUBS = json.loads(r'''{"Room.kt":"package androidx.room.migration\nimport androidx.sqlite.db.SupportSQLiteDatabase\nabstract class Migration(val startVersion: Int, val endVersion: Int) { abstract fun migrate(db: SupportSQLiteDatabase) }\ninterface AutoMigrationSpec { fun onPostMigrate(db: SupportSQLiteDatabase) {} }\n","Sqlite.kt":"package androidx.sqlite.db\ninterface SupportSQLiteDatabase {\n fun execSQL(sql:String)\n fun execSQL(sql:String, args:Array<out Any?>)\n fun query(sql:String): Cursor\n fun query(sql:String,args:Array<out Any?>): Cursor\n fun beginTransaction()\n fun setTransactionSuccessful()\n fun endTransaction()\n}\ninterface Cursor: java.io.Closeable {\n fun moveToFirst():Boolean\n fun moveToNext():Boolean\n fun getString(index:Int):String\n fun getColumnIndexOrThrow(name:String):Int\n}\n","Log.kt":"package android.util\nobject Log { fun i(tag:String,message:String) = 0 }\n","Tracker.kt":"package me.rerere.rikkahub.data.db\nobject DatabaseMigrationTracker { fun onMigrationStart(from:Int,to:Int) {} ; fun onMigrationEnd() {} }\n","Memory.kt":"package me.rerere.rikkahub.data.model\nobject MemoryFile { const val PROFILE_PATH = \"/profile.md\" }\n"}''')
HEADER = r'''import java.sql.DriverManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.Cursor
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.MigrationsTo36

class Db : SupportSQLiteDatabase, java.io.Closeable {
 val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
 private var depth = 0
 override fun execSQL(sql:String) { connection.createStatement().use { it.execute(sql) } }
 override fun execSQL(sql:String,args:Array<out Any?>) { connection.prepareStatement(sql).use { s -> args.forEachIndexed { i,v -> s.setObject(i+1,v) };s.execute() } }
 fun rows(sql:String,args:Array<out Any?> = emptyArray()): Pair<List<String>,List<List<String?>>> = connection.prepareStatement(sql).use { s ->
  args.forEachIndexed { i,v -> s.setObject(i+1,v) }
  s.executeQuery().use { r -> val columns=(1..r.metaData.columnCount).map { r.metaData.getColumnLabel(it) }; val rows= mutableListOf<List<String?>>()
   while(r.next()) rows.add((1..columns.size).map { r.getString(it) }); columns to rows
  }
 }
 override fun query(sql:String): Cursor = query(sql,emptyArray())
 override fun query(sql:String,args:Array<out Any?>): Cursor { val (names,rows)=rows(sql,args);return object:Cursor {
  var i = -1
  override fun moveToFirst():Boolean { i=0;return rows.isNotEmpty() }
  override fun moveToNext():Boolean { i++;return i<rows.size }
  override fun getString(index:Int):String = rows[i][index]!!
  override fun getColumnIndexOrThrow(name:String):Int = names.indexOf(name).also { check(it>=0) }
  override fun close() {}
 } }
 override fun beginTransaction() { if(depth++==0) connection.autoCommit=false }
 override fun setTransactionSuccessful() {}
 override fun endTransaction() { if(--depth==0) { connection.commit();connection.autoCommit=true } }
 override fun close() = connection.close()
 fun shape(table:String):String {
  val columns=rows("PRAGMA table_info('$table')").second.map { it.drop(1) }.sortedBy { it[0] }
  val indices=rows("PRAGMA index_list('$table')").second.filter { it[3]!="pk" }.map { listOf(it[1],it[2],rows("PRAGMA index_xinfo('${it[1]}')").second.map { row -> row.drop(2) }.toString()) }.sortedBy { it[0] }
  val keys=rows("PRAGMA foreign_key_list('$table')").second.map { it.drop(1) }.sortedBy { it.toString() }
  return listOf(columns,indices,keys).toString()
 }
 fun count(table:String) = rows("SELECT count(*) FROM `$table`").second.single().single()!!.toInt()
}
data class Fixture(val name:String,val version:Int,val ddl:List<String>,val seed:List<String>,val tables:List<String>)
'''
MAIN = r'''fun main() {
 Class.forName("org.sqlite.JDBC")
 Db().use { expected ->
  __TARGET_DDL__.forEach(expected::execSQL)
  val targetTables = __TARGET_TABLES__
  val mountList = __MOUNT_LIST__
  val fixtures = __FIXTURES__
  fixtures.forEach { fixture -> Db().use { db ->
   fixture.ddl.forEach(db::execSQL);fixture.seed.forEach(db::execSQL)
   val before=fixture.tables.associateWith { db.rows("SELECT * FROM `$it`") }
   val bridge = MigrationsTo36.singleOrNull { it.startVersion == fixture.version }
   check(bridge != null || fixture.version == Migration_36_37.startVersion)
   fun migrate() {
    bridge?.migrate(db)
    Migration_36_37.migrate(db)
   }
   fun verify() {
    targetTables.forEach { table -> check(db.shape(table)==expected.shape(table)) { "Schema mismatch ${fixture.name} $table\nACTUAL ${db.shape(table)}\nEXPECTED ${expected.shape(table)}" } }
    before.forEach { (table,snapshot) -> val select=snapshot.first.joinToString { "`$it`" };check(db.rows("SELECT $select FROM `$table`").second==snapshot.second) { "Data loss ${fixture.name} $table" } }
    check(db.count("usage_record")==1) { "Duplicate usage ${fixture.name}" }
    check(db.count("memory_file")==1)
    val expectedMounts = if ("storage_mounts" in before.getValue("workspaces").first) mountList else "[]"
    check(db.rows("SELECT storage_mounts FROM workspaces").second == listOf(listOf(expectedMounts))) { "Mount list mismatch ${fixture.name}" }
    check(db.rows("PRAGMA foreign_key_check").second.isEmpty()) { "FK violation ${fixture.name}" }
    if("usage_record" !in fixture.tables) check(db.rows("SELECT prompt_tokens,completion_tokens,cached_tokens FROM usage_record").second.single()==listOf("7","3","2"))
    if("MemoryEntity" in fixture.tables) check(db.rows("SELECT content FROM memory_file").second.single().single()!!.contains("- [stated] keep"))
   }
   migrate();verify();migrate();verify()
   println("PASS ${fixture.name}: schema37 exact metadata, every source row preserved, replay safe")
  } }
  println("PASS ALL ${fixtures.size} HISTORICAL SCHEMAS")
 }
}'''


def quote(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def kotlin_list(values):
    return "listOf(" + ",\n".join(map(quote, values)) + ")"


def schema(family, version):
    return json.loads((SCHEMAS / family / DATABASE / f"{version}.json").read_text())["database"]


def ddl(document):
    return [
        sql.replace("${TABLE_NAME}", entity["tableName"])
        for entity in document["entities"]
        for sql in [entity["createSql"], *(index["createSql"] for index in entity.get("indices", []))]
    ]


def fixture(index, family, version):
    document = schema(family, version)
    inserts = []
    for entity in document["entities"]:
        columns = [f"`{field['columnName']}`" for field in entity["fields"]]
        values = []
        for field in entity["fields"]:
            if entity["tableName"] == "message_node" and field["columnName"] == "messages":
                values.append("'" + json.dumps([{
                    "role": "assistant", "createdAt": "2026-08-12T09:00:00",
                    "usage": {"promptTokens": 7, "completionTokens": 3, "cachedTokens": 2},
                }]) + "'")
            elif entity["tableName"] == "workspaces" and field["columnName"] == "storage_mounts":
                values.append("'" + MOUNT_LIST + "'")
            else:
                values.append("1" if field["affinity"] in ("INTEGER", "REAL") else "'keep'")
        inserts.append(f"INSERT INTO `{entity['tableName']}` ({','.join(columns)}) VALUES ({','.join(values)})")
    branch = family or ("enhanced" if version == 36 else "upstream")
    label = f"{branch}/{version}"
    return (f"fun fixture{index}() = Fixture({quote(label)},{version},"
            f"{kotlin_list(ddl(document))},{kotlin_list(inserts)},"
            f"{kotlin_list([entity['tableName'] for entity in document['entities']])})\n")


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite-jdbc", type=Path)
    args = parser.parse_args()
    jdbc = args.sqlite_jdbc
    if jdbc is None:
        cached = sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc").glob("*/*/*.jar"))
        if cached:
            jdbc = cached[-1]
    if jdbc is None or not jdbc.is_file():
        parser.error("Pass --sqlite-jdbc /path/to/sqlite-jdbc.jar")
    compiler = shutil.which("kotlinc")
    java = shutil.which("java")
    if not compiler or not java:
        parser.error("kotlinc and java must be on PATH")
    cases = [("", version) for version in range(25, 35)] + [
        ("enhanced", version) for version in range(26, 36)
    ] + [("enhanced-legacy29", 29), ("", 36)]
    target = schema("", 37)
    main = (MAIN.replace("__TARGET_DDL__", kotlin_list(ddl(target)))
            .replace("__TARGET_TABLES__", kotlin_list([entity["tableName"] for entity in target["entities"]]))
            .replace("__MOUNT_LIST__", quote(MOUNT_LIST))
            .replace("__FIXTURES__", "listOf(" + ",".join(f"fixture{i}()" for i in range(len(cases))) + ")"))
    with tempfile.TemporaryDirectory(prefix="rikkahub-db-migration-") as temp:
        folder = Path(temp)
        for name, source in STUBS.items():
            (folder / name).write_text(source)
        (folder / "Harness.kt").write_text(HEADER + "".join(
            fixture(i, family, version) for i, (family, version) in enumerate(cases)
        ) + main)
        sources = ["MigrationTo36.kt", "Migration_27_28.kt", "Migration_28_29.kt",
                   "Migration_30_31.kt", "Migration_32_33.kt", "Migration_36_37.kt"]
        jar = folder / "test.jar"
        subprocess.run([compiler, *map(str, folder.glob("*.kt")),
                        *(str(MIGRATIONS / source) for source in sources),
                        "-include-runtime", "-d", str(jar)], check=True)
        subprocess.run([java, "-cp", f"{jar}:{jdbc.resolve()}", "HarnessKt"], check=True)


if __name__ == "__main__":
    run()
