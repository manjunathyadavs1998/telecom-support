# UI refresh — visual and motion design (frontend only)

- Status: **Approved** (rev 1) · Date: 2026-11-28 · approved by user 2026-11-28: light only (U7 skipped), keep the blue brand, start before the docs PR merges · Requested by the user: pause feature work and refactor the frontend incrementally
  toward the look of `manjunathyadavs1998/DSA-Visualizer` (`app/`), which feels cleaner and smoother.
- Amends: [`01-foundation/frontend.md`](01-foundation/frontend.md) §8 (design system) and its OQ 6 (dark theme). Every screen
  design (02, 03, 04) is unchanged in behaviour, copy, routes, states and API use: this changes **presentation only**.
- BRD: XC-03 (states), XC-08 (keyboard, responsive), NFR-A11Y, NFR-RESP, NFR-MAINT.
- Process: tests stay deferred (user, 2026-11-27); each increment is done when `check-frontend.sh` typecheck + lint + build are green
  and the named screens are checked on the dev JAR.

## 1. What makes the reference feel smoother (and what we adopt)
| Reference | Adopt here |
|---|---|
| A full token scale: page `ink`, `panel`, `panel2` (hover), 3 border strengths, 5 text levels, soft accents; warm palette | A token scale on our Tailwind v4 `@theme`: `surface`, `surface-2`, `surface-hover`, `line`, `line-strong`, `text-1…4`, accent/status tints. The current blue brand stays (primary), softened neutrals |
| Inter (+ JetBrains Mono), antialiased | Inter for UI, JetBrains Mono for references/ids (`@fontsource`, self-hosted, no CDN) |
| `tailwindcss-animate`: dialogs, menus, popovers fade/zoom/slide; small entry animations; eased transitions | `tw-animate-css` (the Tailwind v4 equivalent); open/close animations on Dialog, AlertDialog, Select, DropdownMenu, Sheet, Tooltip; 150–250 ms ease-out transitions on buttons, rows, tiles, nav; skeleton shimmer. `prefers-reduced-motion` keeps disabling all of it |
| Layered panels, soft borders, thin themed scrollbars | A shared `Panel` (titled section) replacing ~40 hand-written `rounded-lg border bg-white p-4` sections; softer shadows; thin scrollbars |
| Dark default + light theme | **Decision for the user (§4 OQ 1)** |

Not adopted: the three.js background and visualiser-specific animations (node glow, recursion pops) — they suit a teaching tool, not
an agent workspace.

## 2. Rules
- Presentation only: no change to routes, queries, mutations, copy (except visual labels), focus order or ARIA semantics.
- No hard-coded palette classes (`bg-white`, `slate-*`, `gray-*`, `amber-*`, `blue-*`, …) in feature code after U6: tokens only, so a
  theme switch is one CSS change. Status tones keep text + icon (never colour only, XC-08); contrast ≥ 4.5:1 for text in every theme.
- Motion: ≤ 250 ms, ease-out, no motion that blocks input; dialogs never animate focus away; reduced motion = no animation.

## 3. Increments (one commit each on `ui/refresh`; branch is stacked on `slice/04-outbound-fe` until PR #24 merges)
| # | Increment | Files (approx.) | Done when |
|---|---|---|---|
| U1 | Tokens, fonts, scrollbars, motion library | `styles/globals.css`, `package.json` (`tw-animate-css`, `@fontsource-variable/inter`, `@fontsource/jetbrains-mono`), `main.tsx` | checks green; every screen renders unchanged except font/colours |
| U2 | Base components: animations + states | `components/ui/{dialog,alert-dialog,select,dropdown-menu,sheet,tooltip,button,input,textarea,badge,table,tabs,checkbox,radio-group}.tsx` | dialogs/menus animate open/close; hover/active/disabled states eased |
| U3 | App shell | `components/layout/{AppShell,Sidebar,Header,NavList,PageContainer,UserMenu}.tsx` | sidebar/header restyled; nav hover/active transitions; mobile drawer slides |
| U4 | Shared `Panel` + data/state components | `components/layout/Panel.tsx` (new), `components/data/*`, `components/states/*` | tables with hover rows, skeleton shimmer, empty/error states restyled |
| U5 | Customer portal screens (02) | `features/customer/**` | no palette classes left; Panel used |
| U6 | Inbound + outbound screens (03, 04) | `features/inbound/**`, `features/outbound/**` | no palette classes left; Panel used; tiles/timeline/notices restyled |
| U7 | ~~Dark theme + toggle~~ — skipped (OQ 1: light only) | `styles/globals.css`, `stores/uiStore.ts` (persisted `theme`), `components/layout/UserMenu.tsx` | both themes pass a contrast check on every badge/tone |

## 4. Open questions
| # | Question | Default |
|---|---|---|
| 1 | Dark theme like the reference? | **Decided:** light only (U7 skipped); tokens ready for it later |
| 2 | Keep the blue brand colour or move to the reference's teal accent? | **Decided:** keep blue; adopt the reference's neutral/surface treatment |
| 3 | Fonts self-hosted? | Yes (`@fontsource`, no external CDN; works offline and in the JAR) |

## 5. Progress
- 2026-11-28: U1–U6 built on `ui/refresh` (stacked on `slice/04-outbound-fe`): tokens + Inter/JetBrains Mono + scrollbars + motion
  utilities; animated dialogs/menus/selects/drawer; softer shell with eased nav and page entry; `Panel`, soft tables/cards, restyled
  empty/error/skeleton states; every feature screen on tokens (only the navy sidebar keeps white overlays). U7 skipped (light only).
  Checks: typecheck, lint, build green. `Panel` adoption in screen markup (replacing the restyled section boxes) is a follow-up.
