# PHASES — build order

Eleven phases (0–10). Each ends with something you can run and test. Backend work and the matching frontend wiring ship together, so every phase leaves the app in a working state.

Definition of done for **every** phase:
- `./mvnw verify` is green (unit + integration + authorization tests).
- New endpoints are covered by tests for 401, 403, other-user access, and the happy path.
- Flyway migrations only. No manual DB changes.
- The wired frontend pages work end to end in a browser (manual script in the final message).
- `docs/MEMORY.md` updated. Every "Done when" box below is ticked in the agent's final message.

| # | Phase | Delivers | Frontend pages wired |
|---|---|---|---|
| 0 | Foundations | Project, CI, Docker, DB, static hosting | none (index served) |
| 1 | Auth and security core | Login, tokens, RBAC, lockout, audit base | `index.html` + guards on all |
| 2 | People | Departments, employees, managers, profiles, documents | admin employees, managers, profile · employee profile |
| 3 | Settings and org config | Settings API, office locations, holidays, backups list | admin settings |
| 4 | Tasks | Full task lifecycle, activity log, notifications core | manager assign-task · employee tasks · admin tasks |
| 5 | Attendance | GPS check-in/out, approvals | employee attendance · manager approve-attendance · admin attendance (top half) |
| 6 | Leave | Ledger, credit jobs, requests, approvals | employee leave · manager review-leave · admin attendance (leave half) |
| 7 | Payroll engine | Pure calculator + inputs assembler, golden tests | none (internal preview endpoint) |
| 8 | Payroll workflow | Runs, approvals, release, retentions, payslip PDF | employee salary · manager salary-approval · admin payroll |
| 9 | Dashboards, reports, audit | 3 dashboards, calendar, notifications, exports, audit API | all 3 dashboards, notification bells |
| 10 | Hardening and deploy | Security pass, password reset, backups, free deployment | forgot-password page, final fixes |

---

## Phase 0 — Foundations
**Goal:** an empty but production-shaped app that builds, runs in Docker, and serves the frontend.
**Scope:** Maven project from start.spring.io (see TECHSTACK); packages from ARCHITECTURE §2; `docker-compose.yml` with PostgreSQL 16; `V1__baseline.sql` (extensions `citext`, `pgcrypto`); `BusinessClock`; `ProblemDetail` handler; `PageResponse`; profiles `local/test/prod`; Dockerfile (multi-stage, JVM flags from TECHSTACK); GitHub Actions CI running `./mvnw verify`; `.env.example`; `.gitignore`; copy the frontend into `src/main/resources/static`; `/actuator/health`; Testcontainers base test class.
**Done when:**
- [ ] `./mvnw verify` passes on a clean clone.
- [ ] `docker compose up` gives a working app at `localhost:8080` and `index.html` loads.
- [ ] `/actuator/health` is UP and includes the DB.
- [ ] `docker run -m 512m` starts the app and idles below ~400 MB.
- [ ] CI is green on GitHub. No secrets in the repo.

## Phase 1 — Auth and security core
**Scope:** `users` (+ `refresh_tokens`, `audit_logs` with the append-only trigger, minimal `system_settings` with security defaults); bootstrap admin from env (once); `POST /auth/login|refresh|logout`, `GET /auth/me`, `POST /auth/change-password`; BCrypt 12; lockout and Bucket4j rate limit; password policy; refresh rotation and reuse detection; `must_change_password`; `SecurityConfig` with security headers; `CurrentUser` and `TeamScope`; `AuditService` and `@Audited`; `api.js`, `auth.js`; wire `index.html`; add guards to all 20 pages. Also in this phase (later phases depend on them): `users.manager_id`, `users.token_version`, `users.deleted_at`, profile fields returned by `/auth/me` (name, role, code, department, avatar), and a change-password step on `index.html` for `must_change_password`. Refresh rotation has a 20-second grace window so parallel tabs and page navigation do not log the user out. The CSP choice for inline handlers is in ARCHITECTURE §4. Last-admin protection (BR-S4) and the generic login failure message (BR-S9) are built here.
**Done when:**
- [x] Admin can log in. A wrong password five times locks the account (test).
- [x] Role comes from the DB. Changing the dropdown to another role changes nothing (test).
- [x] Refresh rotation works. Reusing an old refresh token revokes the family (test).
- [x] Employee opening an admin URL is redirected, and the API returns 403 (test).
- [x] Login success and failure appear in `audit_logs`. UPDATE and DELETE on `audit_logs` fail (test).
- [x] Response headers include HSTS, nosniff, frame denial, CSP.
- [x] Two parallel refresh calls with the same cookie both succeed (grace window); reuse after the window revokes the family (test).
- [x] Offboarding or changing a user's role invalidates their existing access token on the next request (test).
- [x] Unknown email, wrong password and locked account return the same message (test).

