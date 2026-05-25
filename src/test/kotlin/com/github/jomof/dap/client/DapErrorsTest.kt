package com.github.jomof.dap.client

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CompletionException

class DapErrorsTest {

    @Test
    fun `extracts adapter-supplied format string from LSP4J placeholder error`() {
        // What `lldb-dap` actually produces for `evaluate("i+1")` when `i`
        // isn't in scope: success=false, no top-level `message`, the real
        // diagnostic packed into `body.error.format`. LSP4J turns that into
        // the literal placeholder `"Unset error message."` plus a body in
        // `responseError.data`.
        val throwable = errorWithBody(
            lsp4jMessage = "Unset error message.",
            body = mapOf(
                "error" to mapOf(
                    "id" to 1100,
                    "format" to "use of undeclared identifier 'i'",
                    "showUser" to true,
                ),
            ),
        )

        assertEquals("use of undeclared identifier 'i'", DapErrors.toUserMessage(throwable))
    }

    @Test
    fun `applies DAP Message variable substitution`() {
        // DAP `Message.format` may embed `{name}` placeholders resolved
        // against `Message.variables` — same convention used throughout the
        // spec for adapter-side i18n.
        val throwable = errorWithBody(
            lsp4jMessage = "Unset error message.",
            body = mapOf(
                "error" to mapOf(
                    "format" to "Cannot evaluate '{expr}' in frame {frame}",
                    "variables" to mapOf("expr" to "i+1", "frame" to "42"),
                ),
            ),
        )

        assertEquals("Cannot evaluate 'i+1' in frame 42", DapErrors.toUserMessage(throwable))
    }

    @Test
    fun `leaves unresolved placeholders intact when variable is missing`() {
        // Defensive: if an adapter is buggy and references a variable it
        // never declared, we don't want to crash or produce gibberish — the
        // raw `{placeholder}` is still more informative than nothing.
        val throwable = errorWithBody(
            lsp4jMessage = "Unset error message.",
            body = mapOf(
                "error" to mapOf(
                    "format" to "Bad: {missing}",
                    "variables" to emptyMap<String, String>(),
                ),
            ),
        )

        assertEquals("Bad: {missing}", DapErrors.toUserMessage(throwable))
    }

    @Test
    fun `prefers non-placeholder LSP4J message when body is absent`() {
        // Some adapters fill in the top-level `message` field but skip the
        // body entirely. Surface that message rather than falling through
        // to the throwable's class name.
        val throwable = errorWithBody(lsp4jMessage = "cancelled", body = null)

        assertEquals("cancelled", DapErrors.toUserMessage(throwable))
    }

    @Test
    fun `unwraps CompletionException to find ResponseErrorException`() {
        // CompletableFuture.get() / .join() (which our coroutine `await`
        // doesn't use, but other callers might) wraps thrown exceptions in
        // CompletionException. Make sure we still see through to the real
        // error.
        val inner = errorWithBody(
            lsp4jMessage = "Unset error message.",
            body = mapOf("error" to mapOf("format" to "deep cause")),
        )
        val wrapped = CompletionException("wrapper", inner)

        assertEquals("deep cause", DapErrors.toUserMessage(wrapped))
    }

    @Test
    fun `falls back to throwable message when not a ResponseError`() {
        val throwable = IllegalStateException("plain old exception")

        assertEquals("plain old exception", DapErrors.toUserMessage(throwable))
    }

    @Test
    fun `falls back to class name when nothing else is informative`() {
        val throwable = object : RuntimeException(null as String?) {}

        // The class name path: `null` message + non-ResponseError → simple
        // class name. We use the actual runtime class name here because the
        // anonymous subclass produces a synthetic name like `…$1` that we
        // don't want to hardcode in the test.
        assertEquals(throwable::class.simpleName ?: "Unknown error", DapErrors.toUserMessage(throwable))
    }

    private fun errorWithBody(lsp4jMessage: String, body: Any?): ResponseErrorException {
        val err = ResponseError().apply {
            code = ResponseErrorCode.UnknownErrorCode.value
            message = lsp4jMessage
            data = body
        }
        return ResponseErrorException(err)
    }
}
