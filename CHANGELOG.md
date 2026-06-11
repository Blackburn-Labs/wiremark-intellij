<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Wiremark Changelog

## [Unreleased]

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
