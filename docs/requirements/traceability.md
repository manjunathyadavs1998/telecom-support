# Requirements traceability matrix

Generated and maintained with the `brd-to-stories` skill. Source: [`docs/brd/brd.md`](../brd/brd.md).
Status: `Planned` · `In progress` · `Done` · `Deferred (reason)`.

## Functional requirements

| ID | Title | Phase | Branch | Backend | Frontend | Tests | Status |
|---|---|---|---|---|---|---|---|
| CUS-01 | Customer dashboard | 2 | ai/04-subagents | | | | Planned |
| CUS-02 | Profile management (+OTP) | 2 | ai/04-subagents | | | | Planned |
| CUS-03 | Plan summary / upgrade | 2 | ai/04-subagents | | | | Planned |
| CUS-04 | Promotions | 2 | ai/04-subagents | | | | Planned |
| CUS-05 | Service request list | 2 | ai/04-subagents | | | | Planned |
| CUS-06 | Create service request | 2 | ai/04-subagents | | | | Planned |
| CUS-07 | Payment history | 2 | ai/04-subagents | | | | Planned |
| CUS-08 | Make payment | 2 | ai/04-subagents | | | | Planned |
| INB-01 | Customer lookup & verification | 3 | ai/05-mcp | | | | Planned |
| INB-02 | Track customer cases | 3 | ai/05-mcp | | | | Planned |
| INB-03 | Raise request on behalf | 3 | ai/05-mcp | | | | Planned |
| INB-04 | Temporary reactivation (24h) | 3 | ai/05-mcp | | | | Planned |
| OUT-01 | Case queue + KPIs | 4 | ai/06-workflow-commands | | | | Planned |
| OUT-02 | Advanced filters | 4 | ai/06-workflow-commands | | | | Planned |
| OUT-03 | Case assignment (atomic claim) | 4 | ai/06-workflow-commands | | | | Planned |
| OUT-04 | Case details & activity | 4 | ai/06-workflow-commands | | | | Planned |
| OUT-05 | Status & closure state machine | 4 | ai/06-workflow-commands | | | | Planned |
| OUT-06 | Bookmarks & follow-up | 4 | ai/06-workflow-commands | | | | Planned |
| BUS-01 | Daily case dashboard | 5 | ai/07-ci-ai-review | | | | Planned |
| BUS-02 | Category & severity analysis | 5 | ai/07-ci-ai-review | | | | Planned |
| BUS-03 | Audit & export | 5 | ai/07-ci-ai-review | | | | Planned |

## Business rules, cross-cutting and NFRs

| ID | Rule (short) | Phase | Implementation | Tests | Status |
|---|---|---|---|---|---|
| BR-01 | Unique non-reusable case reference | 2 | | | Planned |
| BR-02 | Case has account + channel | 2 | | | Planned |
| BR-03 | Status/assignment changes audited | 4 | | | Planned |
| BR-04 | Public vs internal notes | 4 | | | Planned |
| BR-05 | 3-month default is display only | 2 | | | Planned |
| BR-06 | Bounded date filters + paging | 2 | | | Planned |
| BR-07 | Configurable SLA by category | 2 | | | Planned |
| BR-08 | Payment/provisioning confirmed server-side | 2 | | | Planned |
| BR-09 | Request ↔ internal work items | — | | | Deferred (future operational design) |
| BR-10 | Server-side authorization | 1 | | | Planned |
| XC-01 | Role-specific navigation | 1 | | | Planned |
| XC-02 | Restricted routes/APIs denied | 1 | | | Planned |
| XC-03 | Loading/empty/error/validation states | 1 | | | Planned |
| XC-04 | Server-side paging keeps filters | 2 | | | Planned |
| XC-05 | Validated dates, documented TZ | 2 | | | Planned |
| XC-06 | High-risk actions confirmed + audited | 1 | | | Planned |
| XC-07 | Success only after backend confirmation | 2 | | | Planned |
| XC-08 | Keyboard + breakpoints | 6 | | | Planned |
| XC-09 | Automated tests for critical flows | 6 | | | Planned |
| NFR-SEC | Security controls | 1/6 | | | Planned |
| NFR-REL | Idempotency | 2 | | | Planned |
| NFR-OBS | Correlation IDs, metrics, logs | 1/6 | | | Planned |
| NFR-A11Y | Accessibility | 6 | | | Planned |
| NFR-PERF | Performance budgets | 6 | | | Planned |

## Open decisions (BRD §14) — defaults adopted
See the table in `docs/brd/brd.md` §14. Summary: account→many subscriptions; inbound search open, protected actions need verification; outbound team + assigned queue; system severity with authorized override; 7-day reopen window; public vs internal status; mock payment gateway; aggregate-only analytics unless `reports.export.pii`; in-app + email notifications.
