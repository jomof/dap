package com.github.jomof.dap.scaffold.language

import com.intellij.mock.MockVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustLanguageProfileTest {

    @Test fun `accepts rs files`() {
        assertTrue(RustLanguageProfile.isDebuggable(MockVirtualFile("main.rs")))
        assertTrue(RustLanguageProfile.isDebuggable(MockVirtualFile("lib.rs")))
    }

    @Test fun `extension match is case-insensitive`() {
        assertTrue(RustLanguageProfile.isDebuggable(MockVirtualFile("Main.RS")))
    }

    @Test fun `rejects non-rust files`() {
        listOf("foo.cpp", "foo.c", "foo.go", "foo.py", "Cargo.toml").forEach {
            assertFalse("$it should not be debuggable as Rust", RustLanguageProfile.isDebuggable(MockVirtualFile(it)))
        }
    }

    @Test fun `directories are not debuggable`() {
        assertFalse(RustLanguageProfile.isDebuggable(MockVirtualFile(true, "src")))
    }

    @Test fun `profile metadata is stable`() {
        assertEquals("rust", RustLanguageProfile.id)
        assertEquals("Rust", RustLanguageProfile.displayName)
    }
}
