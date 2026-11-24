# <NN name> — frontend design

- Status: **Proposed** (awaiting approval) · Date: <yyyy-mm-dd>
- BRD: <IDs> · Backend contract: [`openapi.yaml`](openapi.yaml)

## 1. Scope
**In:** … **Out:** …

## 2. Routes and navigation
| Route | Screen | Required permission | Nav entry (role) |
|---|---|---|---|

## 3. Screens
### <Screen name> (<BRD ID>)
- Layout sketch (ASCII or a reference to `docs/brd/images/…`)
- Components: …
- Data: `useXxxQuery` → `GET /api/v1/…` · mutations: …
- States: loading · empty · error · validation · success (XC-03)
- Accessibility notes (XC-08)

## 4. Shared components introduced
| Component | Props | Used by |
|---|---|---|

## 5. Client state
| Store (Zustand) | Holds | Why not server state |
|---|---|---|

## 6. Forms and validation
| Form | Zod schema fields | Server error mapping |
|---|---|---|

## 7. Test plan
| Test (Vitest/RTL/Playwright) | Proves | BRD |
|---|---|---|

## 8. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
