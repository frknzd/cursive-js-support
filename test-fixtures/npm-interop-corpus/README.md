# npm interop compatibility corpus

This fixture exercises 20 runtime packages (plus five `@types` providers) chosen to cover the
major JavaScript/TypeScript package shapes consumed by ClojureScript:

| Shape | Packages |
|---|---|
| ESM-only and default exports | `chalk`, `node-fetch` |
| CommonJS and `export =` declarations | `lodash`, `express`, `ws` |
| Dual ESM/CommonJS conditional exports | `axios`, `diff`, `uuid`, `commander` |
| Large named/re-export surfaces | `date-fns`, `d3`, `rxjs` |
| Scoped packages and deep generics | `@reduxjs/toolkit`, `zod`, `ajv` |
| JSX/declaration namespaces and merging | `react`, `react-router-dom` |
| Classes, constructors, and large member graphs | `three`, `typescript` |
| Tooling packages and public subpaths | `vite`, `rxjs`, `zod`, `date-fns` |

Install the pinned lockfile and run the opt-in audit:

```bash
npm ci --prefix test-fixtures/npm-interop-corpus
./gradlew npmInteropAudit
```

The audit compares the plugin's deterministic declaration model with the exports reported by the
same TypeScript compiler API used by official JS/TS tooling. It also checks representative exports,
call signatures, member completion, documentation, and declaration locations. IntelliJ PSI remains
the primary source at editor time; this corpus guards the deterministic fallback and merge layer.

Public subpath coverage includes Toolkit Query, Ajv 2020, `date-fns/addDays`, Lodash FP,
`react/jsx-runtime`, `rxjs/operators`, Vite's module runner, and Zod v4.
