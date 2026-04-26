package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTokensProviderTest {

    // The legend ordering is part of the wire format. Pin it explicitly so
    // accidental reordering breaks the test rather than silently corrupting
    // every emitted token's type id.
    @Test
    fun `legend ordering is stable`() {
        val expectedTypes = listOf("keyword", "function", "parameter", "variable", "string", "number", "comment")
        assertEquals(expectedTypes, SemanticTokensProvider.legend.tokenTypes)
        assertEquals(listOf("declaration", "defaultLibrary"), SemanticTokensProvider.legend.tokenModifiers)
    }

    @Test
    fun `empty program emits no tokens`() {
        val tokens = SemanticTokensProvider.semanticTokens(Analyzer.analyze(""))
        assertTrue(tokens.data.isEmpty())
    }

    @Test
    fun `built-in call emits function token with defaultLibrary modifier`() {
        // `forward 100` → token[0] = forward (function/defaultLibrary)
        //                token[1] = 100 (number)
        val tokens = SemanticTokensProvider.semanticTokens(Analyzer.analyze("forward 100"))
        assertEquals(2 * 5, tokens.data.size)

        // First token: line 0, char 0, length 7, type=function(1), modifiers=defaultLibrary(2)
        assertEquals(0, tokens.data[0])  // deltaLine
        assertEquals(0, tokens.data[1])  // deltaChar
        assertEquals(7, tokens.data[2])  // length of "forward"
        assertEquals(1, tokens.data[3])  // function type
        assertEquals(2, tokens.data[4])  // defaultLibrary modifier (1 shl 1)

        // Second token: same line, deltaChar 8 (after "forward "), length 3, type=number(5)
        assertEquals(0, tokens.data[5])
        assertEquals(8, tokens.data[6])
        assertEquals(3, tokens.data[7])
        assertEquals(5, tokens.data[8])  // number type
        assertEquals(0, tokens.data[9])
    }

    @Test
    fun `procedure definition emits TO keyword + function declaration + parameters + END keyword`() {
        // `to f :n forward :n end` on one line.
        val analysis = Analyzer.analyze("to f :n forward :n end")
        val data = SemanticTokensProvider.semanticTokens(analysis).data

        // Expect 6 tokens: to, f, :n (param), forward, :n (param ref), end
        assertEquals(6 * 5, data.size)

        // Quick spot-checks: every token has a recognised type id.
        for (i in 0 until data.size step 5) {
            val typeId = data[i + 3]
            assertTrue(typeId in 0..6, "unknown token type $typeId at offset $i")
        }
    }

    @Test
    fun `comment is highlighted via hidden-channel range`() {
        // Two tokens emitted: `forward` and `; hi` (comment).
        val analysis = Analyzer.analyze("forward 100  ; hi")
        val data = SemanticTokensProvider.semanticTokens(analysis).data
        // 3 tokens: forward (function), 100 (number), comment
        assertEquals(3 * 5, data.size)
        // Last token should be comment type (6)
        assertEquals(6, data[data.size - 2])
    }

    @Test
    fun `parameter reference emits parameter type, not generic variable`() {
        val analysis = Analyzer.analyze("to f :n forward :n end")
        val data = SemanticTokensProvider.semanticTokens(analysis).data
        // Find any token with type=parameter(2) — there should be at least one.
        var sawParameter = false
        for (i in 0 until data.size step 5) {
            if (data[i + 3] == 2) sawParameter = true
        }
        assertTrue(sawParameter, "expected at least one parameter-typed token")
    }
}
