package com.github.jomof.dap.frames

import com.github.jomof.dap.client.DapClient
import com.github.jomof.dap.client.DapErrors
import com.github.jomof.dap.session.DapSessionScope
import com.github.jomof.dap.session.DapSourceMapper
import com.github.jomof.dap.session.DapSourceReferenceCache
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.StackTraceArguments

/**
 * Represents one DAP thread in the Threads/Frames panel.
 *
 * The execution stack carries the thread id so subsequent step/pause/etc.
 * requests can target it. When the user picks the thread, IntelliJ asks for
 * the top frame synchronously and then for the remaining frames via
 * [computeStackFrames] (range-based pagination).
 *
 * The top frame is preloaded when the suspend context is built so that the
 * gutter highlight appears immediately on stop; further frames are fetched
 * lazily.
 */
class DapExecutionStack(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
    private val sourceMapper: DapSourceMapper,
    private val sourceReferenceCache: DapSourceReferenceCache?,
    val threadId: Int,
    threadName: String,
    private val topFrame: DapStackFrame?,
) : XExecutionStack(threadName) {

    private val log = logger<DapExecutionStack>()

    override fun getTopFrame(): XStackFrame? = topFrame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        sessionScope.scope.launch {
            try {
                val args = StackTraceArguments().apply {
                    threadId = this@DapExecutionStack.threadId
                    startFrame = firstFrameIndex
                }
                val response = client.stackTrace(args)
                val rawFrames = response.stackFrames.orEmpty()
                // Pre-load source-reference content in parallel so the synchronous
                // [DapStackFrame.getSourcePosition] call later sees populated cache entries.
                coroutineScope {
                    rawFrames
                        .mapNotNull { f -> f.source?.sourceReference?.takeIf { it > 0 }?.let { ref -> Triple(ref, f.source.path, f.source.name) } }
                        .map { (ref, path, name) -> async { sourceReferenceCache?.await(ref, path, name) } }
                        .awaitAll()
                }
                val frames: List<XStackFrame> = rawFrames.map {
                    DapStackFrame(client, sessionScope, sourceMapper, it)
                }
                ApplicationManager.getApplication().invokeLater {
                    container.addStackFrames(frames, true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                log.warn("Failed to fetch stack trace for thread $threadId", throwable)
                val userMessage = DapErrors.toUserMessage(throwable)
                ApplicationManager.getApplication().invokeLater {
                    container.errorOccurred(userMessage)
                }
            }
        }
    }
}
