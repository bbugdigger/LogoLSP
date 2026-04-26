package com.bugdigger.logolsp.analysis.symbols

// Per-document symbol table. Holds the global scope (variables introduced by
// top-level `make`), the user-defined procedures, and one Scope per procedure
// (its parameters + locals).
//
// Built-in procedures live in a separate read-only catalog and are not stored
// here.
class SymbolTable {

    val globalScope = Scope()

    private val procedures = mutableMapOf<String, ProcedureSymbol>()
    private val procedureScopes = mutableMapOf<String, Scope>()

    fun defineProcedure(symbol: ProcedureSymbol): Scope {
        procedures[symbol.name] = symbol
        val scope = Scope(parent = globalScope)
        procedureScopes[symbol.name] = scope
        return scope
    }

    fun procedure(name: String): ProcedureSymbol? = procedures[name]

    fun procedureScope(name: String): Scope? = procedureScopes[name]

    fun procedures(): Collection<ProcedureSymbol> = procedures.values

    // Read-only view over all procedure scopes; used by the rename provider
    // to locate the scope a parameter or local belongs to so it can check
    // for same-scope name collisions.
    fun allProcedureScopes(): Collection<Scope> = procedureScopes.values
}
