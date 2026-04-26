package com.bugdigger.logolsp.server

import com.bugdigger.logolsp.analysis.DocumentManager
import com.bugdigger.logolsp.features.DefinitionProvider
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
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
}
