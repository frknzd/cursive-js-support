# npm interoperability compatibility audit

The real-package audit compares Cursive JS Support with the TypeScript compiler's module checker
under both ESM import and CommonJS require conditions. The editor continues to use IntelliJ's
JavaScript/TypeScript PSI first; the deterministic declaration model is checked independently so a
partial live result cannot hide missing data.

## Current corpus

| Package shape | Packages | Runtime exports checked |
|---|---|---:|
| Scoped packages, generics, conditional entry points | `@reduxjs/toolkit`, `ajv`, `zod` | 313 |
| Dual ESM/CommonJS | `axios`, `commander`, `diff`, `uuid` | 90 |
| ESM-only/default exports | `chalk`, `node-fetch` | 27 |
| CommonJS and `export =` namespaces | `express`, `lodash`, `ws`, `react` | 383 |
| Large barrel/re-export surfaces | `d3`, `date-fns`, `react-router-dom`, `rxjs` | 1,130 |
| Classes, constructors, JS inference, tooling APIs | `three`, `typescript`, `vite` | 503 |

Eight public subpaths are checked separately: `@reduxjs/toolkit/query`, `ajv/dist/2020`,
`date-fns/addDays`, `lodash/fp`, `react/jsx-runtime`, `rxjs/operators`, `vite/module-runner`, and
`zod/v4`.

The pinned corpus currently verifies:

- 3,231 runtime exports used for import and alias completion;
- 4,816 call overloads used by hover and parameter information;
- 465 construct signatures and 5,656 member-completion names;
- 1,484 documented exports used by Quick Documentation;
- 3,024 TypeScript declaration targets used by Go to Declaration, with synthesized CommonJS
  targets where TypeScript itself reports no concrete declaration.

Run the audit with:

```bash
npm ci --prefix test-fixtures/npm-interop-corpus
./gradlew npmInteropAudit
```

"Complete" here means parity with statically observable JavaScript/TypeScript semantics. Properties
created only by executing arbitrary package code cannot be enumerated safely by any static JS/TS
tool. JavaScript-only packages are checked directly from every runtime condition entry; Three.js is
kept deliberately free of `@types/three` in this corpus to exercise that path.
