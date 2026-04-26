package com.bugdigger.logolsp.analysis.ast

import com.bugdigger.logolsp.grammar.LogoParser
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

object AstBuilder {

    fun build(ctx: LogoParser.ProgramContext): Program {
        val statements = ctx.statement().map(::buildStatement)
        return Program(statements, ctx.toRange())
    }

    private fun buildStatement(ctx: LogoParser.StatementContext): Statement = when {
        ctx.procedureDef() != null -> buildProcedureDef(ctx.procedureDef())
        ctx.ifStmt() != null -> buildIfStmt(ctx.ifStmt())
        ctx.ifelseStmt() != null -> buildIfElseStmt(ctx.ifelseStmt())
        ctx.repeatStmt() != null -> buildRepeatStmt(ctx.repeatStmt())
        ctx.makeStmt() != null -> buildMakeStmt(ctx.makeStmt())
        ctx.localStmt() != null -> buildLocalStmt(ctx.localStmt())
        ctx.outputStmt() != null -> buildOutputStmt(ctx.outputStmt())
        ctx.stopStmt() != null -> buildStopStmt(ctx.stopStmt())
        ctx.call() != null -> buildCall(ctx.call())
        else -> error("unrecognised statement: ${ctx.text}")
    }

    private fun buildProcedureDef(ctx: LogoParser.ProcedureDefContext): ProcedureDef {
        return ProcedureDef(
            name = buildIdentifier(ctx.identifier()),
            parameters = ctx.parameter().map(::buildParameter),
            body = ctx.body().statement().map(::buildStatement),
            range = ctx.toRange(),
            toKeywordRange = ctx.TO().symbol.toRange(),
            endKeywordRange = ctx.END().symbol.toRange(),
        )
    }

    private fun buildParameter(ctx: LogoParser.ParameterContext): Parameter =
        Parameter(buildIdentifier(ctx.identifier()), ctx.toRange())

    private fun buildIfStmt(ctx: LogoParser.IfStmtContext): IfStmt =
        IfStmt(
            condition = buildExpression(ctx.expression()),
            body = buildListLiteral(ctx.listLiteral()),
            range = ctx.toRange(),
            keywordRange = ctx.IF().symbol.toRange(),
        )

    private fun buildIfElseStmt(ctx: LogoParser.IfelseStmtContext): IfElseStmt {
        val lists = ctx.listLiteral()
        return IfElseStmt(
            condition = buildExpression(ctx.expression()),
            thenBody = buildListLiteral(lists[0]),
            elseBody = buildListLiteral(lists[1]),
            range = ctx.toRange(),
            keywordRange = ctx.IFELSE().symbol.toRange(),
        )
    }

    private fun buildRepeatStmt(ctx: LogoParser.RepeatStmtContext): RepeatStmt =
        RepeatStmt(
            count = buildExpression(ctx.expression()),
            body = buildListLiteral(ctx.listLiteral()),
            range = ctx.toRange(),
            keywordRange = ctx.REPEAT().symbol.toRange(),
        )

    private fun buildMakeStmt(ctx: LogoParser.MakeStmtContext): MakeStmt {
        val target: MakeTarget = ctx.quotedWord()?.let(::buildQuotedWord)
            ?: buildVarRef(ctx.varRef())
        return MakeStmt(
            target = target,
            value = buildExpression(ctx.expression()),
            range = ctx.toRange(),
            keywordRange = ctx.MAKE().symbol.toRange(),
        )
    }

    private fun buildLocalStmt(ctx: LogoParser.LocalStmtContext): LocalStmt =
        LocalStmt(
            names = ctx.quotedWord().map(::buildQuotedWord),
            range = ctx.toRange(),
            keywordRange = ctx.LOCAL().symbol.toRange(),
        )

    private fun buildOutputStmt(ctx: LogoParser.OutputStmtContext): OutputStmt =
        OutputStmt(
            value = buildExpression(ctx.expression()),
            range = ctx.toRange(),
            // The grammar accepts either OUTPUT or OP at this position; pick
            // whichever was actually matched (the other is null).
            keywordRange = (ctx.OUTPUT() ?: ctx.OP()).symbol.toRange(),
        )

    private fun buildStopStmt(ctx: LogoParser.StopStmtContext): StopStmt =
        StopStmt(range = ctx.toRange(), keywordRange = ctx.STOP().symbol.toRange())