## Phase 2 — People
**Scope:** `departments`, `employee_profiles`, `bank_accounts` (encrypted), `employee_deductions`, `employee_documents`; `StorageService` (local + S3); employees CRUD, offboard, reset password (temporary, shown once), soft delete; managers CRUD, assign/remove team, `MANAGER_HAS_TEAM` rule; profile endpoints; document upload and signed download; CSV import/export; employee summary endpoints (tabs return zeros until later phases); auto codes `EMP###`, `MGR###`, `ADM###`.
**Done when:**
- [x] Admin creates an employee and gets a one-time temporary password. First login forces a password change.
- [x] Manager sees only own team (test). Employee reads only self (test).
- [x] Deleting a manager with a team returns 409. After reassigning the team, delete succeeds.
- [x] Offboarded user cannot log in. History remains.
- [x] Bank account number is stored encrypted and returned masked (test reads the raw column).
- [x] Upload rejects a 6 MB file and a `.exe` (tests). Download needs authorization.
- [x] Wired: admin employees, managers, profile; employee profile. Admin employee form gets a Payroll setup tab (bank account, statutory deductions, base salary).
- [x] Removing an employee from a team sets `manager_id` to null (BR-O1); deleting a user with payroll records returns 409.
- [x] Last active admin cannot be deleted, offboarded or demoted (test).

## Phase 3 — Settings and org config
**Scope:** full `GET/PUT /settings/{group}` with validation (key names exactly as in DESIGN §5, including `security.sessionTimeout`, `payroll.noTasksCountsAs100`, `tasks.employeeSelfAssign`, weekly-off days and the half-day threshold); office-location and holiday sections added to `admin/settings.html`; logo upload; `office_locations` CRUD; `holidays`; `backup_runs` + secured webhook + list; settings cache with invalidation on write; every settings change audited with before and after.
**Done when:**
- [x] Each Settings-page section saves and reloads with the same values.
- [x] Invalid values are rejected (negative radius, grace not 15 or 30, bad time).
- [x] Changing password min length changes validation immediately (test).
- [x] Webhook rejects a missing or wrong secret.
- [x] Non-admins get 403 on every settings endpoint.

## Phase 4 — Tasks (and notifications core)
**Scope:** `tasks`, `task_activities`, `task_comments`, `task_attachments`; all endpoints in DESIGN §3; status machine (BR-T2 to T4); progress rules; manager-only approval; soft cancel; list filters and `overdue`; `notifications` table with create/list/read; notify on assign, submit, approve, reject.
**Done when:**
- [ ] Full lifecycle works: assign → start → progress → 100 (auto PENDING_APPROVAL) → approve.
- [ ] Admin cannot approve (403). A manager of a different team cannot approve (403/404).
- [ ] Reject requires a note (the reject dialog is added to the manager page) and the employee can resubmit.
- [ ] Employee tasks page has a progress control (steps of 10) and a Submit button.
- [ ] Every transition creates a timestamped activity row.
- [ ] Invalid transitions return 409 `TASK_INVALID_TRANSITION`. Progress 15 is rejected.
- [ ] Cancelled tasks are excluded from counts and from payroll input.
- [ ] Wired: manager assign-task; employee tasks; admin tasks.

## Phase 5 — Attendance
**Scope:** `attendance_records`, geofence (haversine) matching, work mode and punctuality rules, check-in/out, approval flow with `attendance.managerApproval`, bulk decision, timelines, summaries, exports, end-of-day close job with catch-up.
**Done when:**
- [ ] Check-in stores coordinates, accuracy, device, IP (server-side). Client-sent time is ignored (test).
- [ ] Second check-in the same day returns 409.
- [ ] Inside the geofence = OFFICE, outside = REMOTE. With remote disabled, outside returns 422.
- [ ] Late and half-day boundary tests pass (grace 15 and 30; 239 vs 240 minutes). `is_late` and `day_type` are independent (BR-A4).
- [ ] With manager approval off, records are auto-approved.
- [ ] Manager can decide only own team. Admin can bulk-decide.
- [ ] Wired: employee attendance; manager approve-attendance; admin attendance (attendance half).

## Phase 6 — Leave
**Scope:** `leave_requests`, `leave_ledger_entries`; monthly credit and annual reset with idempotent catch-up; working-day counting with weekly-off and holidays; apply, cancel, approve, reject, `asUnpaid`; balance and ledger endpoints; locking on approval; exports.
**Done when:**
- [ ] Credits are idempotent: run twice, one entry (test). Missed months are back-filled on startup.
- [ ] On the reset date a `RESET` entry zeroes the balance and a `CARRY_FORWARD` entry credits the allowed amount (test with fixed clock).
- [ ] Overlap rejected. Weekend and holiday days not counted.
- [ ] Insufficient balance: approve as normal type returns 422; approve as UNPAID works with no debit.
- [ ] Cancelling approved future leave writes a reversal. Concurrent double-approve yields one debit (test).
- [ ] Leave form has an Unpaid option; the admin leave modal collects a date range and type.
- [ ] Wired: employee leave; manager review-leave; admin attendance (leave half).

