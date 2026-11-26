package io.legado.app.constant

import io.legado.app.help.update.AppVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetaUpdateCompatibilityTest {

    private val betaSignature =
        "E2400519DF26F329EFC3FA1288DB46E8A23C6AEEB14B5378AD80CA9F8136C146"

    @Test
    fun betaSignatureReturnsBetaReleaseVariant() {
        // Single-keystore fork: any package with the beta signature → BETA_RELEASE.
        assertEquals(
            AppVariant.BETA_RELEASE,
            resolveBetaUpdateVariant("io.legado.ryz", listOf(betaSignature))
        )
    }

    @Test
    fun incompatibleSignaturesAndPackagesAreRejected() {
        assertNull(
            resolveBetaUpdateVariant("io.legado.ryz", listOf("different"))
        )
        assertNull(
            resolveBetaUpdateVariant("io.legado.ryz", listOf("differentSignatureHex"))
        )
        assertNull(resolveBetaUpdateVariant("io.legado.ryz", emptyList()))
        assertNull(
            resolveBetaUpdateVariant(
                "io.legado.ryz",
                listOf(betaSignature, betaSignature)
            )
        )
    }
}
