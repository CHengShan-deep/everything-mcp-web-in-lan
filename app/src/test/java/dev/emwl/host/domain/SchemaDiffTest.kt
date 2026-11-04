package dev.emwl.host.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaDiffTest {

    private fun field(
        name: String,
        type: FieldType = FieldType.TEXT,
        required: Boolean = false,
        default: String? = null,
    ) = FieldDef(name, type, required, null, default)

    private fun table(name: String, vararg fields: FieldDef) =
        TableDef(name, fields.toList(), emptyList())

    private fun manifest(vararg tables: TableDef) = Manifest(
        schemaVersion = 1,
        slug = "demo",
        name = "Demo",
        entry = "index.html",
        spaFallback = false,
        auth = AuthPolicy.DEFAULT,
        tables = tables.associateBy { it.name },
    )

    private fun column(name: String, type: String = "TEXT", notNull: Boolean = false) =
        ColumnState(name, type, notNull)

    private fun state(name: String, rows: Int, vararg columns: ColumnState) =
        TableState(name, columns.toList(), rows)

    @Test
    fun `an unchanged schema plans nothing`() {
        val current = listOf(state("notes", 3, column("id", "INTEGER"), column("title")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("title"))))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.destructive.isEmpty())
    }

    @Test
    fun `a new table is additive`() {
        val plan = SchemaDiff.plan(emptyList(), manifest(table("notes", field("title"))))
        assertEquals(listOf("create_table"), plan.changes.map { it.kind })
        assertFalse(plan.changes.single().destructive)
    }

    @Test
    fun `a new optional column is additive`() {
        val current = listOf(state("notes", 5, column("id", "INTEGER")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("pinned", FieldType.BOOLEAN))))
        assertEquals(listOf("add_column"), plan.changes.map { it.kind })
        assertTrue(plan.destructive.isEmpty())
        assertTrue(plan.rebuild.isEmpty())
    }

    @Test
    fun `a required column needs a backfill only when rows exist`() {
        val empty = SchemaDiff.plan(
            listOf(state("notes", 0, column("id", "INTEGER"))),
            manifest(table("notes", field("title", required = true))),
        )
        assertEquals(listOf("add_column"), empty.changes.map { it.kind })

        val populated = SchemaDiff.plan(
            listOf(state("notes", 2, column("id", "INTEGER"))),
            manifest(table("notes", field("title", required = true))),
        )
        assertEquals(listOf("backfill_column"), populated.changes.map { it.kind })
        assertTrue(populated.destructive.single().destructive)
        assertEquals(setOf("notes"), populated.rebuild)
    }

    @Test
    fun `dropping a field is destructive and rebuilds the table`() {
        val current = listOf(state("notes", 4, column("id", "INTEGER"), column("title"), column("legacy")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("title"))))
        assertEquals(listOf("drop_column"), plan.changes.map { it.kind })
        assertEquals("drop column notes.legacy", plan.summary())
        assertEquals(setOf("notes"), plan.rebuild)
    }

    @Test
    fun `changing a field type is destructive`() {
        val current = listOf(state("notes", 1, column("id", "INTEGER"), column("count", "TEXT")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("count", FieldType.INT))))
        assertEquals(listOf("change_type"), plan.changes.map { it.kind })
        assertEquals("TEXT → INTEGER", plan.changes.single().detail)
        assertTrue(plan.changes.single().destructive)
    }

    @Test
    fun `relaxing a required field keeps the data`() {
        val current = listOf(state("notes", 2, column("id", "INTEGER"), column("title", "TEXT", notNull = true)))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("title"))))
        assertEquals(listOf("relax_required"), plan.changes.map { it.kind })
        assertFalse(plan.changes.single().destructive)
        assertEquals(setOf("notes"), plan.rebuild)
    }

    @Test
    fun `enforcing a required field on populated rows is destructive`() {
        val current = listOf(state("notes", 2, column("id", "INTEGER"), column("title")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("title", required = true))))
        assertEquals(listOf("enforce_required"), plan.changes.map { it.kind })
        assertTrue(plan.changes.single().destructive)

        val empty = SchemaDiff.plan(
            listOf(state("notes", 0, column("id", "INTEGER"), column("title"))),
            manifest(table("notes", field("title", required = true))),
        )
        assertFalse(empty.changes.single().destructive)
    }

    @Test
    fun `removing a whole table is destructive`() {
        val current = listOf(state("notes", 1, column("id", "INTEGER")), state("old", 3, column("id", "INTEGER")))
        val plan = SchemaDiff.plan(current, manifest(table("notes")))
        assertEquals(listOf("drop_table"), plan.changes.map { it.kind })
        assertEquals(listOf("old"), plan.dropTables)
        assertTrue(plan.toJson().getBoolean("requiresConfirmation"))
    }

    @Test
    fun `the primary key is never treated as a removed field`() {
        val current = listOf(state("notes", 1, column("id", "INTEGER"), column("title")))
        val plan = SchemaDiff.plan(current, manifest(table("notes", field("title"))))
        assertTrue(plan.changes.isEmpty())
    }
}
