# Wiremark for JetBrains IDEs

Render [wiremark](https://github.com/Blackburn-Labs/wiremark) wireframe diagrams
directly inside IntelliJ IDEA, WebStorm, PyCharm, and every other JetBrains IDE.

Wiremark is a concise, plain-text wireframing language (think "YAML-flavored
Material UI") that renders to hand-drawn, Balsamiq-style SVG mockups. This plugin
brings that rendering into the IDE in two places:

1. **Standalone `*.wiremark` files** open in a split text/preview editor with a
   live SVG that re-renders as you type -- the same editing experience the IDE
   gives Markdown and SVG files.
2. **Fenced code blocks in Markdown** -- a ` ```wireframe ` (or ` ```wiremark `)
   block in any `.md` file renders as a wireframe in the Markdown preview, the
   same way Mermaid diagrams do.

Everything renders **inside the IDE** -- no Node.js, no external tools, no network
calls. (See "How it works" for why.)

---

## Features

- Live split editor for `.wiremark` files (text | split | preview toolbar).
- Wireframe rendering in the Markdown preview for `wireframe` / `wiremark` fences.
- Syntax highlighting for `.wiremark` files and inside Markdown fences.
- Soft-diagnostics footer and hard-error banner in both preview surfaces.
- Multi-frame files render as a flow chart with connectors automatically.
- Local icon files referenced from an `Icons` block (`src=`) are resolved and
  rendered in the split-editor preview (sanitized; project-scoped).

---

## How it works

The Markdown preview in JetBrains IDEs is a JCEF (embedded Chromium) panel, and
both the Markdown plugin and our own split editor can host JCEF. `@wiremark/core`
is pure, browser-safe JavaScript whose `render(src)` is synchronous and returns
`{ svg, diagnostics }`. So the plugin bundles core into a self-contained browser
script and **does all rendering client-side in Chromium** -- which is why no
Node.js or network access is needed on the user's machine. Both integration
points share the same bundle.

- `wiremark-web/` is a tiny npm project that pins `@wiremark/core` and uses
  esbuild to produce an IIFE bundle (`window.wiremark`). A Gradle task builds it
  at compile time into the plugin's resources; a Node smoke test fails the build
  if the bundle is broken.
- The split editor (`editor/`) hosts a `JBCefBrowser`, debounces document edits
  (~250 ms), and pushes the source into the page via `executeJavaScript`.
- The Markdown integration (`markdown/`) injects the bundle plus a small
  observer-driven glue script that finds wireframe fences and replaces them with
  the rendered SVG, idempotently and live as you type.

---

## Project structure

```
build.gradle.kts                 Gradle build; also builds the browser bundle + runs JS tests
settings.gradle.kts
gradle.properties                pluginGroup/version, sinceBuild floor
wiremark-web/                    npm project that bundles @wiremark/core (option B)
  build.mjs                        esbuild driver -> build/generated/.../web/wiremark.browser.js
  bundle.test.mjs                  node --test smoke test for the bundle
examples/                        demo fixtures (also handy for manual testing)
  demo.wiremark                    single-frame example
  flow.wiremark                    multi-frame flow with connectors
  demo.md                          Markdown with wireframe + wiremark fences
src/main/kotlin/dev/wiremark/intellij/
  lang/        WiremarkLanguage, WiremarkFileType, lexer + syntax highlighter, file-type icon
  editor/      split text/preview FileEditor, JCEF preview, debounced live update
  markdown/    Markdown-preview browser extension + fence language injection
  icons/       Icons-block src= bridge: scan, resolve (project-scoped), sanitize, ship to preview
src/main/resources/
  META-INF/plugin.xml              plugin id/name/vendor + extension registrations
  META-INF/wiremark-markdown.xml   Markdown-only extensions (optional dependency)
  web/                             preview HTML shell, glue, shared UI css/js (generated bundle added at build time)
  icons/                           .wiremark file-type icon (light/dark)
src/test/kotlin/...                JUnit + IntelliJ platform tests
src/test/js/...                    node --test suites for the browser-side JS
```

---

## Prerequisites

- **JDK 17+ to run Gradle.** This repo's Gradle (9.x) will not run on Java 8. On
  macOS with Homebrew that usually means prefixing commands with
  `JAVA_HOME=/opt/homebrew/opt/openjdk`. The Kotlin/Java compile toolchain (JDK 21)
  is provisioned automatically by Gradle's foojay resolver -- you only need a
  modern JDK to *launch* Gradle.
- **Node.js + npm** (for building the browser bundle and running the JS tests).
  Available on PATH; CI installs them automatically.

> Tip: if Gradle fails with "A problem occurred starting process 'command npm'",
> the Gradle daemon did not inherit your shell PATH. Run with
> `JAVA_HOME=/opt/homebrew/opt/openjdk PATH="/opt/homebrew/bin:$PATH" ./gradlew ...`.

---

## How to test it (new to IntelliJ plugins? start here)

There are two ways to try the plugin. The first is the standard plugin-dev loop
and is completely isolated from your real IDE.

### Option 1: run a sandbox IDE (recommended)

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew runIde
```

This launches a **separate** IntelliJ IDEA with the plugin pre-installed, using
its own throwaway config under `build/idea-sandbox`. It cannot touch your real
IDE's settings. If you open this project in IntelliJ IDEA, the bundled
**"Run Plugin"** run configuration (in the toolbar) does the same thing -- just
set Settings > Build Tools > Gradle > "Gradle JVM" to a JDK 17+ first.

Once it opens, try the fixtures in `examples/`:

- Open `examples/demo.wiremark` -> it opens in a split editor; edit the text and
  the preview re-renders after ~250 ms. Type something invalid (e.g. a stray tab)
  to see the error banner appear *over* the last good render rather than blanking.
- Open `examples/flow.wiremark` -> a multi-frame file rendered as a flow chart
  with connectors.
- Open `examples/demo.md` and turn on the Markdown preview (the split/preview
  icon top-right) -> the ` ```wireframe ` and ` ```wiremark ` fences render as
  wireframes. `.wiremark` files and those fences also get syntax highlighting in
  the editor pane.

### Option 2: install into your own WebStorm / IntelliJ

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew buildPlugin
```

This produces `build/distributions/wiremark-intellij-<version>.zip`. In your IDE:
**Settings > Plugins > gear icon > Install Plugin from Disk...**, pick the zip,
and restart. Uninstall from the same screen. Your IDE must be **2024.3 or newer**
(the `sinceBuild` floor); the Markdown feature needs the bundled Markdown plugin
enabled (it is, by default, in WebStorm and IntelliJ).

### Run the automated tests

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew check
```

This runs both test layers:

- **Kotlin / IntelliJ platform tests** (`src/test/kotlin/...`) -- file-type and
  extension registration, the JS-payload builders, the lexer (edge cases:
  malformed input, unicode, unterminated strings), the icon scanner/sanitizer
  (path-traversal and XSS defenses), etc.
- **Node tests** (`src/test/js/...`, plus `wiremark-web/bundle.test.mjs`) -- the
  browser-side glue, preview, shared UI helpers, and a smoke test that the bundle
  actually renders.

To produce the distributable and (optionally) run the JetBrains Plugin Verifier
against the declared compatibility range:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew buildPlugin
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew verifyPlugin   # heavy: downloads several IDEs
```

---

## Compatibility

- Built against IntelliJ IDEA 2025.2; compatible from **build 243 (2024.3)**
  upward, with an open upper bound. Works across all JetBrains IDEs that bundle
  JCEF and the Markdown plugin (which is platform-wide).

---

## Publishing

Releases go to the JetBrains Marketplace via the GitHub Actions workflows that
shipped with the template (build/verify on every push and PR; sign + publish on a
GitHub Release). Marketplace uploads must be signed; the first upload is manual.
The full checklist (signing keys, plugin-ID lock, moderation) lives in the
project plan.

---

## License

MIT (c) Blackburn Labs. See [LICENSE](LICENSE).
