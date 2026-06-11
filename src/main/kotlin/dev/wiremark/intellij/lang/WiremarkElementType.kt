package dev.wiremark.intellij.lang

import com.intellij.psi.tree.IElementType

/** [IElementType] bound to [WiremarkLanguage], for the lexer's token types. */
class WiremarkElementType(debugName: String) : IElementType(debugName, WiremarkLanguage)
