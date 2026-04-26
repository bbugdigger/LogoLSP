package com.bugdigger.logolsp.analysis

import com.bugdigger.logolsp.analysis.ast.Atom
import com.bugdigger.logolsp.analysis.ast.BinaryOp
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.Expression
import com.bugdigger.logolsp.analysis.ast.IfElseStmt
import com.bugdigger.logolsp.analysis.ast.IfStmt
import com.bugdigger.logolsp.analysis.ast.ListLiteral
import com.bugdigger.logolsp.analysis.ast.LocalStmt
import com.bugdigger.logolsp.analysis.ast.MakeStmt
import com.bugdigger.logolsp.analysis.ast.MakeTarget
import com.bugdigger.logolsp.analysis.ast.Node
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
import com.bugdigger.logolsp.analysis.symbols.DefinedSymbol
import com.bugdigger.logolsp.analysis.symbols.ProcedureSymbol
import com.bugdigger.logolsp.analysis.symbols.Scope
import com.bugdigger.logolsp.analysis.symbols.Symbol
import com.bugdigger.logolsp.analysis.symbols.SymbolTable
import com.bugdigger.logolsp.analysis.symbols.VariableScope
import com.bugdigger.logolsp.analysis.symbols.VariableSymbol

data class ResolutionResult(
    val symbolTable: SymbolTable,
    // Mapping from any AST node that names a symbol (Call, VarRef, ProcedureDef,
    // Parameter, Make/Local target QuotedWord etc.) to the Symbol it refers to.
    // Reference sites and definition sites are both keyed in here, so feature
    // providers can do `resolution[clickedNode]` regardless of where the user
    // clicked.
    val resolution: Map<Node, Symbol>,
)

class Resolver {

    private val table = SymbolTable()
    private val resolution = mutableMapOf<Node, Symbol>()

    fun resolve(program: Program): ResolutionResult {
        // Pass 1: collect all declarations (procedures + parameters + hoisted
        // LOCALs + globals) so that forward references work.
        declarePass(program)
        // Pass 2: walk every reference site and link it to a Symbol, populating
        // Symbol.references along the way.
        resolvePass(program)
        return ResolutionResult(table, resolution.toMap())
    }

    // ----------------------- Pass 1: declarations -----------------------

    private fun declarePass(program: Program) {
        // Register procedures first so they're visible regardless of order.
        for (stmt in program.statements) {
            if (stmt is ProcedureDef) registerProcedure(stmt)
        }
        // Then sweep for MAKE statements that introduce new globals, anywhere
        // in the program (top level and inside procedure bodies).
        for (stmt in program.statements) {
            sweepForGlobals(stmt, currentProcScope = null)
        }
    }

    private fun registerProcedure(def: ProcedureDef) {
        // Duplicate procedure names: keep the first; the redefinition will
        // produce a diagnostic later (Phase 3).
        if (table.procedure(def.name.text) != null) return

        val symbol = ProcedureSymbol(
            name = def.name.text,
            definitionRange = def.range,
            nameRange = def.name.range,
            parameterCount = def.parameters.size,
        )
        symbol.references.add(def.name.range)
        val scope = table.defineProcedure(symbol)
        resolution[def] = symbol

        for (param in def.parameters) {
            val paramSym = VariableSymbol(
                name = param.name.text,
                definitionRange = param.range,
                nameRange = param.name.range,
                scope = VariableScope.PARAMETER,
            )
            paramSym.references.add(param.name.range)
            scope.define(paramSym)
            resolution[param] = paramSym
        }

        // Hoist LOCAL declarations from anywhere in the body into the procedure
        // scope. A LOCAL whose name shadows a parameter is ignored; the
        // parameter wins.
        collectLocals(def.body).forEach { (qw, sym) ->
            if (scope.lookupLocal(sym.name) == null) {
                scope.define(sym)
            }
            // Resolve every LOCAL target name to whichever symbol now occupies
            // that name in the scope (the local we just added, or the
            // shadowing parameter).
            scope.lookupLocal(sym.name)?.also { resolved ->
                resolved.references.add(qw.name.range)
                resolution[qw] = resolved
            }
        }
    }

    private fun collectLocals(stmts: List<Statement>): List<Pair<QuotedWord, VariableSymbol>> {
        val out = mutableListOf<Pair<QuotedWord, VariableSymbol>>()
        for (stmt in stmts) collectLocalsFromStmt(stmt, out)
        return out
    }

    private fun collectLocalsFromStmt(
        stmt: Statement,
        out: MutableList<Pair<QuotedWord, VariableSymbol>>,
    ) {
        when (stmt) {
            is LocalStmt -> for (qw in stmt.names) {
                out.add(
                    qw to VariableSymbol(
                        name = qw.name.text,
                        definitionRange = qw.range,
                        nameRange = qw.name.range,
                        scope = VariableScope.LOCAL,
                    ),
                )
            }
            is RepeatStmt -> collectLocalsFromList(stmt.body, out)
            is IfStmt -> collectLocalsFromList(stmt.body, out)
            is IfElseStmt -> {
                collectLocalsFromList(stmt.thenBody, out)
                collectLocalsFromList(stmt.elseBody, out)
            }
            else -> {} // Calls, Make, Output, Stop, ProcedureDef can't contain LOCAL
        }
    }

    private fun collectLocalsFromList(
        list: ListLiteral,
        out: MutableList<Pair<QuotedWord, VariableSymbol>>,
    ) {
        for (elem in list.elements) {
            if (elem is Statement) collectLocalsFromStmt(elem, out)
        }
    }

