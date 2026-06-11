package dev.wiremark.intellij.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import dev.wiremark.intellij.icons.IconBridge
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The preview side of the *.wiremark split editor: a JCEF panel that renders the
 * wireframe SVG live as the document changes.
 *
 * When JCEF is unavailable ([JBCefApp.isSupported] is false) the panel degrades
 * to a static message; the text editor side still works via the split editor.
 */
class WiremarkPreviewFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val rootPanel = JPanel(BorderLayout())
    private val browser: JBCefBrowser?
    private val alarm: Alarm
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)

    init {
        alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

        if (JBCefApp.isSupported()) {
            val b = JBCefBrowser()
            Disposer.register(this, b)
            browser = b
            rootPanel.add(b.component, BorderLayout.CENTER)
            b.loadHTML(WiremarkPreviewResources.buildPreviewHtml())

            document?.addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) = scheduleRender()
                },
                this,
            )
            // Initial render once the document text is available.
            scheduleRender(initial = true)
        } else {
            browser = null
            rootPanel.add(jcefUnavailableLabel(), BorderLayout.CENTER)
        }
    }

    private fun scheduleRender(initial: Boolean = false) {
        val b = browser ?: return
        alarm.cancelAllRequests()
        val delay = if (initial) INITIAL_RENDER_DELAY_MS else DEBOUNCE_MS
        alarm.addRequest({ pushSource(b) }, delay)
    }

    private fun pushSource(b: JBCefBrowser) {
        if (b.isDisposed) return
        val text = document?.immutableCharSequence?.toString() ?: return
        // Re-scan the Icons block and pre-read any src= files on every push, so an
        // edit to the source -- or to a referenced icon file -- is reflected next
        // render. Best-effort: failures yield an empty map (core draws placeholders).
        val iconsJson = IconBridge.buildIconJson(project, file, text)
        // The preview document defines window.renderWiremark; it may not have
        // executed yet right after loadHTML, so retry until it exists.
        val js = WIRE_READY_GUARD_PREFIX + WiremarkPreviewPayload.renderCall(text, iconsJson) + WIRE_READY_GUARD_SUFFIX
        // CefBrowser#getURL() may be null before the first load completes.
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.getURL() ?: "", 0)
    }

    override fun getComponent(): JComponent = rootPanel

    override fun getPreferredFocusedComponent(): JComponent? = browser?.component

    override fun getName(): String = "Preview"

    override fun setState(state: FileEditorState) {}

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        alarm.cancelAllRequests()
        // browser is disposed via the Disposer chain registered in init.
    }

    private fun jcefUnavailableLabel(): JComponent =
        JLabel(
            "<html><div style='padding:16px;color:gray;'>" +
                "Wiremark preview requires JCEF (embedded browser), which is not available in this IDE.<br>" +
                "The text editor remains fully usable.</div></html>",
            SwingConstants.CENTER,
        )

    companion object {
        private const val DEBOUNCE_MS = 250
        private const val INITIAL_RENDER_DELAY_MS = 50

        // Wrap the render call so it retries until window.renderWiremark exists
        // (loadHTML is async; the first push can race the inlined script).
        private const val WIRE_READY_GUARD_PREFIX =
            "(function attempt(n){" +
                "if(typeof window.renderWiremark==='function'){"
        private const val WIRE_READY_GUARD_SUFFIX =
            "}else if(n>0){setTimeout(function(){attempt(n-1);},30);}" +
                "})(50);"
    }
}
