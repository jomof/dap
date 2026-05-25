package com.github.jomof.dap.client

import org.eclipse.lsp4j.debug.BreakpointEventArguments
import org.eclipse.lsp4j.debug.CapabilitiesEventArguments
import org.eclipse.lsp4j.debug.ContinuedEventArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.InvalidatedEventArguments
import org.eclipse.lsp4j.debug.LoadedSourceEventArguments
import org.eclipse.lsp4j.debug.MemoryEventArguments
import org.eclipse.lsp4j.debug.ModuleEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.ProcessEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.ThreadEventArguments

/**
 * Discriminated union of asynchronous events emitted by a DAP server, surfaced
 * as a stream by [DapClient]. Each variant wraps the underlying LSP4J payload
 * without copying so downstream code can pattern-match in a Kotlin idiom while
 * still accessing the full protocol record when needed.
 *
 * Add a new variant any time the plugin starts consuming an additional DAP event.
 */
sealed interface DapEvent {
    object Initialized : DapEvent
    class Stopped(val payload: StoppedEventArguments) : DapEvent
    class Continued(val payload: ContinuedEventArguments) : DapEvent
    class Thread(val payload: ThreadEventArguments) : DapEvent
    class Output(val payload: OutputEventArguments) : DapEvent
    class Breakpoint(val payload: BreakpointEventArguments) : DapEvent
    class Terminated(val payload: TerminatedEventArguments?) : DapEvent
    class Exited(val payload: ExitedEventArguments) : DapEvent
    class Module(val payload: ModuleEventArguments) : DapEvent
    class LoadedSource(val payload: LoadedSourceEventArguments) : DapEvent
    class Capabilities(val payload: CapabilitiesEventArguments) : DapEvent
    class Process(val payload: ProcessEventArguments) : DapEvent
    class Memory(val payload: MemoryEventArguments) : DapEvent
    class Invalidated(val payload: InvalidatedEventArguments) : DapEvent
}
