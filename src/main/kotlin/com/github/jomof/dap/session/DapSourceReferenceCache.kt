package com.github.jomof.dap.session

import com.github.jomof.dap.client.DapClient
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.debug.SourceArguments
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the synthetic [LightVirtualFile]s that back DAP frames whose
 * `Source.sourceReference > 0` (i.e. content the adapter holds and we can
 * only retrieve via the DAP `source` request).
 *
 * The cache is populated lazily: when [ensureLoaded] is called for a
 * reference we don't yet know, the actual `source` round-trip is dispatched
 * on the session scope and the second [peek] call returns the result.
 *
 * `peek` is intentionally sync (and may return `null`) because IntelliJ's
 * [com.intellij.xdebugger.XSourcePosition] resolution path is sync; the DAP
 * fetch primes the cache during stop-event handling so the subsequent
 * `peek` from `getSourcePosition()` is a hit.
 */
class DapSourceReferenceCache(
    private val client: DapClient,
    private val sessionScope: DapSessionScope,
) {
    private val log = logger<DapSourceReferenceCache>()
    private val files = ConcurrentHashMap<Int, LightVirtualFile>()

    /** Returns the cached synthetic file, or null if it hasn't been loaded yet. */
    fun peek(reference: Int, hintPath: String?, hintName: String?): VirtualFile? {
        files[reference]?.let { return it }
        // Trigger a load so the next peek (post-fetch) succeeds.
        ensureLoaded(reference, hintPath, hintName)
        return null
    }

    /**
     * Spawns an async [DapClient.source] request if [reference] hasn't been
     * loaded yet. Idempotent: concurrent callers see only one round-trip.
     */
    fun ensureLoaded(reference: Int, hintPath: String?, hintName: String?) {
        if (files.containsKey(reference)) return
        sessionScope.scope.launch { await(reference, hintPath, hintName) }
    }

    /**
     * Suspend variant used by code paths (e.g. stop-event handling) that
     * want the synthetic file to be ready *before* a sync `getSourcePosition`
     * call on the EDT.
     */
    suspend fun await(reference: Int, hintPath: String?, hintName: String?): VirtualFile? {
        files[reference]?.let { return it }
        return try {
            val response = client.source(SourceArguments().apply { sourceReference = reference })
            val text = response.content.orEmpty()
            val name = hintName ?: hintPath?.substringAfterLast('/') ?: "dap-source-$reference"
            val file = LightVirtualFile(name, PlainTextFileType.INSTANCE, text)
            file.isWritable = false
            files.putIfAbsent(reference, file) ?: file
        } catch (ce: CancellationException) {
            throw ce
        } catch (throwable: Throwable) {
            log.warn("Failed to load DAP source for reference $reference", throwable)
            null
        }
    }
}
