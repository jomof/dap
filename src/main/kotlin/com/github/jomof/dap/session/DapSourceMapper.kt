package com.github.jomof.dap.session

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import org.eclipse.lsp4j.debug.Source
import java.io.File

/**
 * Bridges the source-position vocabulary of DAP and IntelliJ:
 *
 *  - DAP line/column numbers are 1-based; [XSourcePosition] is 0-based.
 *  - DAP `Source` may carry a `path` (resolvable on the local filesystem) or a
 *    `sourceReference > 0` (the adapter is the only one that can produce the
 *    content; backed by [DapSourceReferenceCache] in that case).
 *  - Paths from DAP may use platform-foreign separators; we normalise to
 *    [File]'s representation before asking the VFS to resolve them.
 *  - Phase 3 added prefix-based path remapping ([DapPathRemap]) so build-dir
 *    paths, `/rustc/<commit>/library/...` and similar can resolve to the
 *    developer's actual source tree.
 */
class DapSourceMapper(
    private val remap: DapPathRemap = DapPathRemap.EMPTY,
    private val sourceReferenceCache: DapSourceReferenceCache? = null,
) {

    /** DAP → IntelliJ line index. DAP 1-based, IntelliJ 0-based. */
    fun toEditorLine(dapLine: Int): Int = (dapLine - 1).coerceAtLeast(0)

    /** DAP → IntelliJ column index. DAP 1-based, IntelliJ 0-based. */
    fun toEditorColumn(dapColumn: Int?): Int =
        if (dapColumn == null || dapColumn <= 0) 0 else dapColumn - 1

    /** IntelliJ → DAP line. Inverse of [toEditorLine]. */
    fun toDapLine(editorLine: Int): Int = editorLine + 1

    /** IntelliJ → DAP column. Inverse of [toEditorColumn]. */
    fun toDapColumn(editorColumn: Int): Int = editorColumn + 1

    /**
     * Resolves a DAP [Source] + 1-based [dapLine] to an [XSourcePosition], or
     * `null` if the source can't be located on the local filesystem. Adapters
     * that only expose a `sourceReference` will return `null` here in Phase 1;
     * Phase 3 introduces a synthetic `VirtualFile` path for those.
     *
     * Must not be called inside a write action; it consults the VFS.
     */
    fun toSourcePosition(source: Source?, dapLine: Int): XSourcePosition? {
        if (source == null) return null
        val byReference = source.sourceReference?.takeIf { it > 0 }
        val file: VirtualFile? = when {
            byReference != null -> sourceReferenceCache?.peek(byReference, source.path, source.name)
            else -> {
                val path = source.path?.takeIf { it.isNotBlank() } ?: return null
                resolveFile(remap.toLocal(path))
            }
        }
        return file?.let { XDebuggerUtil.getInstance().createPosition(it, toEditorLine(dapLine)) }
    }

    /**
     * Builds a DAP [Source] referring to [file] for use in `setBreakpoints`
     * and similar outgoing requests. The DAP spec only requires `path`; `name`
     * is set for adapters (and humans) that find it convenient.
     */
    fun toDapSource(file: VirtualFile): Source {
        val source = Source()
        source.path = remap.toAdapter(file.path)
        source.name = file.name
        return source
    }

    private fun resolveFile(path: String): VirtualFile? {
        val normalised = File(path).absoluteFile.path
        return ReadAction.compute<VirtualFile?, RuntimeException> {
            LocalFileSystem.getInstance().findFileByPath(normalised)
        }
    }
}
