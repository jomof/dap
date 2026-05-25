package com.github.jomof.dap.evaluation

import com.github.jomof.dap.language.DapLanguageProfile
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * Supplies the IntelliJ editor used inside the Evaluate dialog, Watches view
 * and value-tooltip popups. The language is taken from the active
 * [DapLanguageProfile]; if the profile's preferred language is not available
 * in the current IDE — or refuses to back an ad-hoc one-off PSI file (CLion's
 * `OCLanguage`, for example, can return null from [PsiFileFactory.createFileFromText]
 * because it expects to be backed by a real translation unit) — the provider
 * falls back to plain text so the evaluator surface still works for ad-hoc
 * REPL expressions.
 */
class DapEditorsProvider(
    private val profile: DapLanguageProfile,
) : XDebuggerEditorsProvider() {

    private val fallbackLanguage: Language = Language.findLanguageByID("TEXT") ?: PlainTextFileType.INSTANCE.language

    override fun getFileType(): FileType = profile.evaluatorLanguage()?.associatedFileType ?: FileTypes.PLAIN_TEXT

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode,
    ): Document {
        val text = expression.expression
        val preferred = profile.evaluatorLanguage()
        val psiFile = preferred?.let { tryCreatePsiFile(project, it, text) }
            ?: tryCreatePsiFile(project, fallbackLanguage, text)
            ?: error("Failed to create PSI file for DAP evaluator (preferred=${preferred?.id} fallback=${fallbackLanguage.id})")
        return PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: error("PsiDocumentManager returned null document for ${psiFile.name}")
    }

    /**
     * Wraps [PsiFileFactory.createFileFromText] so a language that refuses
     * ad-hoc PSI creation (returning null despite the Java `@NotNull`
     * annotation) doesn't crash callers — we just fall back to a more
     * permissive language. Logged at debug so this stays out of `idea.log`
     * for the normal path.
     */
    private fun tryCreatePsiFile(project: Project, language: Language, text: String): PsiFile? {
        val ext = language.associatedFileType?.defaultExtension ?: "txt"
        val fileName = "dap-evaluation.$ext"
        return try {
            PsiFileFactory.getInstance(project).createFileFromText(
                fileName,
                language,
                text,
                /* eventSystemEnabled = */ true,
                /* markAsCopy        = */ false,
            )
        } catch (throwable: Throwable) {
            log.debug("createFileFromText(${language.id}) threw; will try fallback", throwable)
            null
        }
    }

    private companion object {
        val log = logger<DapEditorsProvider>()
    }
}
