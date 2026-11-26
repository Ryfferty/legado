package io.legado.pack.diagnostics

import java.util.Locale

/**
 * Keeps credentials and request privacy headers out of ring buffers, log files
 * and diagnostic details.
 *
 * This is a last line of defence: callers must still avoid logging request
 * bodies, cookies and full URLs.
 */
internal object Redaction {

    private val SENSITIVE_TOKENS = setOf(
        "authorization",
        "proxyauthorization",
        "cookie",
        "setcookie",
        "password",
        "passwd",
        "pwd",
        "token",
        "accesstoken",
        "refreshtoken",
        "secret",
        "apikey",
        "clientsecret",
        "credential",
        "credentials",
        "sessionid",
        "sessionkey",
        "privatekey",
        "keystorepassword",
    )

    private val AUTHORIZATION_HEADER =
        Regex("(?i)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+")
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val BASIC = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]+")
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?token|" +
            "refresh[_-]?token|client[_-]?secret)\\b(\\s*[:=]\\s*)([^\\s,;&]+)",
    )

    const val MASK: String = "[redacted]"

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        if (normalized in SENSITIVE_TOKENS) return true
        return normalized.contains("password") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("apikey") ||
            normalized.contains("credential")
    }

    fun redactDetails(details: Map<String, String>): Map<String, String> {
        if (details.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(details.size)
        details.forEach { (key, value) ->
            result[key] = if (isSensitiveKey(key)) MASK else redactText(value)
        }
        return result
    }

    fun redactText(text: String): String {
        var result = text
        result = AUTHORIZATION_HEADER.replace(result) { match ->
            "${match.groupValues[1]}: $MASK"
        }
        result = BEARER.replace(result, "Bearer $MASK")
        result = BASIC.replace(result, "Basic $MASK")
        result = SECRET_ASSIGNMENT.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$MASK"
        }
        return result
    }
}