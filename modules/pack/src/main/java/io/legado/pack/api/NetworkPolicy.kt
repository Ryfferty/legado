package io.legado.pack.api

import java.util.Locale

/**
 * A normalized domain allow-list pattern.
 *
 * * Exact patterns match one host.
 * * `*.example.com` matches subdomains only, never `example.com` itself.
 * * Ports, schemes and paths are not accepted; policy is host-based and the
 *   framework re-checks every redirect hop.
 * * A bare wildcard TLD (`*.com`) is rejected as too broad.
 * * IPv6 literals are not supported as patterns; use a different policy.
 */
@JvmInline
value class DomainPattern private constructor(val value: String) {

    fun matches(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        return if (value.startsWith("*.")) {
            val suffix = value.substring(1) // ".example.com"
            normalized.length > suffix.length && normalized.endsWith(suffix)
        } else {
            normalized == value
        }
    }

    companion object {
        private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        /** Returns null for invalid or over-broad patterns. */
        fun parse(raw: String): DomainPattern? {
            if (raw != raw.trim() || raw.isEmpty() || raw.length > 255) return null
            val normalized = raw.lowercase(Locale.ROOT).removeSuffix(".")
            if (normalized.isEmpty()) return null
            if (normalized.startsWith("*.")) {
                val suffix = normalized.substring(2)
                if (!isValidHost(suffix)) return null
                // Requiring at least two labels prevents `*.com` from matching all of a TLD.
                if (!suffix.contains('.')) return null
                return DomainPattern("*.$suffix")
            }
            if (!isValidHost(normalized)) return null
            return DomainPattern(normalized)
        }

        fun require(raw: String): DomainPattern =
            parse(raw) ?: throw IllegalArgumentException("Invalid domain pattern: '$raw'")

        private fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val labels = host.split('.')
            if (labels.any { it.isEmpty() || it.length > 63 || !LABEL.matches(it) }) return false
            return true
        }

        private fun normalizeHost(host: String): String? {
            if (host != host.trim() || host.isEmpty() || host.length > 253) return null
            if (host.contains(':') || host.contains('/') || host.contains('\\')) return null
            val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
            if (!isValidHost(normalized)) return null
            return normalized
        }
    }

    override fun toString(): String = value
}

/**
 * Three explicit network modes. A missing manifest field is always [None].
 */
sealed interface NetworkPolicy {

    fun permits(host: String): Boolean

    /** Deterministic form used by the permission hash. */
    fun canonical(): String

    data object None : NetworkPolicy {
        override fun permits(host: String): Boolean = false
        override fun canonical(): String = "none"
        override fun toString(): String = "none"
    }

    class Allowlist(patterns: List<DomainPattern>) : NetworkPolicy {
        val patterns: List<DomainPattern> = immutableList(patterns)

        init {
            require(patterns.isNotEmpty()) { "allowlist must contain at least one pattern" }
        }

        override fun permits(host: String): Boolean = patterns.any { it.matches(host) }

        override fun canonical(): String =
            "patterns:" + patterns.map { it.value }.sorted().joinToString(",")

        fun copy(patterns: List<DomainPattern> = this.patterns): Allowlist = Allowlist(patterns)

        override fun equals(other: Any?): Boolean =
            other is Allowlist && patterns == other.patterns

        override fun hashCode(): Int = patterns.hashCode()

        override fun toString(): String = canonical()
    }

    /**
     * Access to arbitrary hosts. The import UI must explicitly confirm this
     * mode; [PackPermissionSet.requiresOpenNetworkApproval] exposes that fact.
     */
    data class Open(val reason: String = "") : NetworkPolicy {
        override fun permits(host: String): Boolean = true
        override fun canonical(): String = "open:${reason.length}:$reason"
        override fun toString(): String = "open"
    }

    companion object {
        val DEFAULT: NetworkPolicy = None
    }
}