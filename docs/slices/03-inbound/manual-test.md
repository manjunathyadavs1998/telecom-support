# Slice 03 inbound — manual test guide (FE + BE on the dev JAR)

Tests are deferred by the user (2026-11-27); this is the hands-on check, feature by feature. Design: `docs/design/03-inbound/frontend.md`.

## Start
```bash
docker compose up -d mysql
bash scripts/check-frontend.sh build                       # SPA → frontend/dist
(cd backend && ./mvnw -o -Pfrontend -DskipTests package)  # JAR with the SPA (-o: offline, deps are cached)
set -a; . ./.env; set +a
java -jar backend/target/support.jar --spring.profiles.active=dev
```
Open http://localhost:8080. Password for every seed user: `Password@123`. Dev OTP code: `123456`.

| User | Can |
|---|---|
| IN001 | Inbound agent: search, verify, view, raise requests; reactivation **history only** |
| IN002 | Inbound agent + reactivation approver: everything incl. restore service |
| CUS001 | Customer: must be refused on `/inbound` |

| Search | Customer | Use for | KBA answers |
|---|---|---|---|
| `9800000001` | 1 Asha Rao, active | overview, billing, cases (incl. INTERNAL notes), raise request | — (OTP) |
| `9800000006` | 6 Nikhil Verma, suspended NON_PAYMENT, 2 lines | eligible reactivation (IN002), KBA | `110017` + `nikhil.verma@example.test` |
| `9800000007` | 7 Farah Khan, suspended FRAUD | "can never be overridden" | `302001` + `9800000017` |
| `9800000008` | 8 Arjun Das, inactive customer | KBA unavailable → OTP only | — |

Tip: keep DevTools → Network open. The mobile number must never appear in a URL; before verification only `identity-checks/current` is called.

## 1. Routes and access (F2)
- [ ] CUS001 → `/inbound` shows Forbidden. IN001 sees "Customer search" in the sidebar.
- [ ] `/inbound/customers/x` and `/inbound/customers/1/cases/PAY-20261125-000007` → Not Found, no API call.

## 2. Customer search (F5)
- [ ] `9800000001` (or `+919800000001`, `98000 00001`) → masked card (name, number, ref, three status badges), "Verify identity".
- [ ] `9999999999` → "No active line found for this number". `123` → field error, no request.
- [ ] 31 searches within a minute → "Too many searches", countdown, Search disabled.

## 3. Verify identity (F6)
- [ ] Customer 1, OTP: "Code sent to +91******0001 · expires in 9:5x"; `000000` → "Incorrect code. 4 attempts left."; `123456` → toast "Customer verified for 30 minutes", workspace opens.
- [ ] "Send a new code" is disabled for 30 s after each send.
- [ ] Customer 6, security questions: a wrong postal code → "The answers don't match our records. N attempts left this hour." (never says which); the seed answers → verified.
- [ ] Customer 8, security questions → switches to the code option with a notice.
- [ ] Reload the browser on a workspace whose search was > 15 min ago (or a new tab after logout/login) → the panel starts with a number search; a different customer's number → "This number belongs to a different customer".

## 4. Workspace frame (F7)
- [ ] Header: masked name before verification, full name + account ref + suspension reason/since after. "Verified · N min left" badge.
- [ ] Tabs Overview · Billing · Cases · Reactivation keep the frame (badge keeps counting, no re-verify) when switching.
- [ ] At ≤ 5 min the badge turns amber and "Verify again" appears; the dialog verifies without losing anything.
- [ ] At 0 (or after `DELETE` of the check in the DB) a modal "Verification ended" opens over the page; typed form text is kept; "Leave this customer" with unsaved input asks first.

## 5. Overview (F8)
- [ ] Customer 1: Profile (no billing address; email/second mobile "Verified"/"Not verified"), Lines and plans, Open bills, Recent requests (5) — each with its own loading/empty/error.

## 6. Billing (F9)
- [ ] Invoices table (latest 12). Payments: last 3 months by default; custom dates; page 2 keeps the dates in the URL; reload keeps them. No receipt/pay buttons.

## 7. Cases list (F10)
- [ ] Status checkboxes and Sort update the URL (`?status=…&sort=…`); page 2, reload and Back keep them. Default sort is not in the URL.
- [ ] Status column shows the internal label + "Customer sees: …"; owner "Unassigned" when none; channel Portal / Inbound.
- [ ] `?status=BOGUS` → "Some filters in the link were invalid and were reset".

## 8. Case detail (F11)
- [ ] Customer 1, an INTERNAL-note case (`R__03_inbound_e2e.sql`, loaded in dev) → amber entry with lock icon "Internal — never shown to the customer"; others "Visible to customer".
- [ ] Possible duplicates list or "No related requests found"; tab title "Request SR-…".
- [ ] A reference of another customer → "Request not found for this customer" + Back to cases.

## 9. Raise request (F12)
- [ ] IN002, customer 1: category groups "Customer categories" / "Internal categories" ("(Internal)" suffix); subcategory appears only when the category has some.
- [ ] Priority text "(from the category)"; "Override priority" → Priority + Reason required; choosing the derived value is refused.
- [ ] Email is disabled when the customer's email is unverified.
- [ ] Submit → "Request SR-… created. Read this reference to the customer." + Copy reference ("Copied"); "View request" opens it; the Cases list shows it.
- [ ] Double-click Submit creates one request.

## 10. Reactivation (F13, F14)
- [ ] IN001, any customer → "Only reactivation approvers can restore service" + History only.
- [ ] IN002, customer 7 → "Suspended for Fraud. This can never be overridden." (red, no form).
- [ ] IN002, customer 6 → facts (Non-payment, 2 numbers, max 24 h) + form. Hours 25 → "At most 24 hours". Reason < 10 chars → field error.
- [ ] Request restoration → confirm "Restore 2 numbers for 24 hours?" → "Service restored until … (REA-…). The customer will be notified by SMS."; History shows it "Restored · Until …"; eligibility now lists "A restoration is already open: REA-…" (link jumps to the row).
- [ ] Customer 1 (not suspended) → "The account is not suspended, so there is nothing to restore."

## 11. Logout and privacy
- [ ] Logout, log in again, open a customer → starts at the search step (tokens and cached data cleared). Same in another tab when logging out in one tab.

Report anything off with the page, the user, the customer and the correlation id from the error box.
