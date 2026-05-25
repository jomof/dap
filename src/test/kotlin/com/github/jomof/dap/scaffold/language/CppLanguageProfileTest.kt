package com.github.jomof.dap.scaffold.language

import com.intellij.mock.MockVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the C/C++ profile's file-extension predicate. Uses
 * [com.intellij.mock.MockVirtualFile] so we don't have to spin up the full
 * platform test fixture; the only VFS bit we touch is `name`/`extension`.
 */
class CppLanguageProfileTest {

    @Test fun `accepts canonical C and C++ extensions`() {
        listOf("main.c", "main.cpp", "main.cc", "main.cxx", "main.c++", "header.h", "header.hpp", "header.hxx").forEach {
            assertTrue("$it should be debuggable", CppLanguageProfile.isDebuggable(MockVirtualFile(it)))
        }
    }

    @Test fun `accepts Objective-C extensions`() {
        assertTrue(CppLanguageProfile.isDebuggable(MockVirtualFile("foo.m")))
        assertTrue(CppLanguageProfile.isDebuggable(MockVirtualFile("foo.mm")))
    }

    @Test fun `rejects unrelated extensions`() {
        listOf("foo.kt", "foo.java", "foo.rs", "foo.go", "foo.py", "foo.ts").forEach {
            assertFalse("$it should not be debuggable", CppLanguageProfile.isDebuggable(MockVirtualFile(it)))
        }
    }

    @Test fun `extension match is case-insensitive`() {
        assertTrue(CppLanguageProfile.isDebuggable(MockVirtualFile("Main.CPP")))
        assertTrue(CppLanguageProfile.isDebuggable(MockVirtualFile("Header.H")))
    }

    @Test fun `directories are not debuggable`() {
        val dir = MockVirtualFile(true, "src")
        assertFalse(CppLanguageProfile.isDebuggable(dir))
    }

    @Test fun `profile metadata is stable`() {
        assertEquals("cpp", CppLanguageProfile.id)
        assertEquals("C/C++", CppLanguageProfile.displayName)
    }
}
