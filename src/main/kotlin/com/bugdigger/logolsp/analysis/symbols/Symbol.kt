package com.bugdigger.logolsp.analysis.symbols

import org.eclipse.lsp4j.Range

sealed interface Symbol {
    val name: String
}

// A symbol the user can rename and jump to. Built-ins are not DefinedSymbols
// because they have no source location and must not be renameable.
sealed interface DefinedSymbol : Symbol {
    // Range of the entire defining construct (e.g. `to square :size ... end`).
    val definitionRange: Range
    // Range of just the identifier inside the definition (used for rename UX
    // and as the jump target for go-to-definition).
    val nameRange: Range
    // Mutable list of every range where this symbol is referenced. Populated
    // by the resolver during analysis; consumed by go-to-definition (inverse
    // lookup), find-references, and rename.
    val references: MutableList<Range>
}

data class ProcedureSymbol(
    override val name: String,
    override val definitionRange: Range,
    override val nameRange: Range,
    val parameterCount: Int,
    override val references: MutableList<Range> = mutableListOf(),
) : DefinedSymbol

data class VariableSymbol(
    override val name: String,
    override val definitionRange: Range,
    override val nameRange: Range,
    val scope: VariableScope,
    override val references: MutableList<Range> = mutableListOf(),
) : DefinedSymbol

enum class VariableScope { GLOBAL, PARAMETER, LOCAL }

data class BuiltinSymbol(
    override val name: String,
    val parameterCount: Int,
    val isVariadic: Boolean = false,
    val documentation: String = "",
) : Symbol
