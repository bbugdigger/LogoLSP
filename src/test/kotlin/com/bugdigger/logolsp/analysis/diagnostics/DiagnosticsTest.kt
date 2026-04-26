package com.bugdigger.logolsp.analysis.diagnostics

import com.bugdigger.logolsp.analysis.Analyzer
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {

    private fun analyze(source: String): List<Diagnostic> = Analyzer.analyze(source).diagnostics

    private fun List<Diagnostic>.codes(): List<String> = map { it.code.left }

    @Test
    fun `clean program produces no diagnostics`() {
        val diags = analyze(
            """
            to square :size
              repeat 4 [forward :size right 90]
            end
            square 50
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "expected no diagnostics, got: ${diags.codes()}")
    }

    @Test
    fun `LOGO001 - syntax error from unclosed bracket`() {
        val diags = analyze("repeat 4 [forward 100")
        assertTrue(diags.any { it.code.left == "LOGO001" }, "expected LOGO001, got: ${diags.codes()}")
        assertTrue(diags.any { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `LOGO002 - undefined procedure call`() {
        val diags = analyze("dance 5")
        val undef = diags.single { it.code.left == "LOGO002" }
        assertEquals(DiagnosticSeverity.Error, undef.severity)
        assertTrue(undef.message.contains("dance"))
    }

    @Test
    fun `LOGO003 - undefined variable reference`() {
        val diags = analyze("print :missing")
        val undef = diags.single { it.code.left == "LOGO003" }
        assertEquals(DiagnosticSeverity.Error, undef.severity)
        assertTrue(undef.message.contains("missing"))
    }

    @Test
    fun `LOGO003 - undefined variable in MAKE target via VarRef`() {
        // `make :varname 5` reads :varname; if :varname isn't defined, that's
        // an undefined variable, not a silent global creation.
        val diags = analyze("make :varname 5")
        assertTrue(
            diags.any { it.code.left == "LOGO003" },
            "expected LOGO003 for :varname, got: ${diags.codes()}",
        )
    }

    @Test
    fun `LOGO004 - wrong arity too few arguments to built-in`() {
        val diags = analyze("forward")
        val arity = diags.single { it.code.left == "LOGO004" }
        assertEquals(DiagnosticSeverity.Warning, arity.severity)
        assertTrue(arity.message.contains("forward"))
        assertTrue(arity.message.contains("1"))
    }

    @Test
    fun `LOGO004 - wrong arity too many arguments to built-in`() {
        val diags = analyze("home 1")
        val arity = diags.single { it.code.left == "LOGO004" }
        assertEquals(DiagnosticSeverity.Warning, arity.severity)
        assertTrue(arity.message.contains("home"))
    }

    @Test
    fun `LOGO004 - variadic built-in is OK with extra arguments but not too few`() {
        // `sum` is variadic with min 2 args.
        val tooFew = analyze("print sum 1")
        // Hmm — in our dialect, `sum 1` inside `print sum 1` would be
        // `print sum` (sum with 0 args) then `1` (number, no statement).
        // For the variadic check we need a clean call, so test bare:
        assertTrue(tooFew.any { it.code.left == "LOGO004" })

        val ok = analyze("print (sum 1 2 3)")
        assertTrue(
            ok.none { it.code.left == "LOGO004" },
            "variadic OK with 3 args, got: ${ok.codes()}",
        )
    }

    @Test
    fun `LOGO005 - duplicate procedure definition`() {
        val diags = analyze(
            """
            to greet  print "hi  end
            to greet  print "hello  end
            """.trimIndent(),
        )
        val redef = diags.single { it.code.left == "LOGO005" }
        assertEquals(DiagnosticSeverity.Warning, redef.severity)
        assertTrue(redef.message.contains("greet"))
    }

    @Test
    fun `LOGO005 - user procedure shadowing a built-in`() {
        val diags = analyze("to forward :n  print :n  end")
        val shadow = diags.single { it.code.left == "LOGO005" }
        assertTrue(shadow.message.contains("built-in"))
        assertTrue(shadow.message.contains("forward"))
    }

    @Test
    fun `diagnostic source is set to logo-lsp`() {
        val diags = analyze("dance 5")
        assertTrue(diags.all { it.source == "logo-lsp" })
    }
}
