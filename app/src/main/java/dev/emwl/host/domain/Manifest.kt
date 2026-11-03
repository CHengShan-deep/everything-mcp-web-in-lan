package dev.emwl.host.domain

import org.json.JSONObject

enum class FieldType(val sqlType: String) {
    TEXT("TEXT"),
    INT("INTEGER"),
    REAL("REAL"),
    BOOLEAN("INTEGER"),
    DATETIME("INTEGER"),
    JSON("TEXT");

    companion object {
        fun parse(raw: String, field: String): FieldType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw DomainException("INVALID_MANIFEST", "unknown type for $field: $raw")
    }
}

data class FieldDef(
    val name: String,
    val type: FieldType,
    val required: Boolean,
    val maxLength: Int?,
    val default: String?,
)

data class TableDef(
    val name: String,
    val fields: List<FieldDef>,
    val indexes: List<List<String>>,
)

data class AuthPolicy(val read: String, val write: String) {

    val isReadProtected: Boolean get() = read != "none"

    fun validated(): AuthPolicy {
        if (read !in READ_MODES) {
            throw DomainException("INVALID_AUTH", "unsupported read mode: $read")
        }
        if (write !in WRITE_MODES) {
            throw DomainException("INVALID_AUTH", "unsupported write mode: $write")
        }
        return this
    }

    companion object {
        val READ_MODES = setOf("none", "token")
        val WRITE_MODES = setOf("token", "none")
        val DEFAULT = AuthPolicy(read = "none", write = "token")

        fun from(read: String?, write: String?): AuthPolicy =
            AuthPolicy(read ?: DEFAULT.read, write ?: DEFAULT.write).validated()
    }
}

data class Manifest(
    val schemaVersion: Int,
    val slug: String,
    val name: String,
    val entry: String,
    val spaFallback: Boolean,
    val auth: AuthPolicy,
    val tables: Map<String, TableDef>,
) {

    fun table(name: String): TableDef =
        tables[name] ?: throw DomainException("TABLE_NOT_FOUND", "table is not declared: $name")

    companion object {
        const val MAX_TABLES = 16
        const val MAX_FIELDS = 64
        const val MAX_INDEXES = 8
        const val MAX_LENGTH = 10_000

        fun parse(json: String): Manifest = try {
            parse(JSONObject(json))
        } catch (t: DomainException) {
            throw t
        } catch (t: Exception) {
            throw DomainException("INVALID_MANIFEST", "manifest is not valid JSON: ${t.message}")
        }

        fun parse(root: JSONObject): Manifest {
            val schemaVersion = root.optInt("schemaVersion", 0)
            if (schemaVersion != 1) {
                throw DomainException("INVALID_MANIFEST", "unsupported schemaVersion: $schemaVersion")
            }

            val slug = Identifiers.requireSlug(root.optString("slug", ""))

            val name = root.optString("name", slug).trim()
            if (name.isEmpty() || name.length > 80) {
                throw DomainException("INVALID_MANIFEST", "name must be 1-80 characters")
            }

            val entry = root.optString("entry", "index.html")
            if (!isSafeRelativePath(entry)) {
                throw DomainException("INVALID_MANIFEST", "entry must be a relative path: $entry")
            }

            val auth = root.optJSONObject("auth")
            val policy = AuthPolicy.from(auth?.optString("read"), auth?.optString("write"))

            val tablesJson = root.optJSONObject("tables") ?: JSONObject()
            val tables = LinkedHashMap<String, TableDef>()
            for (tableName in tablesJson.keys()) {
                if (tables.size >= MAX_TABLES) {
                    throw DomainException("INVALID_MANIFEST", "at most $MAX_TABLES tables are allowed")
                }
                tables[tableName] = parseTable(tableName, tablesJson.getJSONObject(tableName))
            }

            return Manifest(
                schemaVersion = schemaVersion,
                slug = slug,
                name = name,
                entry = entry,
                spaFallback = root.optBoolean("spaFallback", false),
                auth = policy,
                tables = tables,
            )
        }

        private fun parseTable(name: String, json: JSONObject): TableDef {
            Identifiers.requireIdentifier(name, "table")

            val fieldsJson = json.optJSONObject("fields") ?: JSONObject()
            val fields = ArrayList<FieldDef>()
            for (fieldName in fieldsJson.keys()) {
                if (fields.size >= MAX_FIELDS) {
                    throw DomainException("INVALID_MANIFEST", "at most $MAX_FIELDS fields per table")
                }
                fields += parseField(fieldName, fieldsJson.getJSONObject(fieldName))
            }
            if (fields.isEmpty()) {
                throw DomainException("INVALID_MANIFEST", "table $name has no fields")
            }

            val known = fields.map { it.name }.toSet()
            val indexesJson = json.optJSONArray("indexes")
            val indexes = ArrayList<List<String>>()
            if (indexesJson != null) {
                for (i in 0 until indexesJson.length()) {
                    if (indexes.size >= MAX_INDEXES) {
                        throw DomainException("INVALID_MANIFEST", "at most $MAX_INDEXES indexes per table")
                    }
                    val entry = indexesJson.getJSONObject(i)
                    val fieldsArray = entry.optJSONArray("fields")
                        ?: throw DomainException("INVALID_MANIFEST", "index entry needs fields")
                    val columns = ArrayList<String>()
                    for (j in 0 until fieldsArray.length()) {
                        val column = fieldsArray.getString(j)
                        if (column !in known) {
                            throw DomainException("INVALID_MANIFEST", "index references unknown field: $column")
                        }
                        columns += column
                    }
                    if (columns.isEmpty()) {
                        throw DomainException("INVALID_MANIFEST", "index entry needs at least one field")
                    }
                    indexes += columns
                }
            }

            return TableDef(name, fields, indexes)
        }

        private fun parseField(name: String, json: JSONObject): FieldDef {
            Identifiers.requireIdentifier(name, "field")
            val type = FieldType.parse(json.optString("type", "text"), name)

            val maxLengthRaw = if (json.has("maxLength")) json.getInt("maxLength") else null
            val maxLength = maxLengthRaw?.also {
                if (type != FieldType.TEXT) {
                    throw DomainException("INVALID_MANIFEST", "maxLength only applies to text fields: $name")
                }
                if (it !in 1..MAX_LENGTH) {
                    throw DomainException("INVALID_MANIFEST", "maxLength out of range for $name")
                }
            }

            val default = if (json.has("default")) normalizeDefault(name, type, json.get("default")) else null

            return FieldDef(
                name = name,
                type = type,
                required = json.optBoolean("required", false),
                maxLength = maxLength,
                default = default,
            )
        }

        private fun normalizeDefault(name: String, type: FieldType, raw: Any?): String = when (raw) {
            "now" -> {
                if (type != FieldType.DATETIME) {
                    throw DomainException("INVALID_MANIFEST", "default \"now\" requires datetime: $name")
                }
                "now"
            }
            is Number, is Boolean -> raw.toString()
            is String -> raw
            else -> throw DomainException("INVALID_MANIFEST", "unsupported default for $name")
        }

        fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.length > 200) return false
            if (path.startsWith("/") || path.startsWith("\\")) return false
            if (path.contains('\\')) return false
            if (path.contains("..")) return false
            return path.split('/').none { it.isEmpty() || it == "." }
        }
    }
}