    private fun buildCall(ctx: LogoParser.CallContext): Call =
        Call(
            target = buildIdentifier(ctx.identifier()),
            arguments = ctx.atom().map(::buildAtom),
            range = ctx.toRange(),
        )

    private fun buildExpression(ctx: LogoParser.ExpressionContext): Expression = when (ctx) {
        is LogoParser.NegExprContext -> UnaryMinus(buildExpression(ctx.expression()), ctx.toRange())
        is LogoParser.AtomExprContext -> buildAtom(ctx.atom())
        is LogoParser.MulExprContext -> buildBinaryOp(ctx.op, ctx.expression(0), ctx.expression(1), ctx)
        is LogoParser.AddExprContext -> buildBinaryOp(ctx.op, ctx.expression(0), ctx.expression(1), ctx)
        is LogoParser.CmpExprContext -> buildBinaryOp(ctx.op, ctx.expression(0), ctx.expression(1), ctx)
        else -> error("unrecognised expression: ${ctx.text}")
    }

    private fun buildBinaryOp(
        opToken: Token,
        leftCtx: LogoParser.ExpressionContext,
        rightCtx: LogoParser.ExpressionContext,
        wholeCtx: LogoParser.ExpressionContext,
    ): BinaryOp {
        val op = when (opToken.type) {
            LogoParser.PLUS  -> BinaryOperator.PLUS
            LogoParser.MINUS -> BinaryOperator.MINUS
            LogoParser.STAR  -> BinaryOperator.STAR
            LogoParser.SLASH -> BinaryOperator.SLASH
            LogoParser.LT    -> BinaryOperator.LT
            LogoParser.GT    -> BinaryOperator.GT
            LogoParser.LE    -> BinaryOperator.LE
            LogoParser.GE    -> BinaryOperator.GE
            LogoParser.EQ    -> BinaryOperator.EQ
            LogoParser.NE    -> BinaryOperator.NE
            else -> error("unknown binary operator token type: ${opToken.type}")
        }
        return BinaryOp(buildExpression(leftCtx), op, buildExpression(rightCtx), wholeCtx.toRange())
    }

    private fun buildAtom(ctx: LogoParser.AtomContext): Atom = when {
        ctx.varRef() != null -> buildVarRef(ctx.varRef())
        ctx.quotedWord() != null -> buildQuotedWord(ctx.quotedWord())
        ctx.NUMBER() != null -> NumberLit(ctx.NUMBER().text.toDouble(), ctx.NUMBER().symbol.toRange())
        ctx.listLiteral() != null -> buildListLiteral(ctx.listLiteral())
        ctx.parenInner() != null -> buildParenAtom(ctx)
        else -> error("unrecognised atom: ${ctx.text}")
    }

    private fun buildParenAtom(ctx: LogoParser.AtomContext): Atom {
        val inner = ctx.parenInner()
        return when {
            inner.call() != null -> ParenCall(buildCall(inner.call()), ctx.toRange())
            inner.expression() != null -> ParenExpr(buildExpression(inner.expression()), ctx.toRange())
            else -> error("empty parens: ${ctx.text}")
        }
    }

    private fun buildListLiteral(ctx: LogoParser.ListLiteralContext): ListLiteral {
        val elements = mutableListOf<ListElement>()
        for (child in ctx.children) {
            when (child) {
                is LogoParser.StatementContext -> elements.add(buildStatement(child))
                is LogoParser.AtomContext -> elements.add(buildAtom(child))
                // brackets are TerminalNodes; skip
            }
        }
        return ListLiteral(elements, ctx.toRange())
    }

    private fun buildVarRef(ctx: LogoParser.VarRefContext): VarRef =
        VarRef(buildIdentifier(ctx.identifier()), ctx.toRange())

    private fun buildQuotedWord(ctx: LogoParser.QuotedWordContext): QuotedWord =
        QuotedWord(buildIdentifier(ctx.identifier()), ctx.toRange())

    private fun buildIdentifier(ctx: LogoParser.IdentifierContext): Identifier {
        val terminal = ctx.children[0] as TerminalNode
        return Identifier(text = terminal.text.lowercase(), range = terminal.symbol.toRange())
    }
}
