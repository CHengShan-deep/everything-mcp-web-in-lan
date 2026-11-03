package dev.emwl.host.domain

class DomainException(val code: String, message: String) : IllegalArgumentException(message)

object Identifiers {

    private val IDENTIFIER = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val SLUG = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    private val RESERVED = setOf("id", "rowid", "oid", "_rowid_", "sqlite_master")

    fun isIdentifier(value: String): Boolean = IDENTIFIER.matches(value)

    fun isSlug(value: String): Boolean = SLUG.matches(value)

    fun requireIdentifier(value: String, what: String): String {
        if (!isIdentifier(value)) {
            throw DomainException("INVALID_IDENTIFIER", "$what is not a valid identifier: $value")
        }
        if (value in RESERVED) {
            throw DomainException("RESERVED_IDENTIFIER", "$what uses a reserved name: $value")
        }
        return value
    }

    fun requireSlug(value: String): String {
        if (!isSlug(value)) {
            throw DomainException("INVALID_SLUG", "slug must match [a-z0-9-], 1-63 chars: $value")
        }
        return value
    }
}
