package com.github.jomof.dap.client

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

/**
 * Translates a `Throwable` raised by a DAP request into the most informative
 * human-readable string we can produce.
 *
 * Why this exists: the DAP spec puts the actual rejection reason in
 * `body.error.format` (a [DAP `Message`](https://microsoft.github.io/debug-adapter-protocol/specification#Types_Message)
 * with optional `{variable}` placeholders resolved against `body.error.variables`).
 * The top-level `message` field on the response is optional and most adapters
 * (notably `lldb-dap`) leave it unset. When that happens, LSP4J's debug
 * message adapter substitutes the literal placeholder string
 * `"Unset error message."` and parks the actual body inside
 * `ResponseError.data` as a `LinkedTreeMap<String, Any?>`.
 *
 * If we naively used `throwable.message`, every failed evaluate / setVariable /
 * etc. would surface that placeholder to the user instead of the real
 * diagnostic from the adapter. This helper unwraps the body, applies the
 * variable substitution mandated by the DAP `Message` type, and falls back to
 * `throwable.message` when nothing better is available.
 *
 * Use this anywhere we forward a DAP failure to the IDE (XEvaluationCallback,
 * setErrorMessage, XDebugSession.reportError, …).
 */
object DapErrors {

    /**
     * The literal string LSP4J inserts when an error response has no
     * `message` field. We treat this as "no useful message" and fall through
     * to the body extraction.
     */
    private const val LSP4J_PLACEHOLDER = "Unset error message."

    private val VARIABLE_PATTERN = Regex("""\{(\w+)\}""")

    /**
     * @return the best human-readable description of [throwable], drawing
     * first from the adapter-supplied `Message.format`, then any non-placeholder
     * `ResponseError.message`, then [Throwable.message], then the exception's
     * class name.
     */
    fun toUserMessage(throwable: Throwable): String {
        val responseError = unwrap(throwable)?.responseError
        if (responseError != null) {
            extractFormattedMessage(responseError.data)?.let { return it }
            responseError.message
                ?.takeUnless { it.isBlank() || it == LSP4J_PLACEHOLDER }
                ?.let { return it }
        }
        return throwable.message
            ?.takeUnless { it.isBlank() || it == LSP4J_PLACEHOLDER }
            ?: (throwable::class.simpleName ?: "Unknown error")
    }

    /**
     * Walk the cause chain (CompletionException, ExecutionException, …) and
     * return the first [ResponseErrorException] encountered, or `null` if
     * the failure didn't originate from a JSON-RPC error response.
     */
    private fun unwrap(throwable: Throwable): ResponseErrorException? {
        var current: Throwable? = throwable
        // Bound the walk so a pathological cause loop can't hang us.
        repeat(MAX_CAUSE_DEPTH) {
            if (current == null) return null
            if (current is ResponseErrorException) return current
            current = current?.cause
        }
        return null
    }

    /**
     * Pull `error.format` out of the body shape LSP4J parks in
     * [org.eclipse.lsp4j.jsonrpc.messages.ResponseError.data]. The body is a
     * Gson-parsed `Map<String, Any?>` (specifically `LinkedTreeMap`) when the
     * server returned a JSON object, which is the spec-mandated shape.
     */
    private fun extractFormattedMessage(data: Any?): String? {
        val errorEntry = (data as? Map<*, *>)?.get("error") as? Map<*, *> ?: return null
        val format = errorEntry["format"] as? String ?: return null
        val variables = errorEntry["variables"] as? Map<*, *>
        return if (variables.isNullOrEmpty()) format else substitute(format, variables)
    }

    private fun substitute(format: String, variables: Map<*, *>): String =
        VARIABLE_PATTERN.replace(format) { match ->
            val key = match.groupValues[1]
            variables[key]?.toString() ?: match.value
        }

    private const val MAX_CAUSE_DEPTH = 32
}