## Phase 7 — Payroll engine
**Scope:** `PayrollCalculator` (pure), `PayrollInputs`, `PayrollResult`, `PayrollInputsAssembler` (reads tasks, attendance, leave, deductions, settings), `POST /payroll/preview` (Admin, no persistence).
**Done when:**
- [ ] All golden cases G1–G8 and boundary cases from PRD §4 pass with exact values (net is derived from rounded components, G8).
- [ ] Rounding is done once at the end (G5 gives 9,333.33).
- [ ] Assembler integration test: seeded month with 10 tasks (7 approved), 1 unpaid leave day, 1 absent day gives the hand-computed result.
- [ ] Pending attendance or leave in the month returns `PAYROLL_PENDING_ITEMS` (listing them) unless overridden.
- [ ] Generating before the month ends returns 422 `PAYROLL_MONTH_NOT_ENDED` unless `payroll.allowMidMonthGeneration` is true; the cutoff rule (BR-P3) is tested.
- [ ] No Spring or JPA imports in the calculator package (an ArchUnit test enforces this; ArchUnit is added to the test dependencies in this phase).

## Phase 8 — Payroll workflow
**Scope:** `payroll_runs`, `payroll_records` (with `present_days`, `working_days`, `task_unlocked_amount`, `released_pct`, `paid_amount`), `payroll_lines`, `payroll_retentions`; run generation (department scope; EMPLOYEE and MANAGER roles per D23), recalculation while GENERATED or REJECTED, approve/reject with a required note (manager of the employee, or Admin for managers; never the owner), `release-default` (50%) and `release` (rest, after approval), retentions with auto-release of 2-day amounts into the next run and manual release of 7-day amounts as an EARNING line in the next run (forfeit pays nothing), payslip lines and salary breakup per BR-P15 and D24, a reject-note dialog on the manager page, payslip PDF (OpenPDF), summary, exports; optimistic locking.
**Done when:**
- [ ] Generating twice for the same month returns 409 `PAYROLL_ALREADY_GENERATED` (or recalculates only GENERATED records).
- [ ] State machine enforced: the remainder cannot be released before approval; the 50% default can be released while GENERATED or APPROVED; cannot edit APPROVED or RELEASED.
- [ ] A user cannot approve their own payroll record (test). Manager payroll goes to Admin.
- [ ] Manager cannot approve another team's record (test).
- [ ] 2-day retention appears as an earning line in the next month's run. 7-day stays HELD until released or forfeited with a note.
- [ ] Payslip PDF opens and its numbers equal the API record.
- [ ] Snapshot JSON reproduces the numbers after settings change (test).
- [ ] A released 7-day retention shows up as an EARNING line in the next generated run; forfeit adds nothing.
- [ ] Employee salary page shows Unlocked amount and Released % exactly as in G1 (21,666.67 and 93.33%).
- [ ] Manager payroll view returns no bank details or payslip PDF (403 on the PDF).
- [ ] Wired: employee salary; manager salary-approval; admin payroll.

## Phase 9 — Dashboards, reports, audit
**Scope:** three dashboard endpoints (queries, no N+1), calendar events, full notifications (list, read, read-all, polling-friendly counts), deadline reminder job, report endpoints with CSV/XLSX/PDF streaming, `GET /audit-logs` with filters, minimal audit-log viewer page and a reports page (if the frontend author has not built them); CSV exports escape cells starting with `=`, `+`, `-`, `@` to block formula injection.
**Done when:**
- [ ] Dashboard numbers equal the underlying lists (test with seeded data).
- [ ] Admin dashboard responds within 500 ms on 125 employees and 245 tasks (local).
- [ ] 10,000-row export streams without memory growth beyond the limit.
- [ ] Audit log is filterable and shows before and after values.
- [ ] Wired: all 3 dashboards, notification bells.

## Phase 10 — Hardening and deploy
**Scope:** security checklist pass (below); forgot/reset password with email (Brevo or Resend free tier; some providers need a verified sending domain, check before starting) and the frontend pages; optional TOTP 2FA (D15); Actuator lockdown; backup GitHub Action; deployment to Render + Neon + object storage per DEPLOYMENT.md; smoke-test script; README and operations runbook (restore procedure).
**Security checklist:** every endpoint has a role rule and a scope test · no secret in the repo or logs · CSP verified · cookies Secure+HttpOnly · rate limits on login and reset · uploads validated · dependency scan (`mvn dependency-check` or GitHub Dependabot) clean of critical issues · CORS closed · Swagger off in prod.
**Done when:**
- [ ] App is live on HTTPS and `smoke-test.sh` passes against it.
- [ ] Geolocation works on the live URL.
- [ ] Cold start succeeds (the app waits for the sleeping DB and starts).
- [ ] A backup file is produced by the scheduled workflow and a restore into a scratch DB is verified once.
- [ ] Every item in the security checklist is ticked with evidence.
