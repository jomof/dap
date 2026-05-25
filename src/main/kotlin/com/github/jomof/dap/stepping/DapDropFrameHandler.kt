package com.github.jomof.dap.stepping

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.frames.DapStackFrame
import com.github.jomof.dap.session.DapSessionScope
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ThreeState
import com.intellij.xdebugger.frame.XDropFrameHandler
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.RestartFrameArguments

/**
 * Adapts IntelliJ's "Drop Frame" UI to DAP's `restartFrame` request.
 *
 * Conceptually slightly different — drop-frame unwinds the stack while
 * restart-frame both unwinds *and* re-enters — but UX-wise both surface
 * as "rewind to the start of this frame and resume", which is what users
 * expect from the toolbar icon. Adapters that don't support it advertise
 * via [DapCapabilities.supportsRestartFrame] and the host gates the
 * handler accordingly.
 */
class DapDropFrameHandler(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
) : XDropFrameHandler {

    private val log = logger<DapDropFrameHandler>()

    override fun canDropFrame(frame: XStackFrame): ThreeState =
        if (frame is DapStackFrame) ThreeState.YES else ThreeState.NO

    override fun drop(frame: XStackFrame) {
        val dapFrame = frame as? DapStackFrame ?: return
        sessionScope.scope.launch {
            try {
                client.restartFrame(RestartFrameArguments().apply { frameId = dapFrame.dapFrameId })
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("restartFrame(${dapFrame.dapFrameId}) failed", throwable)
            }
        }
    }
}
