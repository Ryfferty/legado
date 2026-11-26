package io.legado.pack.api

/**
 * The framework's pack-library version. This is intentionally independent
 * from the Android app versionCode and from a pack's own version.
 *
 * Grammar: exactly `major.minor.patch`, decimal, non-negative. The parser
 * rejects floating-point or partially specified versions instead of guessing.
 */
data class PackLibVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<PackLibVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "version components must be >= 0" }
    }

    override fun compareTo(other: PackLibVersion): Int {
        val majorResult = major.compareTo(other.major)
        if (majorResult != 0) return majorResult
        val minorResult = minor.compareTo(other.minor)
        if (minorResult != 0) return minorResult
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        val CURRENT: PackLibVersion = PackLibVersion(1, 0, 0)

        private val FORMAT = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

        fun parse(raw: String): PackLibVersion? {
            if (raw != raw.trim()) return null
            val match = FORMAT.matchEntire(raw) ?: return null
            return PackLibVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }

        fun require(raw: String): PackLibVersion =
            parse(raw) ?: throw IllegalArgumentException("Invalid packLib version: '$raw'")
    }
}

/**
 * Fixed grammar for the manifest `packLib` gate:
 *
 * * `*` accepts every framework version.
 * * Otherwise one or more space-separated comparators:
 *   `>=1.0.0`, `>1.0.0`, `<=2.0.0`, `<2.0.0`, `=1.2.3`.
 *
 * No caret, tilde, OR ranges or floating-point comparison are supported.
 */
class VersionRange private constructor(
    private val constraints: List<Constraint>,
) {
    fun contains(version: PackLibVersion): Boolean = constraints.all { it.matches(version) }

    override fun equals(other: Any?): Boolean =
        other is VersionRange && constraints == other.constraints

    override fun hashCode(): Int = constraints.hashCode()

    override fun toString(): String =
        if (constraints.isEmpty()) "*" else constraints.joinToString(" ") { it.toString() }

    data class Constraint(
        val operator: Operator,
        val version: PackLibVersion,
    ) {
        fun matches(candidate: PackLibVersion): Boolean {
            val comparison = candidate.compareTo(version)
            return when (operator) {
                Operator.GE -> comparison >= 0
                Operator.GT -> comparison > 0
                Operator.LE -> comparison <= 0
                Operator.LT -> comparison < 0
                Operator.EQ -> comparison == 0
            }
        }

        override fun toString(): String = "${operator.symbol}${version}"
    }

    enum class Operator(val symbol: String) {
        GE(">="),
        GT(">"),
        LE("<="),
        LT("<"),
        EQ("="),
    }

    companion object {
        val ANY: VersionRange = VersionRange(emptyList())

        private val OPERATORS = listOf(
            Operator.GE,
            Operator.GT,
            Operator.LE,
            Operator.LT,
            Operator.EQ,
        )
        private val WHITESPACE = Regex("\\s+")

        fun parse(raw: String): VersionRange? {
            if (raw != raw.trim() || raw.isEmpty()) return null
            if (raw == "*") return ANY
            val parts = raw.split(WHITESPACE)
            if (parts.isEmpty()) return null
            val constraints = ArrayList<Constraint>(parts.size)
            for (part in parts) {
                val operator = OPERATORS.firstOrNull { part.startsWith(it.symbol) } ?: return null
                val versionText = part.substring(operator.symbol.length)
                val version = PackLibVersion.parse(versionText) ?: return null
                constraints += Constraint(operator, version)
            }
            return VersionRange(constraints.toList())
        }

        fun require(raw: String): VersionRange =
            parse(raw) ?: throw IllegalArgumentException("Invalid packLib range: '$raw'")
    }
}