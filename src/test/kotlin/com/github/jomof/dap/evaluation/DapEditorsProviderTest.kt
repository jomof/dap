package com.github.jomof.dap.evaluation

import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.evaluation.EvaluationMode

/**
 * Verifies that [DapEditorsProvider] keeps the Evaluate / Watches / hover
 * surfaces alive even when the active [DapLanguageProfile] points at a
 * Language that refuses ad-hoc PSI creation. CLion's `OCLanguage` was the
 * concrete trigger: `PsiFileFactory.createFileFromText` returns `null` for
 * it (despite the Java `@NotNull` annotation), and the previous code then
 * crashed inside `PsiDocumentManager.getDocument(@NotNull psiFile)`.
 *
 * Uses [LightPlatformTestCase] so we get a real Project + PSI infrastructure.
 */
class DapEditorsProviderTest : LightPlatformTestCase() {

    fun `test happy path returns a document for a real language`() {
        val provider = DapEditorsProvider(profile(PlainTextLanguage.INSTANCE))
        val doc = provider.createDocument(project, expression("1 + 1"), null, EvaluationMode.EXPRESSION)
        assertEquals("1 + 1", doc.text)
    }

    fun `test falls back to plain text when profile language refuses PSI creation`() {
        // No language → tryCreatePsiFile() against the preferred language is skipped,
        // and the fallback (plain text) is used directly. This is the same code
        // path the CLion OCLanguage failure ends up on once the preferred attempt
        // returns null.
        val provider = DapEditorsProvider(profile(null))
        val doc = provider.createDocument(project, expression("x.y.z"), null, EvaluationMode.EXPRESSION)
        assertEquals("x.y.z", doc.text)
    }

    fun `test getFileType returns plain text when profile has no language`() {
        val provider = DapEditorsProvider(profile(null))
        assertNotNull(provider.fileType)
    }

    private fun expression(text: String): XExpression = object : XExpression {
        override fun getExpression(): String = text
        override fun getLanguage(): Language? = null
        override fun getCustomInfo(): String? = null
        override fun getMode(): EvaluationMode = EvaluationMode.EXPRESSION
    }

    private fun profile(lang: Language?): DapLanguageProfile = object : DapLanguageProfile {
        override val id: String = "test"
        override val displayName: String = "Test"
        override fun isDebuggable(file: VirtualFile): Boolean = false
        override fun evaluatorLanguage(): Language? = lang
    }
}
