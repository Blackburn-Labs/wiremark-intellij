package dev.wiremark.intellij.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the SVG sanitizer, mirroring the security guarantees of the
 * wiremark CLI's `iconFromSvg` (/tmp/package/src/cli.js). The extracted `body`
 * flows verbatim into the rendered SVG's innerHTML, so these are XSS tests.
 */
class IconSvgTest {

    @Test
    fun `extracts inner body and default viewBox`() {
        val art = IconSvg.fromSvg("""<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h24v24H0z"/></svg>""")
        assertNotNull(art)
        assertEquals("""<path d="M0 0h24v24H0z"/>""", art!!.body)
        assertEquals(24.0, art.viewBox, 0.0)
    }

    @Test
    fun `viewBox attribute sets the grid to the larger dimension`() {
        val art = IconSvg.fromSvg("""<svg viewBox="0 0 32 48"><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertEquals(48.0, art!!.viewBox, 0.0)
    }

    @Test
    fun `width height fallback when no viewBox`() {
        val art = IconSvg.fromSvg("""<svg width="16" height="16"><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertEquals(16.0, art!!.viewBox, 0.0)
    }

    @Test
    fun `px-suffixed dimensions are parsed`() {
        val art = IconSvg.fromSvg("""<svg height="20px"><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertEquals(20.0, art!!.viewBox, 0.0)
    }

    @Test
    fun `text without an svg element yields null`() {
        assertNull(IconSvg.fromSvg("not an svg at all"))
        assertNull(IconSvg.fromSvg(""))
    }

    @Test
    fun `an empty svg body yields null`() {
        assertNull(IconSvg.fromSvg("<svg></svg>"))
        assertNull(IconSvg.fromSvg("""<svg viewBox="0 0 24 24">   </svg>"""))
    }

    @Test
    fun `script elements are stripped`() {
        val art = IconSvg.fromSvg(
            """<svg><script>alert(1)</script><path d="M0 0"/></svg>""",
        )
        assertNotNull(art)
        assertFalse("script tag must not survive", art!!.body.contains("<script", ignoreCase = true))
        assertTrue(art.body.contains("""<path d="M0 0"/>"""))
    }

    @Test
    fun `style and foreignObject elements are stripped`() {
        val art = IconSvg.fromSvg(
            "<svg><style>* { fill: red }</style><foreignObject><body/></foreignObject><path d=\"M0 0\"/></svg>",
        )
        assertNotNull(art)
        assertFalse(art!!.body.contains("<style", ignoreCase = true))
        assertFalse(art.body.contains("<foreignObject", ignoreCase = true))
    }

    @Test
    fun `an unclosed script swallows to end (no surviving markup)`() {
        // Mirrors cli.js: an UNCLOSED script/style/foreignObject is consumed to
        // end-of-input rather than leaving an open, still-dangerous tag behind.
        val art = IconSvg.fromSvg("""<svg><path d="M0 0"/><script>evil()</svg>""")
        // Either null (everything after the path was swallowed leaving only the
        // path, which is fine) -- assert no script tag remains regardless.
        if (art != null) {
            assertFalse(art.body.contains("<script", ignoreCase = true))
        }
    }

    @Test
    fun `inline event handlers are stripped in every quoting form`() {
        val art = IconSvg.fromSvg(
            """<svg><path d="M0 0" onclick="x()" onload='y()' onmouseover=z></path></svg>""",
        )
        assertNotNull(art)
        assertFalse("onclick must be gone", art!!.body.contains("onclick", ignoreCase = true))
        assertFalse("onload must be gone", art.body.contains("onload", ignoreCase = true))
        assertFalse("onmouseover must be gone", art.body.contains("onmouseover", ignoreCase = true))
        // The geometry survives.
        assertTrue(art.body.contains("""d="M0 0""""))
    }

    @Test
    fun `SMIL set element injecting an event handler is stripped`() {
        // `<set attributeName="onload" to="...">` creates an on* attribute at
        // runtime, which a static-attribute scrub alone would miss. The whole
        // SMIL element must be removed.
        val art = IconSvg.fromSvg(
            """<svg><rect width="24" height="24"><set attributeName="onload" to="alert(1)"/></rect><path d="M1 1"/></svg>""",
        )
        assertNotNull(art)
        assertFalse("<set> must be gone", art!!.body.contains("<set", ignoreCase = true))
        assertFalse("no injected on* attribute", Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(art.body))
        assertTrue("sibling geometry survives", art.body.contains("""d="M1 1""""))
    }

    @Test
    fun `SMIL animate element injecting a javascript href is stripped`() {
        val art = IconSvg.fromSvg(
            """<svg><a><animate attributeName="href" to="javascript:alert(1)"/><path d="M0 0"/></a></svg>""",
        )
        assertNotNull(art)
        assertFalse("<animate> must be gone", art!!.body.contains("<animate", ignoreCase = true))
        assertFalse("javascript: must be gone", art.body.contains("javascript:", ignoreCase = true))
        assertTrue(art.body.contains("""d="M0 0""""))
    }

    @Test
    fun `a container SMIL element and its content are both removed`() {
        val art = IconSvg.fromSvg(
            """<svg><set attributeName="onload" to="x">junk</set><path d="M2 2"/></svg>""",
        )
        assertNotNull(art)
        assertFalse(art!!.body.contains("<set", ignoreCase = true))
        assertFalse("the element's content goes too", art.body.contains("junk"))
        assertTrue(art.body.contains("""d="M2 2""""))
    }

    @Test
    fun `an unclosed SMIL element swallows to end-of-input`() {
        val art = IconSvg.fromSvg("""<svg><path d="M8 8"/><set attributeName="onload" to="alert(1)">""")
        // Like an unclosed script: the dangerous tag must not survive (the path
        // before it is kept; everything from the tag on is dropped).
        if (art != null) {
            assertFalse(art.body.contains("<set", ignoreCase = true))
            assertFalse(Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(art.body))
        }
    }

    @Test
    fun `script content containing a slash-gt does not leave stray text`() {
        // The /> inside script CONTENT must not stop the element strip early
        // (the rule anchors /> to the opening tag), so nothing leaks out.
        val art = IconSvg.fromSvg("""<svg><script>var a=1/>2;</script><path d="M3 3"/></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("<script", ignoreCase = true))
        assertFalse("no stray script content", art.body.contains("var a"))
        assertTrue(art.body.contains("""d="M3 3""""))
    }

    @Test
    fun `an element merely prefixed by a dangerous name is not stripped`() {
        // The `\b` after the name means `<setter-thing>` is NOT mistaken for <set>.
        val art = IconSvg.fromSvg("""<svg><setter-thing x="1"/><path d="M5 5"/></svg>""")
        assertNotNull(art)
        assertTrue("a name-prefixed element survives", art!!.body.contains("<setter-thing"))
        assertTrue(art.body.contains("""d="M5 5""""))
    }

    @Test
    fun `external href references are stripped, same-document hash refs survive`() {
        val art = IconSvg.fromSvg(
            """<svg><use href="https://evil.example/x.svg#a"/><use href="#local"/></svg>""",
        )
        assertNotNull(art)
        assertFalse("external href must be gone", art!!.body.contains("evil.example"))
        assertFalse(art.body.contains("https://"))
        assertTrue("same-document #ref survives", art.body.contains("""href="#local""""))
    }

    @Test
    fun `a namespace-prefixed script element is stripped`() {
        // Browsers honor the local name regardless of an xmlns prefix, so
        // `<svg:script>` runs like `<script>`. The strip must tolerate the prefix
        // on both the open and the (here matching) close tag.
        val art = IconSvg.fromSvg("""<svg><svg:script>evil()</svg:script><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertFalse("<svg:script> must be gone", art!!.body.contains("script", ignoreCase = true))
        assertTrue(art.body.contains("""d="M0 0""""))
    }

    @Test
    fun `a namespace-prefixed self-closing SMIL element is stripped`() {
        val art = IconSvg.fromSvg("""<svg><svg:animate attributeName="href" to="javascript:alert(1)"/><path d="M1 1"/></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("<svg:animate", ignoreCase = true))
        assertFalse(art.body.contains("javascript:", ignoreCase = true))
        assertTrue(art.body.contains("""d="M1 1""""))
    }

    @Test
    fun `a namespace-prefixed container element with a prefixed close is stripped`() {
        // The close tag may carry its own prefix; the backreference binds the bare
        // local name, so `<x:set ...>junk</x:set>` is removed whole.
        val art = IconSvg.fromSvg("""<svg><x:set attributeName="onload" to="y">junk</x:set><path d="M2 2"/></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("<x:set", ignoreCase = true))
        assertFalse("the content goes too", art.body.contains("junk"))
        assertTrue(art.body.contains("""d="M2 2""""))
    }

    @Test
    fun `a non-namespaced element merely prefixed by a dangerous name is not stripped`() {
        // `<setter>` must NOT be mistaken for a namespaced `<set>`; the `:` is
        // required for the prefix branch and `\b` ends the name at the right place.
        val art = IconSvg.fromSvg("""<svg><setter x="1"/><path d="M3 3"/></svg>""")
        assertNotNull(art)
        assertTrue("a real element named like a prefix survives", art!!.body.contains("<setter"))
        assertTrue(art.body.contains("""d="M3 3""""))
    }

    @Test
    fun `style attributes are stripped wholesale (CSS injection vectors)`() {
        // `style=` is removed entirely (any value): inline CSS can carry
        // url(javascript:), expression(), and -moz-binding. Wireframe icons get
        // their color from fill/stroke attributes, not style, so this is safe.
        val js = IconSvg.fromSvg("""<svg><rect style="background:url(javascript:alert(1))"/><path d="M4 4"/></svg>""")
        assertNotNull(js)
        assertFalse(js!!.body.contains("style", ignoreCase = true))
        assertFalse(js.body.contains("javascript:", ignoreCase = true))

        val expr = IconSvg.fromSvg("""<svg><rect style="width:expression(alert(1))"/><path d="M5 5"/></svg>""")
        assertNotNull(expr)
        assertFalse(expr!!.body.contains("expression(", ignoreCase = true))

        val binding = IconSvg.fromSvg("""<svg><rect style="-moz-binding:url(http://x/x.xml#e)"/><path d="M6 6"/></svg>""")
        assertNotNull(binding)
        assertFalse(binding!!.body.contains("-moz-binding", ignoreCase = true))
    }

    @Test
    fun `a benign style attribute is also stripped (the wholesale v1 decision)`() {
        // DELIBERATE v1 decision: `style=` is dropped WHOLESALE, so even a harmless
        // `style="fill:red"` goes -- not just dangerous CSS. This is the documented
        // trade (icons rarely need inline style; killing the whole attribute avoids
        // a CSS-value sanitizer arms race). Asserted so it is a known choice, not an
        // accident: if a future change wants to allow benign style, it must update
        // this test on purpose. The element's geometry is unaffected.
        val art = IconSvg.fromSvg("""<svg><rect style="fill:red" x="1" y="2"/><path d="M9 9"/></svg>""")
        assertNotNull(art)
        assertFalse("even benign style= is removed", art!!.body.contains("style", ignoreCase = true))
        assertFalse(art.body.contains("fill:red"))
        // The non-style attributes and sibling geometry survive.
        assertTrue(art.body.contains("""x="1""""))
        assertTrue(art.body.contains("""d="M9 9""""))
    }

    @Test
    fun `a slash-separated style attribute is stripped`() {
        val art = IconSvg.fromSvg("""<svg><path d="M7 7"/style="x:y"></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("style", ignoreCase = true))
        assertTrue(art.body.contains("""d="M7 7""""))
    }

    @Test
    fun `presentation fill and stroke attributes survive the style strip`() {
        // Non-regression: dropping style= must NOT touch the fill/stroke/
        // currentColor attributes that carry an icon's actual color.
        val art = IconSvg.fromSvg("""<svg><path fill="currentColor" stroke="red" d="M8 8"/></svg>""")
        assertNotNull(art)
        assertTrue(art!!.body.contains("""fill="currentColor""""))
        assertTrue(art.body.contains("""stroke="red""""))
    }

    @Test
    fun `slash-separated event handlers cannot bypass stripping`() {
        // Inside a start tag the HTML tokenizer treats `/` as an attribute
        // separator, so `<path/onclick=...>` parses onclick as a live attribute.
        // A `\s`-only boundary would miss it; the [\s/] boundary must catch it.
        val art = IconSvg.fromSvg("""<svg><path d="M0 0"/onclick="alert(1)"></svg>""")
        assertNotNull(art)
        assertFalse("slash-separated onclick must be stripped", art!!.body.contains("onclick", ignoreCase = true))
        assertTrue(art.body.contains("""d="M0 0""""))
    }

    @Test
    fun `chained slash-separated event handlers are all stripped`() {
        val art = IconSvg.fromSvg("""<svg><rect onerror=boom()/onload=eek()/><path d="M1 1"/></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("onerror", ignoreCase = true))
        assertFalse(art.body.contains("onload", ignoreCase = true))
        assertTrue(art.body.contains("""d="M1 1""""))
    }

    @Test
    fun `a slash-chained external href cannot hide behind a leading hash href`() {
        // An unquoted href=#a/href=javascript:... must NOT keep the javascript:
        // part by hiding it inside the first value: the [\s/] boundary plus the
        // `/`-excluding unquoted value split them, so the # ref is kept and the
        // javascript: ref is removed.
        val art = IconSvg.fromSvg("""<svg><use href=#a/href=javascript:alert(1) /><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertFalse("javascript: must not survive", art!!.body.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `a legitimate self-closing path is not corrupted by the slash boundary`() {
        // The `/` in `/>` must not trip the handler/href rules (no `on`/`href`
        // follows it), so ordinary self-closing geometry survives intact.
        val art = IconSvg.fromSvg("""<svg><path d="M0 0h24"/><circle r="3"/></svg>""")
        assertNotNull(art)
        assertTrue(art!!.body.contains("""<path d="M0 0h24"/>"""))
        assertTrue(art.body.contains("""<circle r="3"/>"""))
    }

    @Test
    fun `javascript and data uri hrefs are stripped`() {
        val art = IconSvg.fromSvg(
            """<svg><a xlink:href="javascript:alert(1)"><path d="M0 0"/></a><image href="data:image/png;base64,AAAA"/></svg>""",
        )
        assertNotNull(art)
        assertFalse(art!!.body.contains("javascript:", ignoreCase = true))
        assertFalse(art.body.contains("data:image", ignoreCase = true))
    }

    @Test
    fun `comments are stripped`() {
        val art = IconSvg.fromSvg("""<svg><!-- secret --><path d="M0 0"/></svg>""")
        assertNotNull(art)
        assertFalse(art!!.body.contains("secret"))
        assertFalse(art.body.contains("<!--"))
    }

    @Test
    fun `uses the last closing svg tag for nested-looking content`() {
        // lastIndexOf("</svg>") is the boundary; inner text up to it is the body.
        val art = IconSvg.fromSvg("""<svg><g><path d="M1 1"/></g></svg>""")
        assertNotNull(art)
        assertTrue(art!!.body.contains("<g>"))
        assertTrue(art.body.contains("</g>"))
    }
}
