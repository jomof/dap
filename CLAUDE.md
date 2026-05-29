# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An IntelliJ Platform plugin that bridges the **Debug Adapter Protocol (DAP)** to IntelliJ's
debugger framework (`XDebugProcess` / xdebugger), so any DAP-conformant adapter (lldb-dap,
CodeLLDB, delve-dap) can drive a native-code debug session in CLion, RustRover, and other
IntelliJ-based IDEs. Scaffolded for C/C++ and Rust (lldb-dap / CodeLLDB) and Go (delve-dap).

Kotlin, JDK 21 toolchain, IntelliJ Platform Gradle Plugin 2.x, targeting IntelliJ IDEA 2025.3.5.
DAP wire types and JSON-RPC framing come from Eclipse LSP4J Debug (`org.eclipse.lsp4j.debug`).

## Commands

```bash
./gradlew check            # compile + unit tests + detekt (the main gate; CI runs this)
./gradlew test             # unit tests only
./gradlew detekt           # static analysis only
./gradlew runIde           # launch IntelliJ IDEA with the plugin (see .run/ for CLion/RustRover variants)
./gradlew verifyPlugin     # plugin compatibility check against target IDEs

# Single test class / method:
./gradlew test --tests "com.github.jomof.dap.client.DapClientTest"
./gradlew test --tests "com.github.jomof.dap.client.DapClientTest.someMethod"
```

The `.run/` directory has predefined IDE run configurations: **Run Plugin** (`runIde`),
**Run Plugin in CLion** (`runClion`), **Run Plugin in RustRover** (`runRustRover`),
**Run Tests** (`check`), **Run Verifications** (`verifyPlugin`).

### Static analysis is part of the build

`./gradlew check` runs **detekt** and fails on any finding at `Info` severity or above
(`failOnSeverity = Info`, `ignoreFailures = false`) — new warnings break the build. Config is in
`detekt.yml` layered on detekt's default ruleset; ktlint formatting rules are wrapped in via the
`detektFormatting` plugin. Detekt is pinned to a 2.0-alpha because the stable line is incompatible
with this Gradle/Kotlin toolchain (see `gradle/libs.versions.toml`).

**Qodana** (`./gradlew qodanaScan`) runs IntelliJ's full inspection engine headlessly via Docker
to catch what detekt can't; it is not wired into `check`. Results go to `.qodana/` (gitignored).

## Tests: unit vs. integration

- **Unit tests** run against `MockDapServer` (`src/test/.../mock/`) — a hand-rolled in-process DAP
  server implementing the minimum slice the production code exercises. These always run.
- **Integration tests** (`src/test/.../integration/`) talk to *real* debug adapters. They are
  **opt-in and skip by default**: each `assumeTrue`s on `DAP_INTEGRATION_TESTS=1` and on the
  adapter binary being present. CodeLLDB tests will provision/download the adapter; lldb-dap tests
  only resolve an already-installed binary. Run them with `DAP_INTEGRATION_TESTS=1 ./gradlew test`.
- Integration smoke binaries are built by `src/test/resources/fixtures/c/build.sh` (builds the C
  and, if `rustc` is present, Rust fixtures).

## Architecture

The codebase is split into a **transplantable production DAP layer** and a **scaffold** that exists
only to run/test it. The scaffold (`scaffold/` package and everything registered in `plugin.xml`)
is meant to be removed when the production layer is dropped into a larger host codebase. Keep
language- and host-specific concerns in the scaffold; keep the core language-agnostic.

### Core flow

`DapDebugProcess` (`session/`) is the **central transplantable component** — the bridge between
IntelliJ's `XDebugProcess` contract and a live DAP session. It owns:

- **`DapClient`** (`client/`) — the LSP4J-backed JSON-RPC client. Wraps requests as coroutines
  (futures `.await()`ed) and exposes incoming DAP events as a Kotlin `SharedFlow`. Talks over a
  **`DapTransport`** (`Stdio` for lldb-dap, `Tcp` for CodeLLDB/delve-dap).
- The per-session coroutine scope (`DapSessionScope`) and bookkeeping for breakpoints, frames, and
  output forwarding.

Supporting production packages, all language-agnostic:

- `breakpoints/` — sync IDE breakpoints ↔ DAP `setBreakpoints`, exception breakpoints, and a
  synthetic-pause gate.
- `frames/` — `XExecutionStack` / `XStackFrame` / `XValue` adapters over DAP stack/scopes/variables.
- `evaluation/` — Evaluate-dialog editor provider and expression evaluator.
- `session/` — source mapping and path remapping between adapter paths and IDE `VirtualFile`s
  (`DapSourceMapper`, `DapPathRemap`, `DapSourceReferenceCache`), launch spec.
- `disassembly/`, `stepping/` (drop-frame, smart step-into), `output/`, `reverse/` (runInTerminal).

### Extension seams

- **`DapLanguageProfile`** (`language/`) — the narrow surface of language-specific behaviour the
  core needs (debuggable-file check, evaluator `Language`, default launch args, exception filters).
  Concrete profiles (Cpp/Rust/Go) live in `scaffold/language/`. A host transplanting the core
  supplies its own profile by implementing this interface.
- **`DapAdapterProvisioner`** (`scaffold/locator/`) — locates/fetches an adapter binary via a
  deliberate two-step contract: `resolve()` is cheap and offline (never touches the network);
  `provision()` may download. lldb-dap is resolve-only; CodeLLDB resolves-or-downloads from GitHub
  releases into a local cache.

## Conventions

- Code carries dense rationale comments (build.gradle.kts, libs.versions.toml, interface KDoc).
  Read them before changing build/tooling config — version pins and disabled tasks have specific
  reasons documented inline.
- gson is excluded from the LSP4J deps because the IntelliJ Platform already bundles it; don't
  re-add it.
- `instrumentCode` is intentionally disabled (no `.form` files, and the 2.x instrumentation task is
  incompatible with some IDE releases).