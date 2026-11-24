# ADR 0001 — Modular monolith: one deployable for SPA + API

- Status: Accepted · Date: 2026-11-24

## Context
The BRD describes one application with four role-based experiences. The team is small, and Claude generates all of the code. Billing, CRM, payment and provisioning integrations are not available yet (BRD §14 #9/#10).

## Decision
Build a **single Spring Boot application**. It serves the REST API under `/api/v1/**` and the built React SPA from `classpath:/static`. The backend is organized by feature (`customer`, `servicecase`, `payment`, …). External systems sit behind ports (`PaymentGateway`, `ProvisioningClient`, `NotificationSender`), and the first implementations are mock adapters.

## Consequences
- One build, one container and one database, so CI and local runs stay simple.
- Feature packages and ArchUnit rules keep module boundaries, so a feature can be split out later.
- Same origin means session cookies and CSRF work with no CORS setup.
