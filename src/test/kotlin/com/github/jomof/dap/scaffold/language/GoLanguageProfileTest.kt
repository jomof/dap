package com.github.jomof.dap.scaffold.language

import com.intellij.mock.MockVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoLanguageProfileTest {

    @Test fun `accepts go files`() {
        assertTrue(GoLanguageProfile.isDebuggable(MockVirtualFile("main.go")))
        assertTrue(GoLanguageProfile.isDebuggable(MockVirtualFile("server_test.go")))
    }

    @Test fun `extension match is case-insensitive`() {
        assertTrue(GoLanguageProfile.isDebuggable(MockVirtualFile("Main.GO")))
    }

    @Test fun `rejects non-go files`() {
        listOf("foo.cpp", "foo.c", "foo.rs", "foo.py", "go.mod").forEach {
            assertFalse("$it should not be debuggable as Go", GoLanguageProfile.isDebuggable(MockVirtualFile(it)))
        }
    }

    @Test fun `directories are not debuggable`() {
        assertFalse(GoLanguageProfile.isDebuggable(MockVirtualFile(true, "pkg")))
    }

    @Test fun `profile metadata is stable`() {
        assertEquals("go", GoLanguageProfile.id)
        assertEquals("Go", GoLanguageProfile.displayName)
    }

    @Test fun `default launch args include debug mode`() {
        val args = GoLanguageProfile.defaultLaunchArgs()
        assertEquals("debug", args["mode"])
        assertEquals(false, args["stopOnEntry"])
    }

    @Test fun `default exception filters include panic`() {
        assertTrue(GoLanguageProfile.defaultExceptionFilters().contains("panic"))
    }
}
