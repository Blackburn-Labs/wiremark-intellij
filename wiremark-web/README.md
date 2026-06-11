# wiremark-web

Builds `wiremark.browser.js`: a self-contained IIFE bundle of
[`@wiremark/core`](https://www.npmjs.com/package/@wiremark/core) that exposes
its public API as the global `wiremark`. The IntelliJ plugin loads this bundle
into JCEF (embedded Chromium) to render wireframes client-side -- in the
Markdown preview and in the `*.wiremark` split editor. No Node runtime is
required on the user's machine; the bundle runs entirely in the browser engine.

This is "option B" from `tasks/PLAN.md`: because `@wiremark/core` does not yet
ship a prebuilt browser bundle, we bundle a pinned copy here with esbuild.

## Runtime API contract (read this if you call the bundle)

The bundle assigns a global `wiremark`. The surface the plugin uses:

- `wiremark.render(source)` -> `{ svg, diagnostics }`. `svg` is a string;
  `diagnostics` is an array (possibly empty). SOFT problems (unknown icon,
  missing link target) do NOT throw -- they still render an SVG and report the
  problem in `diagnostics`.
- HARD parse problems (a top-level node that is not a `Wireframe` frame, tabs in
  indentation, unknown component, ...) THROW.
- `wiremark.WiremarkError` is exported (a constructor function).

IMPORTANT for callers (JS glue AND the Kotlin-side JS you inject): the thrown
`WiremarkError` is **not** a standard `Error` -- `e instanceof Error === false`.
It has `e.name === 'WiremarkError'` and `e.message`. So branch on
`e.name === 'WiremarkError'` (or `e instanceof wiremark.WiremarkError`), NOT
`e instanceof Error` -- a generic `if (e instanceof Error)` will misclassify a
hard parse error and silently swallow the message your error UI should show.

```js
try {
  const { svg, diagnostics } = wiremark.render(src);
  // render svg; surface diagnostics (soft warnings)
} catch (e) {
  if (e && e.name === 'WiremarkError') {
    showError(e.message);   // hard parse error -- expected, show it
  } else {
    throw e;                // anything else is a real bug
  }
}
```

## Layout

- `package.json` / `package-lock.json` -- pin `@wiremark/core` and `esbuild`.
- `build.mjs` -- esbuild driver. `node build.mjs <outfile>` writes the IIFE.
- `bundle.test.mjs` -- `node --test` smoke test: builds the bundle, evaluates
  it in a bare `vm` context (no DOM), asserts `wiremark.render(src)` returns
  `{ svg, diagnostics }`.

## Build manually

```sh
npm ci
npm run build      # -> dist/wiremark.browser.js
npm test
```

In a normal build you do not run this directly: the Gradle `buildWiremarkBundle`
task runs `npm ci` + `node build.mjs` and writes the bundle into
`build/generated/wiremarkWeb/web/wiremark.browser.js`, which is wired in as a
resource root so `buildPlugin` packages it at the runtime path
`/web/wiremark.browser.js`.

## Switching to upstream (option A)

When `@wiremark/core` publishes a prebuilt `dist/wiremark.browser.js`, drop the
esbuild step: have `build.mjs` copy
`node_modules/@wiremark/core/dist/wiremark.browser.js` to the output path
instead of bundling. The Gradle wiring, resource root, smoke test, and runtime
path all stay the same. See the comment at the top of `build.mjs`.
