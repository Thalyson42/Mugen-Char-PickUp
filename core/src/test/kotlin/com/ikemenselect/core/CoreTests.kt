package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTests {

    private val sample = listOf(
        "; header comment",
        "[Characters]",
        "; first",
        "kfm, stages/a.def",
        "randomselect",
        "empty",
        "ryu/ryu.def",
        "slot = {",
        "  a, previous=d",
        "  b",
        "}",
        "hidden_boss, exclude=1",
        "",
        "[ExtraStages]",
        "stages/a.def",
        "",
    ).joinToString("\n")

    @Test
    fun roundTripIsIdentical() {
        assertEquals(sample, SelectDef.parse(sample).write())
    }

    @Test
    fun roundTripKeepsCrLf() {
        val crlf = sample.replace("\n", "\r\n")
        assertEquals(crlf, SelectDef.parse(crlf).write())
    }

    @Test
    fun parsesCellsAndKeys() {
        val def = SelectDef.parse(sample)
        assertEquals(5, def.cells.size)
        assertEquals(CellKind.CHAR, def.cells[0]!!.kind)
        assertEquals(CellKind.RANDOM, def.cells[1]!!.kind)
        assertEquals(CellKind.EMPTY, def.cells[2]!!.kind)
        assertEquals("ryu", def.cells[3]!!.key)
        assertEquals(CellKind.SLOT, def.cells[4]!!.kind)
        assertEquals(setOf("kfm", "ryu", "a", "b", "hidden_boss"), def.knownKeys())
        assertEquals(1, def.excluded.size)
    }

    @Test
    fun swapMovesAndKeepsLeadingComment() {
        val def = SelectDef.parse(sample)
        val moved = GridOps.swap(def.cells, 0, 3)
        val out = def.withCells(moved).write().lines()
        val iRyu = out.indexOf("ryu/ryu.def")
        val iKfm = out.indexOf("kfm, stages/a.def")
        assertTrue(iRyu < iKfm)
        assertEquals("; first", out[iKfm - 1]) // comentário viaja com o kfm
    }

    @Test
    fun removeLeavesEmptyInPlace() {
        val def = SelectDef.parse(sample)
        val out = def.withCells(GridOps.remove(def.cells, 3)).write()
        assertTrue(!out.contains("ryu/ryu.def"))
        val lines = out.lines()
        assertEquals("empty", lines[lines.indexOf("randomselect") + 2])
    }

    @Test
    fun addMissingFillsBlanksThenAppends() {
        val def = SelectDef.parse(sample)
        val res = GridOps.addMissing(
            def.cells,
            listOf(Candidate("terry", "terry.def"), Candidate("KFM", "kfm.def"), Candidate("zed", "zed_alt.def")),
            def.knownKeys(),
        )
        assertEquals(listOf("terry", "zed"), res.added.map { it.folder })
        assertEquals("terry", res.cells[2]!!.key)
        assertEquals("zed", res.cells[5]!!.key)
        val out = def.withCells(res.cells).write()
        assertTrue(out.contains("zed/zed_alt.def"))
        assertTrue(out.contains("\nterry\n"))
    }

    @Test
    fun trailingBlanksAreNotWritten() {
        val def = SelectDef.parse(sample)
        val padded = GridOps.normalize(def.cells, rows = 10, columns = 8)
        assertEquals(80, padded.size)
        assertEquals(sample, def.withCells(padded).write())
    }

    @Test
    fun pickDefPrefersFolderName() {
        assertEquals("kfm.def", GridOps.pickDef("kfm", listOf("a.def", "kfm.def", "kfm.sff")))
        assertEquals("a.def", GridOps.pickDef("kfm", listOf("b.def", "a.def")))
        assertNull(GridOps.pickDef("kfm", listOf("kfm.sff")))
    }

    @Test
    fun entryFromRelativePath() {
        // mantém a capitalização real da pasta (o sistema de arquivos do Android diferencia maiúsculas)
        assertEquals("Ryu", GridOps.entryFromRelativePath("Ryu/ryu.def"))
        assertEquals("ryu/ryu_alt.def", GridOps.entryFromRelativePath("ryu/ryu_alt.def"))
        assertNull(GridOps.entryFromRelativePath("ryu/ryu.sff"))
    }

    @Test
    fun parsesSystemDefAndConfig() {
        val sys = """
            [Files]
            select = select.def ; comment
            [Select Info]
            rows = 10
            columns = 8
        """.trimIndent()
        assertEquals(MotifInfo(10, 8, "select.def"), parseSystemDef(sys))
        assertEquals("data/ikemen1/system.def", motifFromConfig("""{ "Motif": "data/ikemen1/system.def" }"""))
    }

    private class FakeFs(val files: Map<String, String>) : ReadOnlyFs {
        override fun exists(path: String) = files.keys.any { it.equals(path, true) }
        override fun readText(path: String) = files.entries.firstOrNull { it.key.equals(path, true) }?.value
    }

    @Test
    fun locatorPrefersMotifCopy() {
        val fs = FakeFs(
            mapOf(
                "save/config.json" to """{"Motif":"data/ikemen1/system.def"}""",
                "data/ikemen1/system.def" to "[Files]\nselect = select.def\n[Select Info]\nrows=3\ncolumns=7",
                "data/ikemen1/select.def" to "[Characters]",
                "data/select.def" to "[Characters]",
            ),
        )
        val loc = Locator.locate(fs)!!
        assertEquals("data/ikemen1/select.def", loc.selectPath)
        assertEquals(3, loc.rows)
        assertEquals(7, loc.columns)
    }

    @Test
    fun locatorFallsBackToDataSelect() {
        val loc = Locator.locate(FakeFs(mapOf("data/select.def" to "[Characters]")))!!
        assertEquals("data/select.def", loc.selectPath)
        assertNull(Locator.locate(FakeFs(emptyMap())))
    }
}
