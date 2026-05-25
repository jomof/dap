package com.github.jomof.dap.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus

/**
 * A [CoroutineScope] tied to the lifetime of a single DAP debug session.
 *
 * The scope uses a [SupervisorJob] so a failure in one launched coroutine
 * (e.g. a stack-trace fetch) doesn't cancel sibling work (e.g. the event
 * pump). It is cancelled exactly once when the owning component is disposed,
 * stopping every coroutine that was launched against it.
 *
 * A simple [CoroutineExceptionHandler] logs uncaught failures rather than
 * letting them bubble up to the IntelliJ framework's default handler, which
 * would surface unhelpful exception popups.
 */
class DapSessionScope(private val sessionName: String) : Disposable {

    private val log = logger<DapSessionScope>()

    private val handler = CoroutineExceptionHandler { _, throwable ->
        log.warn("Uncaught coroutine exception in DAP session '$sessionName'", throwable)
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + handler).plus(handler)

    override fun dispose() {
        scope.cancel(kotlinx.coroutines.CancellationException("DAP session '$sessionName' disposed"))
    }
}
