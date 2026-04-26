package com.bugdigger.logolsp.analysis.ast

import org.eclipse.lsp4j.Range

sealed interface Node {
    val range: Range
}

data class Program(
    val statements: List<Statement>,
    override val range: Range,
) : Node

data class Identifier(
    val text: String,
    override val range: Range,
) : Node

// ---------- Statements ----------
//
// Statements may also appear inside list literals (for instruction lists like
// `repeat 4 [forward 100 right 90]`), so they extend ListElement.
sealed interface Statement : ListElement

data class ProcedureDef(
    val name: Identifier,
    val parameters: List<Parameter>,
    val body: List<Statement>,
    override val range: Range,
    val toKeywordRange: Range,
    val endKeywordRange: Range,
) : Statement

data class Parameter(
    val name: Identifier,
    override val range: Range,
) : Node

data class Call(
    val target: Identifier,
    val arguments: List<Atom>,
    override val range: Range,
) : Statement

data class IfStmt(
    val condition: Expression,
    val body: ListLiteral,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class IfElseStmt(
    val condition: Expression,
    val thenBody: ListLiteral,
    val elseBody: ListLiteral,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class RepeatStmt(
    val count: Expression,
    val body: ListLiteral,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class MakeStmt(
    val target: MakeTarget,
    val value: Expression,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class LocalStmt(
    val names: List<QuotedWord>,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class OutputStmt(
    val value: Expression,
    override val range: Range,
    val keywordRange: Range,
) : Statement

data class StopStmt(
    override val range: Range,
    val keywordRange: Range,
) : Statement

// ---------- Expressions ----------
sealed interface Expression : Node

data class BinaryOp(
    val left: Expression,
    val op: BinaryOperator,
    val right: Expression,
    override val range: Range,
) : Expression

enum class BinaryOperator { PLUS, MINUS, STAR, SLASH, LT, GT, LE, GE, EQ, NE }

data class UnaryMinus(
    val operand: Expression,
    override val range: Range,
) : Expression

// ---------- Atoms ----------
//
// Atoms are the subset of expressions that can stand alone as call arguments
// or as elements inside list literals.
sealed interface Atom : Expression, ListElement

data class VarRef(
    val name: Identifier,
    override val range: Range,
) : Atom, MakeTarget

data class QuotedWord(
    val name: Identifier,
    override val range: Range,
) : Atom, MakeTarget

data class NumberLit(
    val value: Double,
    override val range: Range,
) : Atom

data class ListLiteral(
    val elements: List<ListElement>,
    override val range: Range,
) : Atom

// A parenthesised call used as a value, e.g. `forward (sum 1 2)`.
data class ParenCall(
    val call: Call,
    override val range: Range,
) : Atom

// A parenthesised expression used as a value, e.g. `forward (2 + 3)`.
data class ParenExpr(
    val expr: Expression,
    override val range: Range,
) : Atom

// ---------- Marker interfaces ----------

// Targets of `MAKE`: either a quoted name (`make "x ...`) or an existing
// variable reference (`make :x ...`).
sealed interface MakeTarget : Node

// Anything that can appear inside a `[ ... ]` list literal: a Statement
// (instruction list) or an Atom (data list). Both extend this.
sealed interface ListElement : Node
