package dev.wiremark.intellij.lang

import com.intellij.openapi.util.IconLoader

object WiremarkIcons {
    /**
     * The `.wiremark` file-type icon. `IconLoader` automatically picks
     * `wiremark_dark.svg` under a dark IDE theme.
     */
    @JvmField
    val FILE = IconLoader.getIcon("/icons/wiremark.svg", WiremarkIcons::class.java)
}
