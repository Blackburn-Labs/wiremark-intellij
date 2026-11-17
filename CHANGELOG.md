<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Wiremark Changelog

## [Unreleased]

### Added

- Theme-aware preview: the rendered wireframe now follows the active IDE theme, displaying with a dark palette under dark IDE themes instead of always rendering on a light card. This applies to both the `*.wiremark` split editor and the Markdown preview, and re-renders live when you switch the IDE look-and-feel. (Completes the dark-theme support anticipated in 0.1.1, now that the bundled engine's `theme` render option is wired to IDE-theme detection.)
- Cross-block backgrounds in the Markdown preview: a `Wireframe ... background=#id` in one fenced `wiremark` / `wireframe` block can now use a wireframe declared in a different block of the same document as its background (and resolve an `anchor=#id` against it). Previously each fence rendered in isolation, so such a reference produced a "background frame not found" warning and rendered without the background. Self-contained documents are unaffected and render exactly as before.

### Changed

- Updated the bundled `@wiremark/core` rendering engine to 0.2.0 (via 0.0.7 and 0.1.0). Wireframe text now renders in Comic Neue, embedded in the SVG so it looks the same whether or not the font is installed, and a document-level `Font "Family" [width=N] [leading=N]` line selects a custom font family. Also brings keyless `Wireframe` direction such as `Wireframe ... row`, a universal `background=` / `denseBackground` / `opaque` backdrop on elements, `Img` alt-text captions, a `MenuItem` `icon`, and connector-routing/spacing refinements.
- The `*.wiremark` preview's Content Security Policy now allows `data:` fonts (`font-src data:`) so the embedded font loads; it remains closed to network sources. The Markdown preview's own policy already allowed them.

## [0.1.1]

### Changed

- Updated the bundled `@wiremark/core` rendering engine to 0.0.6 (rendering fixes; the engine now supports a dark-theme palette through a `theme` render option, which the plugin will use once IDE-theme detection lands).

### Fixed

- Hardened a flaky platform test in the icon-bridge path-traversal check (test-only; no change to plugin behavior).

## [0.1.0]

### Added

- Initial release.
- Live split text/preview editor for `*.wiremark` files, with the rendered wireframe updating as you type.
- Wireframe rendering of fenced `wireframe` / `wiremark` code blocks in the Markdown preview.
- Syntax highlighting for `.wiremark` files and inside Markdown fences.
- Diagnostics footer and error banner in both preview surfaces.
- Icon `src=` bridge: resolves project-scoped local icon files referenced from an `Icons` block (path-traversal-safe and sanitized) for the split-editor preview.
- Marketplace assets: plugin icons and example files.
