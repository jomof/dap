package com.github.jomof.dap.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DapPathRemapTest {

    private val remap = DapPathRemap(
        listOf(
            DapPathRemap.Rule(from = "/build/src", to = "/Users/me/proj/src"),
            DapPathRemap.Rule(from = "/rustc/abc123/library", to = "/Users/me/.rustup/toolchains/stable/lib/rustlib"),
        )
    )

    @Test fun `first matching rule wins for toLocal`() {
        assertEquals("/Users/me/proj/src/foo.cpp", remap.toLocal("/build/src/foo.cpp"))
    }

    @Test fun `toLocal preserves tail after prefix`() {
        assertEquals(
            "/Users/me/proj/src/a/b/c.cpp",
            remap.toLocal("/build/src/a/b/c.cpp"),
        )
    }

    @Test fun `toAdapter inverts toLocal`() {
        val local = "/Users/me/proj/src/foo.cpp"
        assertEquals("/build/src/foo.cpp", remap.toAdapter(local))
    }

    @Test fun `rustc path rewrites correctly`() {
        assertEquals(
            "/Users/me/.rustup/toolchains/stable/lib/rustlib/std/src/io/mod.rs",
            remap.toLocal("/rustc/abc123/library/std/src/io/mod.rs"),
        )
    }

    @Test fun `no rule matches returns input unchanged`() {
        assertEquals("/tmp/orphan.cpp", remap.toLocal("/tmp/orphan.cpp"))
    }

    @Test fun `empty remap is identity`() {
        val empty = DapPathRemap.EMPTY
        assertEquals("/anywhere/file.cpp", empty.toLocal("/anywhere/file.cpp"))
        assertEquals("/anywhere/file.cpp", empty.toAdapter("/anywhere/file.cpp"))
    }

    @Test fun `partial directory name matches are ignored`() {
        assertEquals(
            "/build/src-other/foo.cpp",
            remap.toLocal("/build/src-other/foo.cpp"),
        )
    }
}
