# Testing Cursive JS Support 1.0

## Automated checks

Run the complete suite:

```bash
./gradlew test detekt
```

The suite covers build-profile discovery, Node package layouts, TypeScript type parsing,
inference, completion context, inspections, Source Map v3 VLQ decoding, indexed sections,
inline maps, `sourcesContent`, path overrides, and generated/original reverse lookup.

Validate the packaged plugin against the IntelliJ API:

```bash
./gradlew verifyPlugin
```

## Try editing features

1. Launch the sandbox with `./gradlew runIde`.
2. Open `test-fixtures/shadow-browser` as a project and allow Cursive to index it.
3. Open `src/fixture/core.cljs` and try:
   - Type `js/` to complete browser globals.
   - Type `(.add` inside a form to complete methods.
   - Put the caret in `.addEventListener` and invoke Parameter Info (`⌘P` on macOS).
   - Hover `js/document` or invoke Quick Documentation.
   - Change `.addEventListener` to `.-addEventListener`; the inspection should report a
     method/property mismatch and `⌥Enter` should restore method syntax.
   - Change `getElementById` to `getElementByID`; the inspection should offer the closest name.
   - Invoke Go to Declaration (`⌘B`) on a browser global or member.
4. Open **View → Tool Windows → ClojureScript JS**. Confirm the `shadow:app` profile is listed,
   the index is `READY`, and npm package/index diagnostics are visible.

## Browser source-map debugging

1. In `test-fixtures/shadow-browser`, run `npm install` once.
2. In the sandbox IDE choose **Run → Edit Configurations…**, add
   **ClojureScript JavaScript Debug → Browser**, and select `shadow:app`.
3. Leave **Start build/watch process before attaching** enabled and start Debug.
4. Put breakpoints on the `let` in `calculate` and the `throw` in `trigger!`.
5. When `http://localhost:8020` opens, click **Trigger mapped exception**.
6. Verify that IntelliJ stops in `core.cljs`, shows `value`, `doubled`, and `answer`, steps
   through CLJS source, and displays a mapped exception stack. The generated JavaScript frame
   remains available when a specific segment has no original mapping.

If a breakpoint remains unbound, open the **ClojureScript JS** tool window and confirm that
`public/js` contains `.map` files. Reindex, rebuild, and check any path mapping warning.

## Node source-map debugging

From `test-fixtures/cljs-main-node`:

```bash
clojure -M -m cljs.main -co dev.cljs.edn -c fixture.core
node --inspect-brk=9229 out/main.js
```

Create a **ClojureScript JavaScript Debug → Node** configuration for `cljs-main:dev`, disable
automatic build startup, and use host `127.0.0.1`, port `9229`. Start Debug, resume from Node's initial
pause, and verify the `calculate` breakpoint and thrown exception map to `src/fixture/core.cljs`.

Alternatively, stop the manually launched process and leave automatic startup enabled: the Node
factory runs the compiler, launches `node --inspect-brk`, attaches the Node debugger, and owns both
processes for the lifetime of the debug session.

## Figwheel profile and browser smoke test

Open `test-fixtures/figwheel-browser`. The status tool window should discover `figwheel:dev`
and no duplicate `cljs-main:dev` profile. Run:

```bash
clojure -M -m figwheel.main -b dev -r
```

Attach a ClojureScript JavaScript Debug **Browser** configuration to the served URL and repeat the mapped
breakpoint test after adding a breakpoint to `init`.
