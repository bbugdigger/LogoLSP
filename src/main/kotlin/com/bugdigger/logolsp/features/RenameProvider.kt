package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analysis
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.Node
import com.bugdigger.logolsp.analysis.ast.NodeFinder
import com.bugdigger.logolsp.analysis.ast.Parameter
import com.bugdigger.logolsp.analysis.ast.ProcedureDef
import com.bugdigger.logolsp.analysis.ast.QuotedWord
import com.bugdigger.logolsp.analysis.ast.VarRef
import com.bugdigger.logolsp.analysis.builtins.Builtins
import com.bugdigger.logolsp.analysis.symbols.DefinedSymbol
import com.bugdigger.logolsp.analysis.symbols.ProcedureSymbol
import com.bugdigger.logolsp.analysis.symbols.Scope
import com.bugdigger.logolsp.analysis.symbols.SymbolTable
import com.bugdigger.logolsp.analysis.symbols.VariableScope
import com.bugdigger.logolsp.analysis.symbols.VariableSymbol
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

private val IDENTIFIER_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

sealed class RenameOutcome {
    data class Edit(val workspaceEdit: WorkspaceEdit) : RenameOutcome()
    data class Invalid(val message: String) : RenameOutcome()
}

object RenameProvider {

    // Returns the rename UX range (just the identifier, not surrounding `:`
    // or `"`) plus the current name as a placeholder. Returns null when the
    // cursor isn't on a user-renameable symbol — built-ins, literals, etc.
    fun prepareRename(analysis: Analysis, position: Position): PrepareRenameResult? {
        val hit = NodeFinder.resolvableAt(analysis, position) ?: return null
        val symbol = hit.symbol as? DefinedSymbol ?: return null
        val identRange = identifierRangeOf(hit.node) ?: return null
        return PrepareRenameResult(identRange, symbol.name)
    }

    fun rename(
        analysis: Analysis,
        uri: String,
        position: Position,
        newName: String,
    ): RenameOutcome {
        val hit = NodeFinder.resolvableAt(analysis, position)
            ?: return RenameOutcome.Invalid("No symbol at the cursor")
        val symbol = hit.symbol as? DefinedSymbol
            ?: return RenameOutcome.Invalid("Cannot rename built-in '${hit.symbol.name}'")

        if (!IDENTIFIER_REGEX.matches(newName)) {
            return RenameOutcome.Invalid("'$newName' is not a valid LOGO identifier")
        }
        if (Builtins.isBuiltin(newName)) {
            return RenameOutcome.Invalid("'$newName' would collide with the built-in '$newName'")
        }

        collisionMessage(symbol, newName, analysis.symbolTable)?.let {
            return RenameOutcome.Invalid(it)
        }

        // The references list contains every occurrence of the name,
        // including the definition itself, so a single TextEdit per entry
        // covers the whole rename.
        val edits = symbol.references.map { range -> TextEdit(range, newName) }
        return RenameOutcome.Edit(WorkspaceEdit(mapOf(uri to edits)))
    }

    private fun collisionMessage(
        symbol: DefinedSymbol,
        newName: String,
        table: SymbolTable,
    ): String? = when (symbol) {
        is ProcedureSymbol -> {
            val existing = table.procedure(newName)
            if (existing != null && existing !== symbol) {
                "Procedure '$newName' is already defined in this file"
            } else null
        }
        is VariableSymbol -> {
            val scope = scopeOf(symbol, table)
            val existing = scope?.lookupLocal(newName)
            if (existing != null && existing !== symbol) {
                "Variable '$newName' is already defined in this scope"
            } else null
        }
    }

    private fun scopeOf(symbol: VariableSymbol, table: SymbolTable): Scope? =
        if (symbol.scope == VariableScope.GLOBAL) {
            table.globalScope
        } else {
            // Parameters and locals live in exactly one procedure scope; find
            // the one that contains this symbol by reference equality.
            table.allProcedureScopes().firstOrNull { it.variables().any { v -> v === symbol } }
        }

    // Range of the identifier portion of a clickable node, used as the
    // "what's being edited" highlight during rename.
    private fun identifierRangeOf(node: Node): Range? = when (node) {
        is Call -> node.target.range
        is ProcedureDef -> node.name.range
        is Parameter -> node.name.range
        is VarRef -> node.name.range
        is QuotedWord -> node.name.range
        else -> null
    }
}
