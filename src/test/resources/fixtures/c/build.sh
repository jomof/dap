#!/usr/bin/env bash
# Build the smoke binaries used by the opt-in lldb-dap integration tests.
# Run with: bash src/test/resources/fixtures/c/build.sh
#
# Also builds the Rust spin fixture (../rust/spin.rs) when `rustc` is
# on PATH, so the same set of debugger smoke targets exists for the
# RustRover sandbox without forcing a Rust toolchain on contributors
# who only run the C-based tests.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cc -g -O0 "$DIR/hello.c" -o "$DIR/hello"
echo "built $DIR/hello"
cc -g -O0 "$DIR/spin.c" -o "$DIR/spin"
echo "built $DIR/spin"

RUST_DIR="$(cd "$DIR/../rust" && pwd)"
if command -v rustc >/dev/null 2>&1; then
    # `-C opt-level=0 -g` is the cargo dev-profile analogue. `--edition=2021`
    # matches modern stable. Output is a regular ELF/Mach-O with debug info
    # in the same layout lldb-dap / CodeLLDB consume natively.
    rustc -g -C opt-level=0 --edition=2021 "$RUST_DIR/spin.rs" -o "$RUST_DIR/spin"
    echo "built $RUST_DIR/spin"
else
    echo "rustc not on PATH — skipping $RUST_DIR/spin (run \`brew install rustup\` or https://rustup.rs to enable)"
fi
