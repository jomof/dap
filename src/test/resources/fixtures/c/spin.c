/*
 * Long-running fixture used by the lldb-dap pause/resume integration test.
 *
 * The program spins forever, printing a tick every ~100ms. The integration
 * test attaches via lldb-dap, sends `pause`, waits for a `stopped` event,
 * sends `continue`, then repeats — verifying the adapter can be paused
 * repeatedly within a single session.
 *
 * Volatile counter + fflush keep the loop out of the optimiser's reach and
 * make tick output visible to the parent process without buffering games.
 */
#include <stdio.h>
#include <time.h>

int main(void) {
    volatile long counter = 0;
    struct timespec ts = { .tv_sec = 0, .tv_nsec = 100L * 1000L * 1000L };
    for (;;) {
        ++counter;
        printf("tick %ld\n", counter);
        fflush(stdout);
        nanosleep(&ts, NULL);
    }
    return 0;
}
