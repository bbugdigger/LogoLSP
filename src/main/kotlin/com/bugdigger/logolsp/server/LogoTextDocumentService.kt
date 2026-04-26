package com.bugdigger.logolsp.server

import com.bugdigger.logolsp.analysis.DocumentManager
import com.bugdigger.logolsp.features.DefinitionProvider
import com.bugdigger.logolsp.features.RenameOutcome
import com.bugdigger.logolsp.features.RenameProvider
import com.bugdigger.logolsp.features.SemanticTokensProvider
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

// Forwards LSP document-lifecycle notifications to the DocumentManager and
// dispatches feature requests to per-feature providers. Each provider is a
// pure function of the latest Analysis for the document.
class LogoTextDocumentService(private val documents: DocumentManager) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        documents.openOrUpdate(doc.uri, doc.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // We advertise Full sync: each change event carries the entire new
        // document text. Take the first (and only) entry.
        val newText = params.contentChanges.firstOrNull()?.text ?: return
        documents.openOrUpdate(params.textDocument.uri, newText)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        documents.close(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No save-time work required: analysis runs on every change.
    }

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        val uri = params.textDocument.uri
        val analysis = documents.analysis(uri)
        val locations: MutableList<out Location> = if (analysis != null) {
            DefinitionProvider.definition(analysis, uri, params.position).toMutableList()
        } else {
            mutableListOf()
        }
        return CompletableFuture.completedFuture(Either.forLeft(locations))
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        val analysis = documents.analysis(params.textDocument.uri)
        val tokens = if (analysis != null) {
            SemanticTokensProvider.semanticTokens(analysis)
        } else {
            SemanticTokens(mutableListOf())
        }
        return CompletableFuture.completedFuture(tokens)
    }

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        val analysis = documents.analysis(params.textDocument.uri)
            ?: return CompletableFuture.completedFuture(null)
        val result = RenameProvider.prepareRename(analysis, params.position)
            ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.completedFuture(Either3.forSecond(result))
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        val uri = params.textDocument.uri
        val analysis = documents.analysis(uri)
            ?: return CompletableFuture.completedFuture(WorkspaceEdit())
        return when (val outcome = RenameProvider.rename(analysis, uri, params.position, params.newName)) {
            is RenameOutcome.Edit -> CompletableFuture.completedFuture(outcome.workspaceEdit)
            is RenameOutcome.Invalid -> {
                val error = ResponseError(ResponseErrorCode.InvalidParams, outcome.message, null)
                val future = CompletableFuture<WorkspaceEdit>()
                future.completeExceptionally(ResponseErrorException(error))
                future
            }
        }
    }
}
