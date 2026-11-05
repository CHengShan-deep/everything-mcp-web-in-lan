package dev.emwl.host.domain

/**
 * Maps a request's Host header to a site slug for the single-port hub. Only names under the
 * configured base domain resolve, so a stray host header cannot pick an arbitrary site.
 */
object HostRouting {

    fun slugForHost(host: String?, baseDomain: String): String? {
        if (host.isNullOrBlank()) return null
        val domain = baseDomain.trim().lowercase()
        if (domain.isEmpty()) return null

        val name = host
            .substringBefore(':')
            .trim()
            .lowercase()
            .removeSuffix(".")
        val suffix = ".$domain"
        if (!name.endsWith(suffix)) return null

        val slug = name.removeSuffix(suffix)
        if (slug.isEmpty() || slug.contains('.')) return null
        return slug
    }
}
