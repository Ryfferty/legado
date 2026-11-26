package io.legado.pack.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRangeTest {

    @Test
    fun `versions require all three numeric components`() {
        assertEquals(PackLibVersion(1, 2, 3), PackLibVersion.parse("1.2.3"))
        assertNull(PackLibVersion.parse("1.2"))
        assertNull(PackLibVersion.parse("1.2.3.4"))
        assertNull(PackLibVersion.parse("01.2.3"))
        assertNull(PackLibVersion.parse("1.2.3-alpha"))
        assertTrue(PackLibVersion.parse("1.2.3")!! > PackLibVersion.parse("1.2.2")!!)
    }

    @Test
    fun `any range accepts every version`() {
        assertTrue(VersionRange.parse("*")!!.contains(PackLibVersion(9, 9, 9)))
    }

    @Test
    fun `conjunction range is enforced`() {
        val range = VersionRange.require(">=1.0.0 <2.0.0")

        assertTrue(range.contains(PackLibVersion(1, 0, 0)))
        assertTrue(range.contains(PackLibVersion(1, 9, 9)))
        assertFalse(range.contains(PackLibVersion(2, 0, 0)))
        assertFalse(range.contains(PackLibVersion(0, 9, 9)))
    }

    @Test
    fun `equality and strict comparators are supported`() {
        assertTrue(VersionRange.require("=1.2.3").contains(PackLibVersion(1, 2, 3)))
        assertFalse(VersionRange.require(">1.2.3").contains(PackLibVersion(1, 2, 3)))
        assertTrue(VersionRange.require("<=1.2.3").contains(PackLibVersion(1, 2, 3)))
    }

    @Test
    fun `unsupported range syntax is rejected`() {
        assertNull(VersionRange.parse(""))
        assertNull(VersionRange.parse("^1.0.0"))
        assertNull(VersionRange.parse("~1.0.0"))
        assertNull(VersionRange.parse("1.0.0"))
        assertNull(VersionRange.parse(">=1.0"))
        assertNull(VersionRange.parse(">=1.0.0 || <2.0.0"))
        assertNull(VersionRange.parse(">=1.0.0,"))
    }
}