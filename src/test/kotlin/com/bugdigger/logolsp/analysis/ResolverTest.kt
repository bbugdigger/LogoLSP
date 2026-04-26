package com.bugdigger.logolsp.analysis

import com.bugdigger.logolsp.analysis.ast.AstBuilder
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.MakeStmt
import com.bugdigger.logolsp.analysis.ast.ProcedureDef
import com.bugdigger.logolsp.analysis.ast.Program
import com.bugdigger.logolsp.analysis.ast.QuotedWord
import com.bugdigger.logolsp.analysis.ast.RepeatStmt
import com.bugdigger.logolsp.analysis.ast.VarRef
import com.bugdigger.logolsp.analysis.symbols.BuiltinSymbol
import com.bugdigger.logolsp.analysis.symbols.ProcedureSymbol
import com.bugdigger.logolsp.analysis.symbols.VariableScope
import com.bugdigger.logolsp.analysis.symbols.VariableSymbol
import com.bugdigger.logolsp.grammar.LogoLexer
import com.bugdigger.logolsp.grammar.LogoParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolverTest {

    private fun analyze(source: String): Pair<Program, ResolutionResult> {
        val lexer = LogoLexer(CharStreams.fromString(source))
        val parser = LogoParser(CommonTokenStream(lexer))
        val program = AstBuilder.build(parser.program())
        return program to Resolver().resolve(program)
    }

    @Test
    fun `user procedure is registered with parameter count`() {
        val (_, result) = analyze("to greet :name :loud  print :name  end")
        val proc = result.symbolTable.procedure("greet")
        assertNotNull(proc)
        assertEquals(2, proc.parameterCount)
    }

    @Test
    fun `forward call resolves to procedure declared later`() {
        val (program, result) = analyze(
            """
            square 100
            to square :size
              repeat 4 [forward :size right 90]
            end
            """.trimIndent(),
        )
        val firstStmt = assertIs<Call>(program.statements[0])
        val resolved = assertIs<ProcedureSymbol>(result.resolution[firstStmt])
        assertEquals("square", resolved.name)
    }

    @Test
    fun `built-in calls resolve to BuiltinSymbol`() {
        val (program, result) = analyze("forward 100")
        val call = assertIs<Call>(program.statements[0])
        val resolved = assertIs<BuiltinSymbol>(result.resolution[call])
        assertEquals("forward", resolved.name)
        assertEquals(1, resolved.parameterCount)
    }

    @Test
    fun `parameter is visible inside its procedure body`() {
        val (_, result) = analyze(
            """
            to f :x
              forward :x
            end
            """.trimIndent(),
        )
        val procScope = assertNotNull(result.symbolTable.procedureScope("f"))
        val xSym = assertNotNull(procScope.lookupLocal("x"))
        assertEquals(VariableScope.PARAMETER, xSym.scope)
        // 2 entries: parameter declaration + the one VarRef use
        assertEquals(2, xSym.references.size)
    }

    @Test
    fun `LOCAL hoisting makes a variable visible across the whole procedure`() {
        val (_, result) = analyze(
            """
            to f
              repeat 3 [local "i make "i 5 print :i]
            end
            """.trimIndent(),
        )
        val procScope = assertNotNull(result.symbolTable.procedureScope("f"))
        val iSym = assertNotNull(procScope.lookupLocal("i"))
        assertEquals(VariableScope.LOCAL, iSym.scope)
    }

    @Test
    fun `LOCAL shadows a same-named global`() {
        val (_, result) = analyze(
            """
            make "x 1
            to f
              local "x
              make "x 2
              print :x
            end
            """.trimIndent(),
        )
        val global = assertNotNull(result.symbolTable.globalScope.lookupLocal("x"))
        assertEquals(VariableScope.GLOBAL, global.scope)

        val procScope = assertNotNull(result.symbolTable.procedureScope("f"))
        val localX = assertNotNull(procScope.lookupLocal("x"))
        assertEquals(VariableScope.LOCAL, localX.scope)

        // The :x and make "x inside the procedure should bind to the LOCAL,
        // not the global. The local has 3 references (declaration + make + :x),
        // and the global has only 1 reference (its own declaration).
        assertEquals(3, localX.references.size)
        assertEquals(1, global.references.size)
    }

    @Test
    fun `top-level MAKE introduces a global on first occurrence`() {
        val (_, result) = analyze(
            """
            make "score 0
            make "score 1
            print :score
            """.trimIndent(),
        )
        val score = assertNotNull(result.symbolTable.globalScope.lookupLocal("score"))
        // 1 definition + 1 reassignment + 1 read = 3 references
        assertEquals(3, score.references.size)
    }

    @Test
    fun `MAKE inside a procedure can introduce a new global`() {
        val (_, result) = analyze(
            """
            to setup
              make "ready 1
            end
            print :ready
            """.trimIndent(),
        )
        val ready = assertNotNull(result.symbolTable.globalScope.lookupLocal("ready"))
        assertEquals(VariableScope.GLOBAL, ready.scope)
        // Definition (MAKE inside setup) + use at top level = 2 references
        assertEquals(2, ready.references.size)
    }

    @Test
    fun `unresolved variable reference produces no resolution entry`() {
        val (program, result) = analyze("print :missing")
        val print = assertIs<Call>(program.statements[0])
        val varRef = assertIs<VarRef>(print.arguments[0])
        assertNull(result.resolution[varRef])
        assertNull(result.symbolTable.globalScope.lookupLocal("missing"))
    }

    @Test
    fun `unresolved call produces no resolution entry`() {
        val (program, result) = analyze("nonexistent 1")
        val call = assertIs<Call>(program.statements[0])
        assertNull(result.resolution[call])
    }

    @Test
    fun `procedure definition site is included in references for rename`() {
        val (program, result) = analyze(
            """
            to square
              forward 50
            end
            square
            """.trimIndent(),
        )
        val procDef = assertIs<ProcedureDef>(program.statements[0])
        val proc = assertNotNull(result.symbolTable.procedure("square"))
        // Definition's name range + one call site = 2 references
        assertEquals(2, proc.references.size)
        assertTrue(procDef.name.range in proc.references)
    }

    @Test
    fun `references inside nested REPEAT are collected`() {
        val (_, result) = analyze(
            """
            to draw :n
              repeat :n [forward :n]
            end
            """.trimIndent(),
        )
        val procScope = assertNotNull(result.symbolTable.procedureScope("draw"))
        val n = assertNotNull(procScope.lookupLocal("n"))
        // Parameter declaration + repeat count + forward arg = 3 references
        assertEquals(3, n.references.size)
    }

    @Test
    fun `MAKE target QuotedWord resolves to its variable symbol`() {
        val (program, result) = analyze("""make "x 5""")
        val make = assertIs<MakeStmt>(program.statements[0])
        val target = assertIs<QuotedWord>(make.target)
        val resolved = assertIs<VariableSymbol>(result.resolution[target])
        assertEquals("x", resolved.name)
        assertEquals(VariableScope.GLOBAL, resolved.scope)
    }
}
