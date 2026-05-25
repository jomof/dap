// Long-running fixture used by the lldb-dap / CodeLLDB pause/resume
// integration tests against Rust. Mirrors `../c/spin.c` so the same
// fixture conventions (loop forever, tick to stdout, breakpoint-able
// body) apply when driving the plugin under RustRover.
//
// The program spins forever, printing a tick every ~100ms. The
// integration test attaches via the DAP adapter, sends `pause`, waits
// for a `stopped` event, sends `continue`, then repeats — verifying
// the adapter can be paused repeatedly within a single session.
//
// `std::hint::black_box` is the Rust analogue of C's `volatile`: it
// hides the value from the optimiser so the increment + read can't be
// elided. `io::stdout().flush()` makes ticks visible to the parent
// when stdout is a pipe (println! is block-buffered in that case).
use std::hint::black_box;
use std::io::{self, Write};
use std::thread;
use std::time::Duration;

fn main() {
    let mut counter: i64 = 0;
    let tick = Duration::from_millis(100);
    loop {
        counter += 1;
        let counter = black_box(counter);
        println!("tick {}", counter);
        io::stdout().flush().ok();
        thread::sleep(tick);
    }
}
