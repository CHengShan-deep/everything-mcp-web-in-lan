package dev.emwl.host.domain

/**
 * Query parsing for the generated REST surface. Values are always bound as SQL
 * parameters; LIKE wildcards inside user values are escaped rather than dropped.
 */
object QueryFilters {

    val OPERATORS = setOf("eq", "ne", "gt", "gte", "lt", "lte", "like")

    data class Filter(val field: String, val op: String, val value: String)

    fun parseFilter(field: String, raw: String): Filter {
        val dot = raw.indexOf('.')
        if (dot <= 0) {
            throw DomainException("INVALID_FILTER", "filter must be <op>.<value>: $raw")
        }
        val op = raw.substring(0, dot)
        if (op !in OPERATORS) {
            throw DomainException("INVALID_FILTER", "unsupported operator: $op")
        }
        return Filter(field, op, raw.substring(dot + 1))
    }

    fun sqlFor(filter: Filter): Pair<String, Any> {
        val column = "\"${filter.field}\""
        val value: Any = filter.value
        val sql = when (filter.op) {
            "eq" -> "$column = ?"
            "ne" -> "$column <> ?"
            "gt" -> "$column > ?"
            "gte" -> "$column >= ?"
            "lt" -> "$column < ?"
            "lte" -> "$column <= ?"
            "like" -> "$column LIKE ? ESCAPE '\\'"
            else -> throw DomainException("INVALID_FILTER", "unsupported operator: ${filter.op}")
        }
        return sql to if (filter.op == "like") escapeLike(value.toString()) else value
    }

    /** Escapes backslash first, then the wildcards, so user data matches literally. */
    fun escapeLike(value: String): String {
        val escaped = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> escaped.append("\\\\")
                '%' -> escaped.append("\\%")
                '_' -> escaped.append("\\_")
                else -> escaped.append(ch)
            }
        }
        return escaped.toString()
    }

    fun parseOrder(raw: String?, allowed: Set<String>): Pair<String, String>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(Regex("\\s+"))
        val field = parts[0]
        if (field !in allowed) {
            throw DomainException("INVALID_ORDER", "cannot order by: $field")
        }
        val direction = when (parts.getOrNull(1)?.lowercase()) {
            null, "asc" -> "ASC"
            "desc" -> "DESC"
            else -> throw DomainException("INVALID_ORDER", "direction must be asc or desc")
        }
        return field to direction
    }

    fun parseLimit(raw: String?, default: Int = 20, max: Int = 100): Int {
        val parsed = raw?.toIntOrNull() ?: return default
        if (parsed <= 0) throw DomainException("INVALID_LIMIT", "limit must be positive")
        return parsed.coerceAtMost(max)
    }

    fun parseOffset(raw: String?): Int {
        val parsed = raw?.toIntOrNull() ?: return 0
        if (parsed < 0) throw DomainException("INVALID_OFFSET", "offset must be >= 0")
        return parsed
    }
}
