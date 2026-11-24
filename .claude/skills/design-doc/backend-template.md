# <NN name> — backend design

- Status: **Proposed** (awaiting approval) · Date: <yyyy-mm-dd>
- BRD: <IDs>
- Conventions: [`../00-overview.md`](../00-overview.md) · Contract: [`openapi.yaml`](openapi.yaml)

## 1. Scope
**In:** … **Out (later slices):** …

## 2. Data model (Flyway V<n>…)
```mermaid
erDiagram
```
| Migration | Content |
|---|---|
Indexes and constraints: …

## 3. Business rules and states
| Rule | Enforced where | BRD |
|---|---|---|
(state diagram if the entity has a lifecycle)

## 4. Authorization and scope
| Endpoint/action | Permission | Record scope rule |
|---|---|---|

## 5. Endpoints (details in openapi.yaml)
| Method | Path | Permission | Idempotent | Response / errors |
|---|---|---|---|---|

## 6. Components
| Component | Responsibility |
|---|---|

## 7. Audit actions
`ACTION_NAME` (details: …)

## 8. Integrations / ports
| Port | Mock behaviour | Failure mode (NFR-AVAIL) |
|---|---|---|

## 9. Test plan
| Test | Proves | BRD |
|---|---|---|
(every protected endpoint has a forbidden-role case)

## 10. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
