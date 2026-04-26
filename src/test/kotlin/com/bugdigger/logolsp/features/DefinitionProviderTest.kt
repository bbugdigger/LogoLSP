package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analyzer
import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionProviderTest {

    private val uri = "file:///test.logo"

    @Test
    fun `built-in call returns no location`() {
        val analysis = Analyzer.analyze("forward 100")
        val locs = DefinitionProvider.definition(analysis, uri, Position(0, 3))
        assertTrue(locs.isEmpty())
    }

    @Test
    fun `user procedure call jumps to its definition range`() {
        // line 0: `to square`
        // line 1: `  forward 50`
        // line 2: `end`
        // line 3: `square`
        val analysis = Analyzer.analyze("to square\n  forward 50\nend\nsquare")
        // Click on "square" at line 3 (the call)
        val locs = DefinitionProvider.definition(analysis, uri, Position(3, 2))
        assertEquals(1, locs.size)
        val def = locs[0]
        assertEquals(uri, def.uri)
        // Definition range is the entire `to square ... end` (lines 0-2)
        assertEquals(0, def.range.start.line)
        assertEquals(2, def.range.end.line)
    }

    @Test
    fun `variable read jumps to first MAKE that introduced it`() {
        // line 0: `make "x 5`
        // line 1: `print :x`
        val analysis = Analyzer.analyze("make \"x 5\nprint :x")
        // Click on :x at line 1, chars 6-8 — anywhere inside the VarRef
        val locs = DefinitionProvider.definition(analysis, uri, Position(1, 7))
        assertEquals(1, locs.size)
        // Definition is the make statement on line 0
        assertEquals(0, locs[0].range.start.line)
    }

    @Test
    fun `parameter read jumps to its parameter declaration`() {
        // line 0: `to f :n`
        // line 1: `  forward :n`
        // line 2: `end`
        val analysis = Analyzer.analyze("to f :n\n  forward :n\nend")
        // Click on :n at line 1
        val locs = DefinitionProvider.definition(analysis, uri, Position(1, 11))
        assertEquals(1, locs.size)
        // Parameter declaration is on line 0
        assertEquals(0, locs[0].range.start.line)
    }

    @Test
    fun `click on a number returns no location`() {
        val analysis = Analyzer.analyze("forward 100")
        // 100 is at chars 8-11
        val locs = DefinitionProvider.definition(analysis, uri, Position(0, 9))
        assertTrue(locs.isEmpty())
    }

    @Test
    fun `click outside any node returns no location`() {
        val analysis = Analyzer.analyze("forward 100")
        // Way past end of file
        val locs = DefinitionProvider.definition(analysis, uri, Position(99, 99))
        assertTrue(locs.isEmpty())
    }
}
