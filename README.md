# Cursive JS Support

A companion plugin for [Cursive](https://cursive-ide.com/) that brings JavaScript interop awareness to ClojureScript files. It provides completion, documentation, and go-to-declaration for `js/`, `(.method receiver)`, `(.-prop receiver)`, and shadow-cljs `(:require ["pkg" :as alias :refer […]])` forms. Completion is driven by a bundled snapshot of TypeScript's `lib.dom.d.ts` plus any `.d.ts` files found in your project's `node_modules` (including shadow-cljs `:npm-deps` packages and workspace-style `packages/*`).

## Requirements

- IntelliJ IDEA 2026.1 (build 261) or compatible
- The Cursive plugin installed in the IDE
- JDK 21 (for building)

## Build

```
./gradlew composedJar
```

The artifact lands in `build/libs/` (the composed jar bundles all plugin runtime dependencies — that's the file you install).

## Install

In the IDE: `Settings → Plugins → ⚙ → Install Plugin From Disk…` and select the composed jar from `build/libs/`. Restart when prompted.

## Develop

Run tests:

```
./gradlew test
```

Launch a sandbox IDE with the plugin loaded:

```
./gradlew runIde
```

The bundled browser symbol index is committed to `src/main/resources/js/browser-symbols.json.gz`. To regenerate it from the TypeScript `.d.ts` files (requires Node.js on `PATH`):

```
./gradlew generateBrowserSymbolsIndex
```
