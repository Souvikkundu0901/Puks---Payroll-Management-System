# MEMORY.md — living project state

**The coding agent reads this file first in every session and updates it at the end.** Keep it short (under about 150 lines). Prune old session logs. Never store secrets here.

## Project snapshot
- Product: Cloud-Based Payroll Management System ("Octopyder"). Backend for an existing static frontend (20 HTML pages).
- Stack: Java 25 (or 21), Spring Boot 4.1.x, PostgreSQL 16, Flyway, Spring Security (JWT + refresh cookie), Maven. Single deployable that also serves the frontend.
- Docs (in `/docs`): PRD.md (rules, decisions D1–D25, golden tests G1–G8) · TECHSTACK.md · ARCHITECTURE.md (schema, flows, jobs) · DESIGN.md (API catalog, page mapping) · PHASES.md · PROMPTS.md · DEPLOYMENT.md. Rules: `AGENTS.md` (copy of RULES.md).

## Status
| Phase | State | Notes |
|---|---|---|
| 0 Foundations | NOT STARTED | |
| 1 Auth and security core | NOT STARTED | |
| 2 People | NOT STARTED | |
| 3 Settings and org config | NOT STARTED | |
| 4 Tasks | NOT STARTED | |
| 5 Attendance | NOT STARTED | |
| 6 Leave | NOT STARTED | |
| 7 Payroll engine | NOT STARTED | |
| 8 Payroll workflow | NOT STARTED | |
| 9 Dashboards, reports, audit | NOT STARTED | |
| 10 Hardening and deploy | NOT STARTED | |

States: NOT STARTED · IN PROGRESS · BLOCKED · DONE (all "Done when" boxes ticked, `./mvnw verify` green).

**Current phase:** 0
**Next step:** Run PROMPTS.md "Prompt 0".

## Decisions in force
Defaults D1–D25 in PRD §5 apply unless changed below. Record every change here with date and reason.

| Date | Decision | Reason |
|---|---|---|
| — | (none yet) | |

## Open questions for the frontend author (answers change a default, not the architecture)
1. **Completion %** (D1): approved ÷ tasks due in month, or weighted by estimated hours?
2. **Day rate** (D2/D9): divisor 30 matches all UI numbers, but the Settings page says 26 working days. Which is intended? Weekly off: Sat+Sun or Sunday only?
3. **Statutory deductions** (D3): flat amounts per employee (TDS 1,000 / PF 2,100 / PT 200 in the UI), or computed from slabs?
4. **Retention release** (D4): does 2-day retention return automatically next month? Who decides 7-day retention, and are Forfeit and partial release allowed?
5. **Carry forward** (D8): "resets on 1 April" or "carry forward up to 12"? The Settings page default of 12 contradicts the report.
6. **Task assignment** (D6): can employees assign tasks to themselves? Report says "Admin, Manager, or User".
7. **Leave types** (D7): is the set Paid, Casual, Sick, Emergency, Unpaid right? Do Casual and Sick draw from the same 1-per-month balance?
8. **Missing screens:** will the author add audit log, reports, forgot-password, holiday and office-location pages, or should the backend project add minimal ones?

## Gotchas and conventions learned (append as you go)
- Round money once at the end: `gross × days ÷ divisor`, not `round(dayRate) × days` (G5).
- Free hosts sleep: jobs must be idempotent and catch up on startup (ARCHITECTURE §6).
- Flyway needs a direct (non-pooled) DB URL on Neon. The app uses the pooled URL.
- Admin cannot approve tasks (BR-T4). It is intentional.
- Payroll is generated after month end (D22). The 50% default part and the task-linked part are different from the two payout tranches; read BR-P2 and BR-P10 before touching payroll.
- Nobody approves their own payroll record (D23).
- The login role dropdown is never trusted.

## Commands
- Run locally: `docker compose up -d db && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- Test: `./mvnw verify`
- Build image: `docker build -t payroll .`
- (Add more as discovered.)

## Session log (newest first; keep the last ~10)
| Date | Phase | Done | Next |
|---|---|---|---|
| — | — | — | — |
