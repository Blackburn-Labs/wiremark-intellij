package dev.wiremark.intellij.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider

/**
 * Registers the standard text / split / preview editor for *.wiremark files.
 *
 * Extends the platform [TextEditorWithPreviewProvider] (the same base JetBrains'
 * own Markdown split editor uses). That base pairs the standard platform text
 * editor (its hardcoded main provider) with the preview provider passed here, and
 * supplies the split toolbar, async creation, and state plumbing for free. We
 * only build the combined editor and let acceptance flow from the preview
 * provider's file-type check.
 */
class WiremarkSplitEditorProvider :
    TextEditorWithPreviewProvider(WiremarkPreviewFileEditorProvider()) {

    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor =
        TextEditorWithPreview(
            firstEditor,
            secondEditor,
            "Wiremark Editor",
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
        )
}
