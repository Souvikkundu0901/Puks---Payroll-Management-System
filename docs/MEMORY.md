# MEMORY.md — living project state

**The coding agent reads this file first in every session and updates it at the end.** Keep it short (under about 150 lines). Prune old session logs. Never store secrets here.

## Project snapshot
- Product: Cloud-Based Payroll Management System ("Octopyder"). Backend for an existing static frontend (20 HTML pages).
- Stack: Java 25, Spring Boot 4.1.2, PostgreSQL 16, Flyway, Spring Security (JWT + refresh cookie), Maven. Single deployable that also serves the frontend.
- Docs (in `/docs`): PRD.md (rules, decisions D1–D25, golden tests G1–G8) · TECHSTACK.md · ARCHITECTURE.md (schema, flows, jobs) · DESIGN.md (API catalog, page mapping) · PHASES.md · PROMPTS.md · DEPLOYMENT.md. Rules: `AGENTS.md` (copy of RULES.md).

## Status
| Phase | State | Notes |
|---|---|---|
| 0 Foundations | DONE | Spring Boot 4.1.2, Java 25, PostgreSQL 16, Flyway V1, Actuator health, static frontend |
| 1 Auth and security core | DONE | JWT (Nimbus) + HttpOnly refresh cookie with 20s grace window rotation and reuse detection; BCrypt 12; lockout & Bucket4j rate limit; audit_logs immutable append-only; frontend api.js + auth.js guards on all 20 pages |
| 2 People | DONE | Employee & manager CRUD, AES-256-GCM bank account encryption, Local & S3 StorageService with magic-byte allowlist, temporary passwords with 7-day expiry and no-store, CSV import/export with formula injection sanitization, soft-delete with 409 checks, wired admin/employees, admin/managers, admin/profile, employee/profile |
| 3 Settings and org config | DONE | System settings registry & validation, GET/PUT /settings/{group}, GET /app-config, logo upload, office locations CRUD with soft deactivation, holidays CRUD, backup runs + secret webhook + list, in-memory cache with post-commit invalidation, audit logging with before/after diffs, wired admin/settings.html |
| 4 Tasks | NOT STARTED | |
| 5 Attendance | NOT STARTED | |
| 6 Leave | NOT STARTED | |
| 7 Payroll engine | NOT STARTED | |
| 8 Payroll workflow | NOT STARTED | |
| 9 Dashboards, reports, audit | NOT STARTED | |
| 10 Hardening and deploy | NOT STARTED | |

States: NOT STARTED · IN PROGRESS · BLOCKED · DONE (all "Done when" boxes ticked, `./mvnw verify` green).

**Current phase:** 4
**Next step:** Phase 4 — Tasks (and notifications core). Status machine (BR-T2 to T4), task progress, manager approval, soft cancel, overdue flags, notifications table, wire manager assign-task, employee tasks, admin tasks.

## Decisions in force
Defaults D1–D25 in PRD §5 apply unless changed below. Record every change here with date and reason.

