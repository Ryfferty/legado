package io.legado.pack.api

/**
 * Stable identifier of an installed pack.
 *
 * The value is used as a single directory name below the host-provided
 * `packs` root, therefore it is restricted to lower-case ASCII DNS-like
 * labels. Separators may only appear between labels, which rejects `..`,
 * leading dots, trailing dots and drive-letter style paths outright.
 */
@JvmInline
value class PackId private constructor(val value: String) {

    companion object {
        private val FORMAT = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        private val WINDOWS_RESERVED = buildSet {
            addAll(listOf("con", "prn", "aux", "nul"))
            for (i in 1..9) {
                add("com$i")
                add("lpt$i")
            }
        }

        /** Returns null instead of throwing so manifest validation can report a typed diagnostic. */
        fun parse(raw: String): PackId? {
            if (raw != raw.trim()) return null
            if (raw.length !in 2..64) return null
            if (!FORMAT.matches(raw)) return null
            if (raw in WINDOWS_RESERVED) return null
            return PackId(raw)
        }

        fun require(raw: String): PackId =
            parse(raw) ?: throw IllegalArgumentException("Invalid pack id: '$raw'")
    }

    override fun toString(): String = value
}

/**
 * Immutable revision identifier of pack code. A revision is the lower-case
 * SHA-256 of the staged content and is also used as the code directory name.
 */
@JvmInline
value class RevisionId private constructor(val value: String) {

    companion object {
        private val FORMAT = Regex("[0-9a-f]{64}")

        fun parse(raw: String): RevisionId? {
            if (raw != raw.trim()) return null
            if (!FORMAT.matches(raw)) return null
            return RevisionId(raw)
        }

        fun require(raw: String): RevisionId =
            parse(raw) ?: throw IllegalArgumentException("Revision must be 64 lower-case hex characters")
    }

    override fun toString(): String = value
}

/**
 * A normalized, relative file path inside a pack.
 *
 * The framework never accepts absolute paths, backslashes, empty segments,
 * `.`/`..`, drive letters, control characters or Unicode-normalization
 * equivalents. Requiring printable ASCII keeps ZIP extraction, staging,
 * Linux directories and Windows test hosts on one canonical spelling.
 */
@JvmInline
value class RelativePath private constructor(val value: String) {

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private const val MAX_LENGTH = 512

        fun parse(raw: String): RelativePath? {
            if (raw != raw.trim()) return null
            if (raw.isEmpty() || raw.length > MAX_LENGTH) return null
            if (raw.startsWith("/") || raw.contains('\\') || raw.contains('\u0000')) return null
            val segments = raw.split('/')
            if (segments.any { segment ->
                    segment.isEmpty() ||
                        !SEGMENT.matches(segment) ||
                        segment.endsWith(".") ||
                        segment.endsWith(" ")
                }
            ) {
                return null
            }
            return RelativePath(raw)
        }

        fun require(raw: String): RelativePath =
            parse(raw) ?: throw IllegalArgumentException("Invalid relative pack path: '$raw'")
    }

    override fun toString(): String = value
}