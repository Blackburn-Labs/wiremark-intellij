package dev.wiremark.intellij.icons

import com.google.gson.JsonObject

/**
 * Pure encoding of a resolved icon map into the JSON object shipped to the JCEF
 * preview alongside the source. Browser-free, so it is unit-tested without a live
 * Chromium or platform fixtures.
 *
 * Shape (consumed by wiremark-preview.js's `loadIcon`): an object keyed by the
 * raw `src=` string the author wrote -- exactly what core hands back to
 * `loadIcon(src)` -- whose values are `{ body, viewBox }`. A `src` absent from
 * the object (rejected, oversize, or unreadable) makes `loadIcon` return
 * undefined, which core degrades to its placeholder + a soft diagnostic.
 *
 *   { "./logo.svg": { "body": "<path .../>", "viewBox": 24 }, ... }
 */
object IconPayload {

    /** The empty icon map literal, for the common no-`src=`-entries case. */
    const val EMPTY: String = "{}"

    /** Encode [icons] (raw-src -> artwork) as a compact JSON object string. */
    fun toJson(icons: Map<String, IconArt>): String {
        if (icons.isEmpty()) return EMPTY
        val root = JsonObject()
        for ((src, art) in icons) {
            val entry = JsonObject()
            entry.addProperty("body", art.body)
            entry.addProperty("viewBox", art.viewBox)
            root.add(src, entry)
        }
        return root.toString()
    }
}