| Date | Decision | Reason |
|---|---|---|
| 2026-10-26 | Java 25 retained (no fallback to Java 21) | Toolchain verified and builds cleanly with Spring Boot 4.1.2 and javac release 25 |
| 2026-10-26 | TDD + Security Agent Red Teaming + Code Minimization | User mandate: strict TDD (unit + integration tests first), minimize code footprint, act as security adversary after each feature to find/report bugs, and do not fix reported bugs without user instructions |
| 2026-10-27 | manager_id on users only, token_version/deleted_at reused | User approved changes: no duplicated manager_id on employee_profiles; soft deletes enforced |
| 2026-10-27 | Temporary passwords: no-store, never logged, 7-day expiry | User security requirement: reset revokes refresh tokens and bumps token_version |
| 2026-10-27 | Magic-byte checks for file uploads | Header + content inspection for PDF, PNG, JPEG to prevent file upload disguise |
| 2026-10-27 | Phase 2 security red-team fixes | IDOR stream authorization, docType enum validation (400 not 500), CSV import 5MB limit, pessimistic locking on manager deletion/assignment |
| 2026-10-28 | Phase 3 security red-team fixes | Company logo allowed in stream validator for authenticated users, backup webhook payload strict status/size validation (400 instead of DB 500), office location CIDR format validation, holiday creation concurrency duplicate translation (409 instead of 500) |

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
- Spring Boot 4 modularized starter test packages: `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure`.
- Round money once at the end: `gross × days ÷ divisor`, not `round(dayRate) × days` (G5).
- Free hosts sleep: jobs must be idempotent and catch up on startup (ARCHITECTURE §6).
- Flyway needs a direct (non-pooled) DB URL on Neon. The app uses the pooled URL.
- Admin cannot approve tasks (BR-T4). It is intentional.
- Payroll is generated after month end (D22). The 50% default part and the task-linked part are different from the two payout tranches; read BR-P2 and BR-P10 before touching payroll.
- Nobody approves their own payroll record (D23).
- The login role dropdown is never trusted.
- AuditLog jsonb mapping: non-JSON strings must be wrapped in valid JSON syntax `{"message":"..."}` before persisting to PostgreSQL jsonb.
- Audit logging requires `Propagation.REQUIRES_NEW` so failed transactions (e.g. login failures, lockout) still commit their audit entries and increment counters.
- X-Forwarded-For is only trusted when APP_TRUSTED_PROXY=true (BR-S8).
- Pessimistic write lock on RefreshTokenRepository.findByTokenHash serializes concurrent rotation attempts.
- Lockout counters automatically reset upon first attempt after the locked_until duration expires.
- AuditLog request_id is truncated to 64 chars to prevent schema length violations on malicious headers.
- audit_logs append-only trigger prohibits UPDATE and DELETE; FK `actor_id ON DELETE SET NULL` fails on hard delete of users. Soft deletes (`deleted_at = now()`) must always be used.
- LocalStorageService root dir must be normalized to an absolute path so `path.normalize().startsWith(rootDir)` evaluates correctly.
- GlobalExceptionHandler requires explicit `@ExceptionHandler(AccessDeniedException.class)` in Spring Boot 4 / Spring Security 6+ to avoid falling through to 500 handler.
- Formula injection prevention: prefix characters `=`, `+`, `-`, `@` with single quote `'` in CSV exports.
- Document streaming `/stream/{storageKey}` requires ownership validation (Admin/Self for docs, org-scoped for avatars) to prevent IDOR.
- Database check constraints on doc_type (`RESUME`, `OFFER_LETTER`, `ID_CARD`, `CERTIFICATE`, `PAN`, `AADHAAR`, `OTHER`) must be validated in service to return 400 Bad Request instead of DB constraint 500 errors.
- Concurrent team assignment and manager deletion must be serialized using pessimistic write locking (`findByIdForUpdate`) to prevent TOCTOU races.
- Spring Boot 4.1.2 uses Jackson 3.x (`tools.jackson.databind.*` and `tools.jackson.core.type.*`).
- Settings cache must be invalidated on write with immediate thread visibility and rollback synchronization (`TransactionSynchronization.afterCompletion`) to restore DB state on rollback.
- Backup webhook requires constant-time secret comparison (`MessageDigest.isEqual`) and must fail closed (401) if BACKUP_WEBHOOK_SECRET environment variable is null or empty.

## Commands
- Run locally: `docker compose up -d db && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- Test: `./mvnw test`
- Build image: `docker build -t payroll .`
- Run container with 512M limit: `docker compose up -d` or `docker run -m 512m -p 8080:8080 payroll`

## Session log (newest first; keep the last ~10)
| Date | Phase | Done | Next |
|---|---|---|---|
| 2026-10-28 | 3 | Settings and org config complete: Flyway V4 (system_settings seeded with ON CONFLICT DO NOTHING, office_locations, holidays, backup_runs), central SettingDefinition registry with type/range/format validation, GET/PUT /settings/{group} patch support, GET /app-config for all authenticated users, logo upload (PNG/JPEG max 2MB) with magic byte check, office locations CRUD with soft deactivation, holidays CRUD with 409 duplicate checks, backup webhook with constant-time secret verification & rate limiting, dynamic security settings without restart (min length, max attempts, auto lock, timeout), wired admin/settings.html | Phase 4 Tasks |
| 2026-10-27 | 2 | People complete: Flyway V3 (departments, employee_profiles, bank_accounts, employee_deductions, employee_documents, user_preferences, sequences), AES-256-GCM encryption with raw column tests, LocalStorageService & S3StorageService with 5MB & magic-byte validation, employee & manager CRUD, team assignment/removal, soft delete & 409 checks, CSV import/export, profile endpoints, wired admin/employees.html, admin/managers.html, admin/profile.html, employee/profile.html | Phase 3 Settings and org config |
| 2026-10-26 | 1 | Auth & security core complete: Nimbus HS256 JWT, HttpOnly refresh cookie rotation with 20s grace window and family reuse revocation, BCrypt 12, account lockout (5 failed attempts, 15m duration), Bucket4j IP/email rate limiting, append-only immutable audit_logs trigger, static frontend wired with api.js & auth.js guards on all 20 pages | Phase 2 People |
| 2026-10-26 | 0 | Foundations complete: Spring Boot 4.1.2 + Java 25, Testcontainers Postgres 16, Flyway V1, multi-stage Dockerfile, CI workflow, ProblemDetail, PageResponse, BusinessClock, static frontend served at / | Phase 1 Auth and security core |
