package com.github.jomof.dap.disassembly

import com.github.jomof.dap.client.DapClient
import org.eclipse.lsp4j.debug.DisassembleArguments
import org.eclipse.lsp4j.debug.DisassembledInstruction

/**
 * Thin wrapper over the DAP `disassemble` request. Hosts call this to fetch
 * a window of machine instructions around a given memory reference (a string
 * the adapter hands back on a `StackFrame.instructionPointerReference`).
 *
 * Phase 4 ships the data path; [DapDisassemblyTab] consumes it for the
 * default disassembly UI.
 */
class DapDisassemblyProvider(private val client: DapClient) {

    suspend fun disassemble(
        memoryReference: String,
        instructionCount: Int = DEFAULT_INSTRUCTIONS,
        offset: Int = 0,
        resolveSymbols: Boolean = true,
    ): List<DisassembledInstruction> {
        val args = DisassembleArguments().apply {
            this.memoryReference = memoryReference
            this.instructionCount = instructionCount
            this.offset = offset
            this.resolveSymbols = resolveSymbols
        }
        return client.disassemble(args).instructions?.toList().orEmpty()
    }

    companion object {
        const val DEFAULT_INSTRUCTIONS: Int = 64
    }
}
