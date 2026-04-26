package com.bugdigger.logolsp.analysis.ast

import com.bugdigger.logolsp.grammar.LogoLexer
import com.bugdigger.logolsp.grammar.LogoParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AstBuilderTest {

    private fun parse(source: String): Program {
        val lexer = LogoLexer(CharStreams.fromString(source))
        val parser = LogoParser(CommonTokenStream(lexer))
        return AstBuilder.build(parser.program())
    }

    @Test
    fun `empty program yields empty statement list`() {
        val program = parse("")
        assertTrue(program.statements.isEmpty())
    }

    @Test
    fun `simple call with one numeric argument`() {
        val program = parse("forward 100")
        assertEquals(1, program.statements.size)
        val call = assertIs<Call>(program.statements[0])
        assertEquals("forward", call.target.text)
        assertEquals(1, call.arguments.size)
        val number = assertIs<NumberLit>(call.arguments[0])
        assertEquals(100.0, number.value)
    }

    @Test
    fun `case-insensitive identifiers are normalised to lower-case`() {
        val program = parse("FoRwArD 50")
        val call = assertIs<Call>(program.statements[0])
        assertEquals("forward", call.target.text)
    }

    @Test
    fun `procedure definition with parameter and nested body`() {
        val program = parse(
            """
            to square :size
              repeat 4 [forward :size right 90]
            end
            """.trimIndent(),
        )
        val procDef = assertIs<ProcedureDef>(program.statements[0])
        assertEquals("square", procDef.name.text)
        assertEquals(listOf("size"), procDef.parameters.map { it.name.text })

        val repeat = assertIs<RepeatStmt>(procDef.body[0])
        val repeatBody = repeat.body.elements
        assertEquals(2, repeatBody.size)

        val forwardCall = assertIs<Call>(repeatBody[0])
        assertEquals("forward", forwardCall.target.text)
        val varArg = assertIs<VarRef>(forwardCall.arguments[0])
        assertEquals("size", varArg.name.text)

        val rightCall = assertIs<Call>(repeatBody[1])
        assertEquals("right", rightCall.target.text)
    }

    @Test
    fun `make statement with quoted-word target and numeric value`() {
        val program = parse("""make "x 5""")
        val make = assertIs<MakeStmt>(program.statements[0])
        val target = assertIs<QuotedWord>(make.target)
        assertEquals("x", target.name.text)
        val value = assertIs<NumberLit>(make.value)
        assertEquals(5.0, value.value)
    }

    @Test
    fun `local statement with multiple names`() {
        val program = parse("""local "a "b "c""")
        val local = assertIs<LocalStmt>(program.statements[0])
        assertEquals(listOf("a", "b", "c"), local.names.map { it.name.text })
    }

    @Test
    fun `ifelse parses both branches`() {
        val program = parse("""ifelse :x [print "yes] [print "no]""")
        val ifElse = assertIs<IfElseStmt>(program.statements[0])
        assertIs<VarRef>(ifElse.condition)
        assertEquals(1, ifElse.thenBody.elements.size)
        assertEquals(1, ifElse.elseBody.elements.size)
        val thenCall = assertIs<Call>(ifElse.thenBody.elements[0])
        assertEquals("print", thenCall.target.text)
    }

    @Test
    fun `output and stop statements`() {
        val program = parse(
            """
            output 42
            stop
            """.trimIndent(),
        )
        val out = assertIs<OutputStmt>(program.statements[0])
        assertEquals(42.0, assertIs<NumberLit>(out.value).value)
        assertIs<StopStmt>(program.statements[1])
    }

    @Test
    fun `list literal in data position holds atoms`() {
        val program = parse("""make "lst [1 2 3]""")
        val make = assertIs<MakeStmt>(program.statements[0])
        val list = assertIs<ListLiteral>(make.value)
        val numbers = list.elements.map { assertIs<NumberLit>(it).value }
        assertEquals(listOf(1.0, 2.0, 3.0), numbers)
    }

    @Test
    fun `parenthesised call as argument becomes ParenCall`() {
        val program = parse("forward (sum 1 2)")
        val call = assertIs<Call>(program.statements[0])
        val paren = assertIs<ParenCall>(call.arguments[0])
        assertEquals("sum", paren.call.target.text)
        assertEquals(2, paren.call.arguments.size)
    }

    @Test
    fun `comparison expression with infix equals`() {
        val program = parse("""if :x = 5 [stop]""")
        val ifStmt = assertIs<IfStmt>(program.statements[0])
        val cond = assertIs<BinaryOp>(ifStmt.condition)
        assertEquals(BinaryOperator.EQ, cond.op)
        assertIs<VarRef>(cond.left)
        assertIs<NumberLit>(cond.right)
    }

    @Test
    fun `comments on hidden channel do not affect parsing`() {
        val program = parse(
            """
            ; draw a square
            forward 100  ; first side
            """.trimIndent(),
        )
        assertEquals(1, program.statements.size)
        val call = assertIs<Call>(program.statements[0])
        assertEquals("forward", call.target.text)
    }

    @Test
    fun `node ranges reflect source line and column positions`() {
        // Layout (LSP 0-indexed):
        //   line 0: "forward 100"
        //   line 1: "right 90"
        val program = parse("forward 100\nright 90")
        val first = assertIs<Call>(program.statements[0])
        val second = assertIs<Call>(program.statements[1])

        // "forward" starts at line 0, char 0; spans 7 chars
        assertEquals(Range(Position(0, 0), Position(0, 7)), first.target.range)
        // first call's full range covers "forward 100"
        assertEquals(Range(Position(0, 0), Position(0, 11)), first.range)
        // "right" starts at line 1, char 0
        assertEquals(Range(Position(1, 0), Position(1, 5)), second.target.range)
    }
}
