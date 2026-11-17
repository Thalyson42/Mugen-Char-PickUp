package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocatorTests {

    private class Fs(val files: Map<String, String>) : ReadOnlyFs {
        override fun exists(path: String) = files.keys.any { it.equals(path, true) }
        override fun readText(path: String) = files.entries.firstOrNull { it.key.equals(path, true) }?.value
        override fun listDirs(path: String): List<String> {
            val prefix = path.trimEnd('/') + "/"
            return files.keys.filter { it.startsWith(prefix, true) }
                .map { it.substring(prefix.length) }.filter { it.contains('/') }
                .map { it.substringBefore('/') }.distinct()
        }
    }

    private val sys = "[Files]\nselect = select.def\n[Select Info]\nrows = 6\ncolumns = 9\n"

    @Test
    fun findsIkemen1SystemDefWithoutAnyConfig() {
        val loc = Locator.locate(Fs(mapOf("data/ikemen1/system.def" to sys, "data/ikemen1/select.def" to "[Characters]")))!!
        assertEquals("data/ikemen1/system.def", loc.systemDefPath)
        assertEquals("data/ikemen1/select.def", loc.selectPath)
        assertEquals(6, loc.rows)
        assertEquals(9, loc.columns)
    }

    @Test
    fun keepsDataSelectDefWhenMotifFolderHasNone() {
        val loc = Locator.locate(Fs(mapOf("data/ikemen1/system.def" to sys, "data/select.def" to "[Characters]")))!!
        assertEquals("data/ikemen1/system.def", loc.systemDefPath)
        assertEquals("data/select.def", loc.selectPath)
    }

    @Test
    fun configIniMotifWinsOverTheDefaults() {
        val fs = Fs(
            mapOf(
                "save/config.ini" to "[Config]\nMotif = data/mypack/system.def ; screenpack\n",
                "data/mypack/system.def" to sys,
                "data/ikemen1/system.def" to sys,
                "data/mypack/select.def" to "[Characters]",
            ),
        )
        assertEquals("data/mypack/system.def", Locator.locate(fs)!!.systemDefPath)
    }

    @Test
    fun mugenCfgMotifIsUsed() {
        val fs = Fs(mapOf("data/mugen.cfg" to "[Options]\nmotif = data/custom.def\n", "data/custom.def" to sys, "data/select.def" to "[Characters]"))
        assertEquals("data/custom.def", Locator.locate(fs)!!.systemDefPath)
    }

    @Test
    fun scansUnknownScreenpackFolders() {
        val fs = Fs(mapOf("data/zpack/system.def" to sys, "data/apack/readme.txt" to "x", "data/select.def" to "[Characters]"))
        assertEquals("data/zpack/system.def", Locator.locate(fs)!!.systemDefPath)
    }

    @Test
    fun noSystemDefStillFindsSelectDef() {
        val loc = Locator.locate(Fs(mapOf("data/select.def" to "[Characters]")))!!
        assertNull(loc.systemDefPath)
        assertEquals("data/select.def", loc.selectPath)
    }

    @Test
    fun manualSystemAndSelectOverridesWinOverDetection() {
        val fs = Fs(
            mapOf(
                "data/ikemen1/system.def" to sys,
                "data/ikemen1/select.def" to "[Characters]",
                "data/outro/meu.def" to "[Files]\nselect = x.def\n[Select Info]\nrows = 3\ncolumns = 4\n",
                "data/minha/lista.def" to "[Characters]",
            ),
        )
        val loc = Locator.locate(fs, selectOverride = "Data/Minha/Lista.def", systemOverride = "data/outro/meu.def")!!
        assertEquals("data/outro/meu.def", loc.systemDefPath)
        assertEquals("Data/Minha/Lista.def", loc.selectPath)
        assertEquals(3, loc.rows)
        assertEquals(4, loc.columns)
        assertEquals(true, loc.systemManual)
        assertEquals(true, loc.selectManual)
        assertEquals(emptyList(), loc.warnings)
    }

    @Test
    fun onlyTheSelectOverrideKeepsAutomaticSystemDef() {
        val fs = Fs(mapOf("data/ikemen1/system.def" to sys, "data/ikemen1/select.def" to "[Characters]", "data/b.def" to "[Characters]"))
        val loc = Locator.locate(fs, selectOverride = "data/b.def")!!
        assertEquals("data/b.def", loc.selectPath)
        assertEquals("data/ikemen1/system.def", loc.systemDefPath)
        assertEquals(false, loc.systemManual)
    }

    @Test
    fun missingOverridesFallBackWithWarnings() {
        val fs = Fs(mapOf("data/ikemen1/system.def" to sys, "data/ikemen1/select.def" to "[Characters]"))
        val loc = Locator.locate(fs, selectOverride = "data/sumiu.def", systemOverride = "data/sumiu2.def")!!
        assertEquals("data/ikemen1/system.def", loc.systemDefPath)
        assertEquals("data/ikemen1/select.def", loc.selectPath)
        assertEquals(false, loc.selectManual)
        assertEquals(2, loc.warnings.size)
    }

    @Test
    fun parsesMotifFromIni() {
        assertEquals("data/ikemen1/system.def", motifFromIni("[Config]\nmotif=\"data/ikemen1/system.def\"\n"))
        assertNull(motifFromIni("[Config]\n;Motif = x\n"))
    }
}
