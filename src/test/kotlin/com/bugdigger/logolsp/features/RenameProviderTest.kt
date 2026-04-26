package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analyzer
import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RenameProviderTest {

    private val uri = "file:///test.logo"

    // -------- prepareRename --------

    @Test
    fun `prepareRename on built-in returns null`() {
        val analysis = Analyzer.analyze("forward 100")
        val result = RenameProvider.prepareRename(analysis, Position(0, 3))
        assertNull(result)
    }

    @Test
    fun `prepareRename on user procedure call returns identifier range and current name`() {
        // line 0: `to square forward 50 end`
        // line 1: `square`
        val analysis = Analyzer.analyze("to square forward 50 end\nsquare")
        val result = RenameProvider.prepareRename(analysis, Position(1, 2))
        assertNotNull(result)
        assertEquals("square", result.placeholder)
        assertEquals(1, result.range.start.line)
    }

    @Test
    fun `prepareRename on variable returns identifier range`() {
        val analysis = Analyzer.analyze("make \"x 5\nprint :x")
        val result = RenameProvider.prepareRename(analysis, Position(1, 7))
        assertNotNull(result)
        assertEquals("x", result.placeholder)
    }

    @Test
    fun `prepareRename off-symbol returns null`() {
        val analysis = Analyzer.analyze("forward 100")
        val result = RenameProvider.prepareRename(analysis, Position(0, 9))  // on the number
        assertNull(result)
    }

    // -------- rename --------

    @Test
    fun `rename produces edits for every reference of a procedure`() {
        // Definition + 2 call sites = 3 references, hence 3 TextEdits
        val analysis = Analyzer.analyze(
            """
            to square
              forward 50
            end
            square
            square
            """.trimIndent(),
        )
        val outcome = RenameProvider.rename(analysis, uri, Position(3, 2), "rect")
        val edit = assertIs<RenameOutcome.Edit>(outcome)
        val edits = edit.workspaceEdit.changes[uri]!!
        assertEquals(3, edits.size)
        for (e in edits) assertEquals("rect", e.newText)
    }

    @Test
    fun `rename of scoped variable touches only in-scope references`() {
        // global :x has 1 ref (its declaration). procedure-local :x has 3 refs
        // (LOCAL declaration + MAKE + read). Renaming the local should touch
        // exactly the procedure's 3 occurrences and leave the global alone.
        val source = """
            make "x 1
            to f
              local "x
              make "x 2
              print :x
            end
        """.trimIndent()
        val analysis = Analyzer.analyze(source)

        // Position on `:x` inside the procedure (line 4, col 8)
        val outcome = RenameProvider.rename(analysis, uri, Position(4, 9), "y")
        val edit = assertIs<RenameOutcome.Edit>(outcome)
        val edits = edit.workspaceEdit.changes[uri]!!
        assertEquals(3, edits.size)
        // None of the edits target line 0 (the global declaration)
        for (e in edits) {
            assert(e.range.start.line != 0) { "edit unexpectedly touches global :x at line 0" }
        }
    }

    @Test
    fun `rename to invalid identifier is rejected`() {
        val analysis = Analyzer.analyze("to f forward 1 end\nf")
        val outcome = RenameProvider.rename(analysis, uri, Position(1, 0), "bad name!")
        val invalid = assertIs<RenameOutcome.Invalid>(outcome)
        assert(invalid.message.contains("identifier"))
    }

    @Test
    fun `rename to a built-in name is rejected`() {
        val analysis = Analyzer.analyze("to f forward 1 end\nf")
        val outcome = RenameProvider.rename(analysis, uri, Position(1, 0), "forward")
        val invalid = assertIs<RenameOutcome.Invalid>(outcome)
        assert(invalid.message.contains("built-in"))
    }

    @Test
    fun `rename procedure to existing procedure name is rejected`() {
        val analysis = Analyzer.analyze("to f forward 1 end\nto g back 1 end\nf")
        // Click on the call to `f`, try to rename it to `g`
        val outcome = RenameProvider.rename(analysis, uri, Position(2, 0), "g")
        val invalid = assertIs<RenameOutcome.Invalid>(outcome)
        assert(invalid.message.contains("already defined"))
    }
}
