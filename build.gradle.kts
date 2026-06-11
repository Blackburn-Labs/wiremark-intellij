import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
    }
}

// Plugin compatibility range. IPGP otherwise narrows the range to the build
// branch we compile against (2025.2 = 252.*), which is too restrictive.
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Provisional floor: 243 = the 2023.3 build branch. qa's verifyPlugin
            // run validates it at the end. The plugin uses only long-stable
            // platform/markdown APIs (JCEF preview, FileEditorProvider, the
            // markdown browser-preview EP), so a wide range is expected to hold.
            sinceBuild = "243"
            // Open-ended upper bound: an explicitly-null provider clears IPGP's
            // default untilBuild convention, so patchPluginXml omits the
            // until-build attribute entirely (no artificial upper cap).
            untilBuild = provider { null }
        }
    }
}

// ---------------------------------------------------------------------------
// wiremark browser bundle (option B; see wiremark-web/README.md and PLAN.md).
//
// wiremark-web/ is a small npm project that bundles a pinned @wiremark/core
// into a self-contained IIFE (global `wiremark`). We build it here so the
// plugin jar ships /web/wiremark.browser.js, loaded into JCEF at runtime.
//
// OPTION-A SWITCHOVER: when @wiremark/core ships a prebuilt browser bundle,
// only wiremark-web/build.mjs changes (copy instead of esbuild). This Gradle
// wiring, the resource root, and the smoke test stay the same.
// ---------------------------------------------------------------------------

val wiremarkWebDir = layout.projectDirectory.dir("wiremark-web")
val wiremarkBundleOut = layout.buildDirectory.file("generated/wiremarkWeb/web/wiremark.browser.js")
// Kept OUT of generated/wiremarkWeb so it is not picked up as a packaged resource.
val wiremarkSmokeMarker = layout.buildDirectory.file("generated/wiremarkWebTest/.smoke-test-passed")

// `npm` is `npm.cmd` on Windows; both resolve from PATH (incl. CI's setup-node).
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

// `npm ci`: reproducible install from the committed lockfile. Up-to-date keyed
// on the manifest + lockfile so it reruns only when deps change.
val wiremarkWebInstall by tasks.registering(Exec::class) {
    description = "Install wiremark-web npm dependencies (npm ci)."
    workingDir = wiremarkWebDir.asFile
    commandLine(npmCommand, "ci")

    inputs.file(wiremarkWebDir.file("package.json"))
    inputs.file(wiremarkWebDir.file("package-lock.json"))
    outputs.dir(wiremarkWebDir.dir("node_modules"))
}

// Build the IIFE bundle into the generated resource dir.
val buildWiremarkBundle by tasks.registering(Exec::class) {
    description = "Build the wiremark browser bundle (IIFE) into the generated resources."
    dependsOn(wiremarkWebInstall)
    workingDir = wiremarkWebDir.asFile

    // build.mjs takes the output path as argv[1]; it lives under build/ so a
    // clean wipes it. Gradle passes an absolute path.
    commandLine(npmCommand, "run", "build", "--", wiremarkBundleOut.get().asFile.absolutePath)

    // node_modules is wiremarkWebInstall's lockfile-keyed output (and we
    // dependsOn it), so a lockfile change already reruns this transitively.
    inputs.file(wiremarkWebDir.file("build.mjs"))
    inputs.dir(wiremarkWebDir.dir("node_modules"))
    outputs.file(wiremarkBundleOut)
}

// Smoke test: fail the build if the bundle is broken (node --test in wiremark-web).
val testWiremarkBundle by tasks.registering(Exec::class) {
    description = "Smoke-test the wiremark browser bundle (node --test)."
    dependsOn(wiremarkWebInstall)
    workingDir = wiremarkWebDir.asFile
    commandLine(npmCommand, "test")

    // node_modules is wiremarkWebInstall's lockfile-keyed output (and we
    // dependsOn it), so a lockfile change already reruns this transitively.
    inputs.file(wiremarkWebDir.file("build.mjs"))
    inputs.file(wiremarkWebDir.file("bundle.test.mjs"))
    inputs.dir(wiremarkWebDir.dir("node_modules"))
    // No file output of its own; key the up-to-date check on a marker so `check`
    // stays incremental.
    val marker = wiremarkSmokeMarker
    outputs.file(marker)
    doLast {
        marker.get().asFile.writeText("ok\n")
    }
}

// Add the generated dir (parent of web/) as a resource root so processResources
// and buildPlugin package web/wiremark.browser.js automatically.
sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/wiremarkWeb"))
    }
}

// processResources must wait for the bundle; `check` runs the smoke test.
tasks.named("processResources") {
    dependsOn(buildWiremarkBundle)
}
tasks.named("check") {
    dependsOn(testWiremarkBundle)
}

// ---------------------------------------------------------------------------
// dev3 (Part A): DOM-less tests for the markdown-preview glue script.
//
// Pure `node:test` (no npm deps, no install) against a vm sandbox with a tiny
// DOM shim -- exercises wiremark-glue.js idempotency, content-change re-render,
// error handling, and scroll-sync attribute carrying. Wired into `check`.
// ---------------------------------------------------------------------------
val glueTestFile = layout.projectDirectory.file("src/test/js/wiremark-glue.test.mjs")
val glueSourceFile = layout.projectDirectory.file("src/main/resources/web/wiremark-glue.js")
val glueTestMarker = layout.buildDirectory.file("generated/wiremarkGlueTest/.glue-test-passed")

val testWiremarkGlue by tasks.registering(Exec::class) {
    description = "Test the markdown-preview glue script (node --test)."
    workingDir = layout.projectDirectory.asFile
    commandLine("node", "--test", glueTestFile.asFile.absolutePath)

    inputs.file(glueTestFile)
    inputs.file(glueSourceFile)
    val marker = glueTestMarker
    outputs.file(marker)
    doLast {
        marker.get().asFile.writeText("ok\n")
    }
}
tasks.named("check") {
    dependsOn(testWiremarkGlue)
}

// ---------------------------------------------------------------------------
// dev2 (Part B): DOM-less tests for the split-editor preview entry script.
//
// Same pure `node:test` + vm-sandbox approach as the glue test above --
// exercises window.renderWiremark: SVG swap, soft diagnostics, the dev1
// WiremarkError contract (a thrown error is NOT instanceof Error), and the
// last-good-SVG-on-error behaviour. Wired into `check`.
// ---------------------------------------------------------------------------
val previewTestFile = layout.projectDirectory.file("src/test/js/wiremark-preview.test.mjs")
val previewSourceFile = layout.projectDirectory.file("src/main/resources/web/wiremark-preview.js")
val previewTestMarker = layout.buildDirectory.file("generated/wiremarkPreviewTest/.preview-test-passed")

val testWiremarkPreview by tasks.registering(Exec::class) {
    description = "Test the split-editor preview entry script (node --test)."
    workingDir = layout.projectDirectory.asFile
    commandLine("node", "--test", previewTestFile.asFile.absolutePath)

    inputs.file(previewTestFile)
    inputs.file(previewSourceFile)
    val marker = previewTestMarker
    outputs.file(marker)
    doLast {
        marker.get().asFile.writeText("ok\n")
    }
}
tasks.named("check") {
    dependsOn(testWiremarkPreview)
}
