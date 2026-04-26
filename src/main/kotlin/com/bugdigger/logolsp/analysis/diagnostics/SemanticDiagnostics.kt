package com.bugdigger.logolsp.analysis.diagnostics

import com.bugdigger.logolsp.analysis.ResolutionResult
import com.bugdigger.logolsp.analysis.ast.Atom
import com.bugdigger.logolsp.analysis.ast.BinaryOp
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.Expression
import com.bugdigger.logolsp.analysis.ast.IfElseStmt
import com.bugdigger.logolsp.analysis.ast.IfStmt
import com.bugdigger.logolsp.analysis.ast.ListLiteral
import com.bugdigger.logolsp.analysis.ast.LocalStmt
import com.bugdigger.logolsp.analysis.ast.MakeStmt
import com.bugdigger.logolsp.analysis.ast.NumberLit
import com.bugdigger.logolsp.analysis.ast.OutputStmt
import com.bugdigger.logolsp.analysis.ast.ParenCall
import com.bugdigger.logolsp.analysis.ast.ParenExpr
import com.bugdigger.logolsp.analysis.ast.ProcedureDef
import com.bugdigger.logolsp.analysis.ast.Program
import com.bugdigger.logolsp.analysis.ast.QuotedWord
import com.bugdigger.logolsp.analysis.ast.RepeatStmt
import com.bugdigger.logolsp.analysis.ast.Statement
import com.bugdigger.logolsp.analysis.ast.StopStmt
import com.bugdigger.logolsp.analysis.ast.UnaryMinus
import com.bugdigger.logolsp.analysis.ast.VarRef
import com.bugdigger.logolsp.analysis.builtins.Builtins
import com.bugdigger.logolsp.analysis.symbols.BuiltinSymbol
import com.bugdigger.logolsp.analysis.symbols.ProcedureSymbol
import org.eclipse.lsp4j.Diagnostic

// Computes semantic diagnostics over an already-resolved AST. Run AFTER the
// Resolver, so that resolution[node] is populated; this object is purely a
// reader.
object SemanticDiagnostics {

    fun compute(program: Program, result: ResolutionResult): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        detectProcedureRedefinitions(program, out)
        for (stmt in program.statements) checkStatement(stmt, result, out)
        return out
    }

    private fun detectProcedureRedefinitions(program: Program, out: MutableList<Diagnostic>) {
        val seen = mutableSetOf<String>()
        for (stmt in program.statements) {
            if (stmt !is ProcedureDef) continue
            val name = stmt.name.text
            when {
                name in seen -> out += diagnostic(
                    DiagnosticRule.REDEFINED_PROCEDURE,
                    stmt.name.range,
                    "Procedure '$name' is already defined in this file",
                )
                Builtins.isBuiltin(name) -> {
                    seen += name
                    out += diagnostic(
                        DiagnosticRule.REDEFINED_PROCEDURE,
                        stmt.name.range,
                        "Procedure '$name' shadows the built-in '$name'",
                    )
                }
                else -> seen += name
            }
        }
    }

    private fun checkStatement(stmt: Statement, result: ResolutionResult, out: MutableList<Diagnostic>) {
        when (stmt) {
            is ProcedureDef -> for (s in stmt.body) checkStatement(s, result, out)
            is Call -> checkCall(stmt, result, out)
            is IfStmt -> {
                checkExpression(stmt.condition, result, out)
                checkList(stmt.body, result, out)
            }
            is IfElseStmt -> {
                checkExpression(stmt.condition, result, out)
                checkList(stmt.thenBody, result, out)
                checkList(stmt.elseBody, result, out)
            }
            is RepeatStmt -> {
                checkExpression(stmt.count, result, out)
                checkList(stmt.body, result, out)
            }
            is MakeStmt -> {
                // `make :foo ...` — the VarRef target is a normal variable read.
                if (stmt.target is VarRef) checkVarRef(stmt.target, result, out)
                checkExpression(stmt.value, result, out)
            }
            is OutputStmt -> checkExpression(stmt.value, result, out)
            is LocalStmt, is StopStmt -> {}
        }
    }

    private fun checkCall(call: Call, result: ResolutionResult, out: MutableList<Diagnostic>) {
        val resolved = result.resolution[call]
        if (resolved == null) {
            out += diagnostic(
                DiagnosticRule.UNDEFINED_PROCEDURE,
                call.target.range,
                "Procedure '${call.target.text}' is not defined",
            )
        } else {
            val expected: Int
            val variadic: Boolean
            when (resolved) {
                is ProcedureSymbol -> { expected = resolved.parameterCount; variadic = false }
                is BuiltinSymbol -> { expected = resolved.parameterCount; variadic = resolved.isVariadic }
                else -> { expected = -1; variadic = false }
            }
            if (expected >= 0) {
                val actual = call.arguments.size
                val mismatch = if (variadic) actual < expected else actual != expected
                if (mismatch) {
                    val msg = if (variadic) {
                        "Procedure '${call.target.text}' expects at least $expected argument(s), got $actual"
                    } else {
                        "Procedure '${call.target.text}' expects $expected argument(s), got $actual"
                    }
                    out += diagnostic(DiagnosticRule.WRONG_ARITY, call.range, msg)
                }
            }
        }
        for (arg in call.arguments) checkAtom(arg, result, out)
    }

    private fun checkExpression(expr: Expression, result: ResolutionResult, out: MutableList<Diagnostic>) {
        when (expr) {
            is Atom -> checkAtom(expr, result, out)
            is BinaryOp -> {
                checkExpression(expr.left, result, out)
                checkExpression(expr.right, result, out)
            }
            is UnaryMinus -> checkExpression(expr.operand, result, out)
        }
    }

    private fun checkAtom(atom: Atom, result: ResolutionResult, out: MutableList<Diagnostic>) {
        when (atom) {
            is VarRef -> checkVarRef(atom, result, out)
            is ListLiteral -> checkList(atom, result, out)
            is ParenCall -> checkCall(atom.call, result, out)
            is ParenExpr -> checkExpression(atom.expr, result, out)
            is QuotedWord, is NumberLit -> {}
        }
    }

    private fun checkVarRef(ref: VarRef, result: ResolutionResult, out: MutableList<Diagnostic>) {
        if (result.resolution[ref] == null) {
            out += diagnostic(
                DiagnosticRule.UNDEFINED_VARIABLE,
                ref.range,
                "Variable ':${ref.name.text}' is not defined",
            )
        }
    }

    private fun checkList(list: ListLiteral, result: ResolutionResult, out: MutableList<Diagnostic>) {
        for (elem in list.elements) {
            when (elem) {
                is Statement -> checkStatement(elem, result, out)
                is Atom -> checkAtom(elem, result, out)
            }
        }
    }
}