    private fun sweepForGlobals(stmt: Statement, currentProcScope: Scope?) {
        when (stmt) {
            is ProcedureDef -> {
                val procScope = table.procedureScope(stmt.name.text)
                for (s in stmt.body) sweepForGlobals(s, procScope)
            }
            is MakeStmt -> registerMakeAsGlobalIfNew(stmt, currentProcScope)
            is RepeatStmt -> sweepForGlobalsInList(stmt.body, currentProcScope)
            is IfStmt -> sweepForGlobalsInList(stmt.body, currentProcScope)
            is IfElseStmt -> {
                sweepForGlobalsInList(stmt.thenBody, currentProcScope)
                sweepForGlobalsInList(stmt.elseBody, currentProcScope)
            }
            else -> {}
        }
    }

    private fun sweepForGlobalsInList(list: ListLiteral, currentProcScope: Scope?) {
        for (elem in list.elements) {
            if (elem is Statement) sweepForGlobals(elem, currentProcScope)
        }
    }

    private fun registerMakeAsGlobalIfNew(stmt: MakeStmt, currentProcScope: Scope?) {
        val nameAndRange = makeTargetNameAndRange(stmt.target) ?: return
        val (name, nameRange) = nameAndRange
        // Already in scope (parameter / local / pre-existing global)? Don't
        // create anything; the MAKE is an assignment, resolved in pass 2.
        if (currentProcScope?.lookup(name) != null) return
        if (table.globalScope.lookupLocal(name) != null) return
        // First MAKE for this name → it defines the global.
        val symbol = VariableSymbol(
            name = name,
            definitionRange = stmt.range,
            nameRange = nameRange,
            scope = VariableScope.GLOBAL,
        )
        table.globalScope.define(symbol)
    }

    private fun makeTargetNameAndRange(target: MakeTarget): Pair<String, org.eclipse.lsp4j.Range>? =
        when (target) {
            is QuotedWord -> target.name.text to target.name.range
            is VarRef -> target.name.text to target.name.range
        }

    // ----------------------- Pass 2: references -----------------------

    private fun resolvePass(program: Program) {
        for (stmt in program.statements) resolveStmt(stmt, currentProcScope = null)
    }

    private fun resolveStmt(stmt: Statement, currentProcScope: Scope?) {
        when (stmt) {
            is ProcedureDef -> {
                val procScope = table.procedureScope(stmt.name.text)
                for (s in stmt.body) resolveStmt(s, procScope)
            }
            is Call -> resolveCall(stmt, currentProcScope)
            is IfStmt -> {
                resolveExpr(stmt.condition, currentProcScope)
                resolveListLiteral(stmt.body, currentProcScope)
            }
            is IfElseStmt -> {
                resolveExpr(stmt.condition, currentProcScope)
                resolveListLiteral(stmt.thenBody, currentProcScope)
                resolveListLiteral(stmt.elseBody, currentProcScope)
            }
            is RepeatStmt -> {
                resolveExpr(stmt.count, currentProcScope)
                resolveListLiteral(stmt.body, currentProcScope)
            }
            is MakeStmt -> {
                resolveMakeTarget(stmt.target, currentProcScope)
                resolveExpr(stmt.value, currentProcScope)
            }
            is LocalStmt -> {
                // LOCAL targets were already linked during pass 1.
            }
            is OutputStmt -> resolveExpr(stmt.value, currentProcScope)
            is StopStmt -> {}
        }
    }

    private fun resolveCall(call: Call, currentProcScope: Scope?) {
        val name = call.target.text
        val symbol = table.procedure(name) ?: Builtins.lookup(name)
        if (symbol != null) {
            resolution[call] = symbol
            if (symbol is DefinedSymbol) {
                symbol.references.add(call.target.range)
            }
        }
        for (arg in call.arguments) resolveAtom(arg, currentProcScope)
    }

    private fun resolveExpr(expr: Expression, currentProcScope: Scope?) {
        when (expr) {
            is Atom -> resolveAtom(expr, currentProcScope)
            is BinaryOp -> {
                resolveExpr(expr.left, currentProcScope)
                resolveExpr(expr.right, currentProcScope)
            }
            is UnaryMinus -> resolveExpr(expr.operand, currentProcScope)
        }
    }

    private fun resolveAtom(atom: Atom, currentProcScope: Scope?) {
        when (atom) {
            is VarRef -> resolveVarRef(atom, currentProcScope)
            is QuotedWord -> {} // a literal word, not a variable reference
            is NumberLit -> {}
            is ListLiteral -> resolveListLiteral(atom, currentProcScope)
            is ParenCall -> resolveCall(atom.call, currentProcScope)
            is ParenExpr -> resolveExpr(atom.expr, currentProcScope)
        }
    }

    private fun resolveVarRef(ref: VarRef, currentProcScope: Scope?) {
        val name = ref.name.text
        val symbol = currentProcScope?.lookup(name) ?: table.globalScope.lookup(name)
        if (symbol != null) {
            resolution[ref] = symbol
            symbol.references.add(ref.name.range)
        }
        // Unresolved → diagnostics will flag this in Phase 3.
    }

    private fun resolveListLiteral(list: ListLiteral, currentProcScope: Scope?) {
        for (elem in list.elements) {
            when (elem) {
                is Statement -> resolveStmt(elem, currentProcScope)
                is Atom -> resolveAtom(elem, currentProcScope)
            }
        }
    }

    private fun resolveMakeTarget(target: MakeTarget, currentProcScope: Scope?) {
        val (name, nameRange) = makeTargetNameAndRange(target) ?: return
        val symbol = currentProcScope?.lookup(name) ?: table.globalScope.lookup(name)
        if (symbol != null) {
            // Avoid duplicating the entry that pass 1 already added when this
            // is the very first MAKE that defined the global.
            if (symbol.references.none { it == nameRange }) {
                symbol.references.add(nameRange)
            }
            resolution[target] = symbol
        }
    }
}
