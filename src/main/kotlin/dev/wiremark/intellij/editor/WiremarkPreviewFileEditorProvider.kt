package dev.wiremark.intellij.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.wiremark.intellij.lang.WiremarkFileType

/**
 * Creates the preview half of the *.wiremark split editor. This is handed to
 * [WiremarkSplitEditorProvider]'s superclass as the "preview provider"; the
 * platform pairs it with the standard text editor and assembles the split view.
 *
 * It is not registered as a standalone `fileEditorProvider` extension -- only the
 * split provider is -- so a `.wiremark` file always opens in the split editor.
 */
class WiremarkPreviewFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType == WiremarkFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        WiremarkPreviewFileEditor(file)

    override fun getEditorTypeId(): String = "wiremark-preview-editor"

    // getPolicy() is abstract on FileEditorProvider so it must be implemented, but
    // this provider is never registered standalone -- it is only handed to
    // WiremarkSplitEditorProvider's base as the preview provider, which derives the
    // split editor's own policy. The platform therefore never consults this value
    // for editor resolution, so NONE (the neutral default) is the honest choice.
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.NONE
}
