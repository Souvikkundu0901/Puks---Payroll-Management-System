# PRD — Cloud-Based Payroll Management System (Backend)

Status: v1.0 · Source: CBPMS_ProjectReport.pdf, SRS.pdf, and all 20 frontend HTML pages (index + 8 admin + 5 manager + 6 employee).
Brand shown in the UI: "Octopyder". Currency: INR (₹). Business timezone: Asia/Kolkata.

## 1. Purpose and scope

The frontend exists (Bootstrap 5 + vanilla JS, static dummy data). This project builds the **backend that makes it real**: REST API, PostgreSQL database, authentication, role enforcement, the payroll engine, audit logging, file storage, exports.

**In scope:** auth and RBAC, employees and managers, tasks, GPS attendance, leave ledger, payroll engine and approval flow, payslips, dashboards, notifications, reports and exports, system settings, audit log, backups (external job), free-tier deployment.

**Out of scope (for now):** real bank transfers, verified/anti-spoof GPS beyond browser geolocation, SMS and push notifications, native mobile apps, multi-company tenancy, statutory filing (TDS/PF returns).

## 2. Roles and permissions

Roles: `ADMIN`, `MANAGER`, `EMPLOYEE`. The role comes from the account in the database. The role dropdown on `index.html` must never be trusted.

"Own team" = users whose `manager_id` is the current manager.

| Capability | Admin | Manager | Employee |
|---|---|---|---|
| Create / edit / offboard users, reset passwords | Yes | No | No |
| View employee records | All | Own team | Self |
| Edit own profile | Yes | Yes | Phone, address, emergency contact, photo only |
| Assign tasks | Any employee | Own team | No (see D6) |
| Edit / cancel tasks | Any | Own team's | No |
| Update task progress, submit task | No | Own team's (progress only) | Own tasks |
| **Approve / reject completed task** | **No** | **Only the task's manager** | No |
| Check in / check out | n/a | n/a | Yes (see D20) |
| Approve / reject attendance | Any (override, audited) | Own team | No |
| Apply for / cancel leave | n/a | n/a | Yes |
| Approve / reject leave | Any (override, audited) | Own team | No |
| Generate payroll | Yes | Own team's GENERATED records only (D18) | No |
| Approve / reject payroll record | Managers' own payroll | Own team | No |
| Release 50% default (tranche 1), release remainder after approval | Yes | No | No |
| Retention release / forfeit | Yes | Own team | No |
| View salary | All, with bank details and snapshot | Own team: summary only (no bank, no snapshot, no payslip PDF) | Self, with payslip PDF |
| Settings, backups, audit log, reports | Yes | Reports for own team | No |

## 3. Business rules (canonical)

IDs in brackets are for tests and commit messages.

### Payroll [BR-P]
- **BR-P1 Gross.** `gross` = employee `base_salary` (monthly).
- **BR-P2 Two parts of salary.** 50% of gross is the *default part* (`defaultReleaseAmount`), payable without task approval. The other 50% is the *task-linked part* (`taskLinkedAmount`). Retention (BR-P4/P6) comes out of the task-linked part: `taskUnlockedAmount = taskLinkedAmount − retentionAmount` (the "Unlocked ₹21,667" on employee/salary.html). `releasedPct = (defaultReleaseAmount + taskUnlockedAmount) ÷ gross` (the "93.3% Released" figure). Statutory and unpaid-leave deductions are applied on top in BR-P9.
- **BR-P3 Completion %.** `approved_tasks ÷ total_tasks × 100`. Tasks counted are those with `deadline` inside the payroll month (IST), excluding `CANCELLED`. Only `APPROVED` counts as approved. No tasks in the month means 100% (setting `payroll.noTasksCountsAs100`, default true). **Never round before comparing** (69.99 is not 70). **Cutoff:** only data up to `cutoff = min(today, month end)` counts: tasks due after the cutoff are excluded and days after the cutoff are not absent. By default payroll can be generated only after the month ends (cutoff = month end). Setting `payroll.allowMidMonthGeneration` (default false) allows demos. `payroll.generationDay` (the UI's "28th") is a reminder date, not a trigger (D22).
- **BR-P4 Retention bands.**
  - `≥ 70` → 0 days retained (100% released)
  - `50 ≤ p < 70` → 2 days retained
  - `< 50` → 7 days retained
- **BR-P5 Day rate.** `gross ÷ payroll.dayRateDivisor` (default **30**). This matches every number in the UI. The admin settings page also shows "Working Days = 26"; that field is informational and is not used for the day rate unless the divisor setting is changed.
- **BR-P6 Retention amount.** `gross × retention_days ÷ divisor`, **rounded once at the end** (not `round(day_rate) × days`, which gives 9,333.31 instead of 9,333.33 for G5). The same rule applies to the unpaid-days deduction. Each component is rounded to 2 places on its own and net is then derived from the rounded components, so payslip lines always add up exactly (G8). Money uses `BigDecimal`, scale 2, `HALF_UP`.
- **BR-P7 Unpaid days.** Approved `UNPAID` leave days + absent working days (BR-A5). Deduction = `day_rate × unpaid_days`. Applies to `EMPLOYEE` role only (D19).
- **BR-P8 Statutory deductions.** Flat monthly amounts per employee for TDS, PF and Professional Tax, set by Admin (default 0). Not computed from slabs (D3).
- **BR-P9 Net.** `net = gross − unpaid_deduction − retention_amount − statutory_total`, never below 0.
- **BR-P10 Payout tranches.** `tranche1 = min(defaultReleaseAmount, net)`, payable by the Admin action `release-default` while the record is GENERATED or APPROVED (no task approval needed). `tranche2 = net − tranche1`, payable only after the record is APPROVED. `paidAmount` tracks what has been paid so far. There is no automatic scheduler for tranche 1 (D21): a person marks salary as paid, because there is no bank integration.
- **BR-P11 Retention lifecycle.** Each retention is a row with status `HELD`, `RELEASED` or `FORFEITED`. 2-day retention auto-releases as an earning line in the next payroll run. 7-day retention stays `HELD` until a Manager (own team) or Admin releases or forfeits it with a note (D4). A manual release creates an EARNING line "Retention released" in the employee's next generated run (`released_in_record_id` is set then). Forfeit pays nothing and only records the decision.
- **BR-P12 Workflow.** `GENERATED → APPROVED | REJECTED → RELEASED`. Recalculation (`POST /payroll/runs` with `recalculate=true`) is allowed only for `GENERATED` and `REJECTED` records. `RELEASED` means fully paid. The approver is the employee's manager (an Admin for MANAGER-role employees) and never the record's own owner (D23). `APPROVED` and `RELEASED` records are immutable. Corrections go in the next run as adjustment lines.
- **BR-P13 Pending inputs block generation.** If any attendance record or leave request that overlaps the month is still `PENDING`, generation returns 422 with the list. Admin may pass `treatPendingAsPresent=true` to override (pending attendance counts as present, pending leave counts as not taken; audited).
- **BR-P15 Payslip lines.** Earnings lines are the gross split by `payroll.breakupPercent` (D24). Deduction lines: unpaid leave, retention, TDS, PF, PT. Each record also stores `presentDays` and `workingDays` (the "29/30 days" on manager/salary-approval.html).
- **BR-P14 Snapshot.** Every record stores a JSON snapshot of all inputs and settings used, so a payslip can be reproduced years later.

### Tasks [BR-T]
- **BR-T1** Title + text description only. No subtasks. Also: priority (HIGH/MEDIUM/LOW), deadline date and time, optional estimated hours, department.
- **BR-T2** Statuses: `ASSIGNED, IN_PROGRESS, PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED`. `OVERDUE` is derived (deadline passed and not APPROVED/CANCELLED).
- **BR-T3** Progress is 0–100 in steps of 10. Reaching 100 moves the task to `PENDING_APPROVAL`. Progress > 0 on an `ASSIGNED` task moves it to `IN_PROGRESS`. "Submit" means setting progress to 100 and is allowed from `ASSIGNED`, `IN_PROGRESS` or `REJECTED`.
- **BR-T4** Only the task's manager (`tasks.manager_id`, which defaults to the assignee's manager; D25) can approve or reject. Managers can view tasks where they are the manager or the assignee is in their team. Reject requires a note and sets `REJECTED`. The assignee can resubmit from `REJECTED`.
- **BR-T5** Every change writes a timestamped `task_activities` row (created, started, progress, submitted, approved, rejected, edited, reassigned, cancelled).
- **BR-T6** "Delete" is a soft cancel (`CANCELLED`). Cancelled tasks are excluded from completion %.
- **BR-T7** Comments and file attachments are supported per task.

### Attendance [BR-A]
- **BR-A1** The server sets all timestamps. Client time is never trusted. One record per user per `work_date` (IST).
- **BR-A2** Check-in captures latitude, longitude, accuracy (m), device platform, user agent and IP (IP read server-side). If `attendance.gpsVerification` is on, coordinates are required.
- **BR-A3** Work mode is derived: `OFFICE` if inside an office geofence (radius setting, default 100 m) or on an allowed office IP; otherwise `REMOTE`. If `attendance.remoteAllowed` is off and the user is outside the geofence, check-in is refused. `HYBRID` is stored but not derived.
- **BR-A4** `is_late` = check-in after `officeStart + grace` (grace 15 or 30 min). `day_type` = `HALF_DAY` if worked < 240 min after check-out, otherwise `PRESENT` (provisionally `PRESENT` until check-out). The two are independent, so a record can be late and half-day. The UI shows "Late" when `is_late` is true, otherwise the day type.
- **BR-A5** For payroll, a working day is absent when there is no `APPROVED` attendance and no `APPROVED` leave. Working day = not a weekly-off day (default Sat and Sun) and not a holiday. Days before joining or after exit are ignored.
- **BR-A6** Approval: if `attendance.managerApproval` is on, new records are `PENDING`. If off, they are auto-`APPROVED`. A `REJECTED` record counts as absent.
- **BR-A7** A job closes open records at day end with flag `MISSED_CHECKOUT` (counted present if approved).
- **BR-A8** Late and overtime have no money effect by default (D11, D12). `overtime_minutes = max(0, worked_minutes − (officeEnd − officeStart))` is stored for display. Monthly attendance % = (present days + 0.5 × half days + approved leave days) ÷ working days up to the cutoff.
- **BR-A9** The geofence is a circle (centre and radius), not a polygon; the UI text "HQ Office Polygon" means that circle match. Browsers cannot detect mock locations: store `accuracy_m` and flag `LOW_ACCURACY` above 200 m. "Mock Location" is a manager's judgement with a reject note. There is no reverse geocoding: show coordinates and a map link. Office-IP matching works only when `allowed_ip_cidrs` is set and the request arrived through the configured trusted proxy (default: off).

### Leave [BR-L]
- **BR-L1** Leave year runs 1 April to 31 March (setting `leave.resetDate`).
- **BR-L2** 1 leave is credited on the 1st of each month from the joining month. Credits are ledger entries and must be **idempotent** (unique per user and month).
- **BR-L3** Unused leave rolls over month to month. On 1 April the balance is cut off: a `RESET` entry brings it to 0 and, if `leave.carryForwardOnReset` > 0 (default 0), a `CARRY_FORWARD` entry credits `min(old balance, carryForwardOnReset)` (D8). The leave year starts on `leave.resetDate` (04-01 or 01-01).
- **BR-L4** Types: `PAID, CASUAL, SICK, EMERGENCY, UNPAID` (D7). All except `UNPAID` draw from the single balance.
- **BR-L5** Day count = working days in the range (excludes weekly off and holidays). Overlapping `PENDING` or `APPROVED` requests are rejected.
- **BR-L6** If days exceed the balance, the request is flagged `insufficientBalance`. The approver can only approve it **as UNPAID** (loss of pay). Approving a normal type then returns 422.
- **BR-L7** Statuses: `PENDING, APPROVED, REJECTED, CANCELLED`. Approval writes a `DEBIT` ledger entry inside the same transaction, with the user's ledger locked. Cancelling an approved future leave writes a `REVERSAL`.
- **BR-L8** `SICK` over 2 days accepts a medical certificate attachment (optional, not enforced).
- **BR-L9** The full ledger is kept forever and is append-only.

### Organisation [BR-O]
- **BR-O1** Removing an employee from a team sets `manager_id` to null. Until a manager is assigned again, approvals for that employee (attendance, leave, payroll, tasks) go to Admin.
- **BR-O2** A manager cannot be offboarded or deleted while they have employees (`MANAGER_HAS_TEAM`) or open tasks awaiting their approval (`MANAGER_HAS_OPEN_APPROVALS`).
- **BR-O3** "Delete" is a soft delete: `users.deleted_at` is set, the user disappears from lists, login is disabled, history stays. It is blocked (409) if payroll records exist; offboard instead.
- **BR-O4** Pending items follow the employee: approver authority is by the employee's *current* manager.
- **BR-O5** Experience is stored as numeric years (`experience_years`) so the 1-3 / 3-5 / 5+ filters work.

### Security and audit [BR-S]
- **BR-S1** Password rules, session timeout and max login attempts come from system settings. Lockout after N failed attempts (default 5, 15-minute lock).
- **BR-S2** The audit log is append-only. It records who, what, when, from where, and before and after values for: logins (success and fail), password changes and resets, user create/update/offboard, role and salary changes, task approve/reject/cancel, attendance and leave decisions, payroll generate/approve/reject/release, retention decisions, settings changes, exports, and document downloads.
- **BR-S3** Every endpoint enforces role AND ownership or team scope on the server.
- **BR-S4** Last-admin protection: the last active ADMIN cannot be offboarded, deleted, demoted or locked by an admin action, and nobody can change their own role.
- **BR-S5** Offboarding, role change, password change or reset, and lockout take effect immediately (token version check, see ARCHITECTURE §4).
- **BR-S6** Audit `before`/`after` never contain passwords, hashes, tokens, temporary passwords, or bank account numbers (redaction list).
- **BR-S7** Decision notes: required for task reject, leave reject, payroll reject, and retention release or forfeit; optional for attendance reject.
- **BR-S8** Temporary passwords: at least 14 random characters from `SecureRandom`, shown once, must be changed at first login, expire after 7 days unused.
- **BR-S9** Login failures look the same for unknown email, wrong password, and locked account ("Invalid credentials or account temporarily locked"). A dummy BCrypt check runs for unknown emails to equalise timing.

## 4. Golden test cases (must pass exactly)

| # | Gross | Completion | Unpaid days | Statutory | Retention days | Retention ₹ | Unpaid ₹ | Net ₹ | Source |
|---|---|---|---|---|---|---|---|---|---|
| G1 | 50,000 | 55% | 0 | 3,300 | 2 | 3,333.33 | 0 | 43,366.67 | employee/salary.html (shows 43,367) |
| G2 | 60,000 | 60% | 1 | 0 | 2 | 4,000.00 | 2,000.00 | 54,000.00 | manager/salary-approval.html |
| G3 | 30,000 | 40% | 0 | 0 | 7 | 7,000.00 | 0 | 23,000.00 | manager/salary-approval.html |
| G4 | 45,000 | 60% | 0 | 0 | 2 | 3,000.00 | 0 | 42,000.00 | admin/payroll.html |
| G5 | 40,000 | 40% | 0 | 0 | 7 | 9,333.33 | 0 | 30,666.67 | admin/payroll.html (shows 9,333) |
| G6 | 50,000 | 80% | 0 | 0 | 0 | 0 | 0 | 50,000.00 | admin/payroll.html |
| G7 | 30,000 | 60% | 0 | 0 | 2 | 2,000.00 | 0 | 28,000.00 | architecture diagram image (not one of the 20 pages) |
| G8 | 40,000 | 40% | 1 | 0 | 7 | 9,333.33 | 1,333.33 | 29,333.34 | derived: rounding rule BR-P6 (components rounded, net derived) |

G1 tranches: tranche1 = 25,000.00, tranche2 = 18,366.67. G1 display fields: `taskUnlockedAmount` = 21,666.67 and `releasedPct` = 93.33%. Net values for G4–G8 are derived from the rules; the pages show only the retention amount (G4, G5) or the released figure (G6).

Boundary tests: 70.00 → 0 days · 69.99 → 2 · 50.00 → 2 · 49.99 → 7 · no tasks → 100%.

Leave tests: 1 credit per month, credited twice for the same month = one entry · balance 2 and 3-day PAID request → `insufficientBalance`, approve as UNPAID works, approve as PAID fails · 1 April reset · cancel approved future leave writes a reversal.

## 5. Decisions and defaults (confirm with the frontend author)

The documents are silent or contradictory on these. The defaults below are what the build will use. Each is a setting or isolated in one class, so changing it is cheap.

| ID | Question | Default used |
|---|---|---|
| D1 | How is "approved completion %" computed? | Count of approved ÷ count of tasks due in month (BR-P3). Alternative: weight by estimated hours. |
| D2 | Day-rate divisor | 30, setting `payroll.dayRateDivisor` |
| D3 | TDS, PF, PT | Flat per-employee monthly amounts, default 0 |
| D4 | When is retained salary released? | 2-day: next run, automatically. 7-day: explicit Manager/Admin decision. |
| D5 | Who approves attendance and leave? | Manager for own team. Admin may override. |
| D6 | Who can assign tasks? Report says "Admin, Manager or User". | Admin and Manager now. Employee self-assigned tasks are behind flag `tasks.employeeSelfAssign` (default off). |
| D7 | Leave types differ across pages | Unified to PAID, CASUAL, SICK, EMERGENCY, UNPAID. "Medical" in the UI maps to SICK. |
| D8 | "Maximum Carry Forward = 12" vs. "resets on 1 April" | Reset to 0 carry-forward. Change `leave.carryForwardOnReset` if 12 is intended. |
| D9 | Weekly off days | Saturday and Sunday (setting). The UI's "26 working days" implies Sunday only. Confirm. |
| D10 | Half-day threshold | Worked < 240 minutes |
| D11 | Late deduction | None |
| D12 | Overtime pay | Off. The toggle is stored, with no calculation yet. |
| D13 | Employee code format | `EMP001`, `MGR001`, `ADM001` (admin forms). The UI also shows `EMP-2026-042`, which is not used. |
| D14 | Fields an employee may edit | Phone, address, emergency contact, photo. Name and email changes are Admin-only (email is the login). |
| D15 | 2FA and forgot-password | Forgot-password by email is built in Phase 10 (needs an email provider). TOTP 2FA is optional in Phase 10; until then the flag is only stored. |
| D16 | Backup "Restore" button | Not implemented in-app (too dangerous). Documented manual runbook instead. |
| D17 | Deployment shape | One Spring Boot app serving the static frontend (same origin). |
| D18 | Manager's "Generate Final Batch" button | Recalculates only the manager's team's `GENERATED` records. Admin generates org-wide. |
| D19 | Payroll rules for MANAGER and ADMIN roles | Retention and absent-day rules apply to EMPLOYEE only. Manager payroll is approved by Admin. |
| D20 | Manager and Admin attendance | The check-in API allows any authenticated user, but only the Employee portal has a UI. Not required for managers or admins. |
| D21 | Tranche payment | Two payouts by Admin actions: `release-default` (50%), then `release` (the rest, after approval). No scheduler. |
| D22 | Payroll timing | Generate after month end (cutoff rule in BR-P3). `generationDay` is a reminder only. |
| D23 | Who is in payroll | Active users with `base_salary > 0` and role EMPLOYEE or MANAGER. ADMIN users are excluded unless `payroll.includeAdmins=true`. Nobody approves their own record. |
| D24 | Salary breakup display | Gross split by `payroll.breakupPercent` (default Basic 50, HRA 20, Medical 10, Travel 10, Performance bonus 10), matching employee/salary.html. The 40k/8k/2k in admin/payroll.html is mock data. |
| D25 | Task approver | `tasks.manager_id` defaults to the assignee's manager. Admin may pick another MANAGER. Only that manager approves. |
| D26 | Manager access to payroll | Managers see their team's payroll summary (amounts, status, completion) only: no bank details, no snapshot, no payslip PDF. |

## 6. Frontend gaps (the report promises these; no page exists)

Audit-log viewer · Reports page · Forgot/reset password page · First-login forced password change · Holiday management · Office location (geofence) management · Department management · Employee payroll setup (bank account and statutory deductions) · Retention release/forfeit screen · Full notifications page · "Cancel leave" button for employees · Reject-note dialogs.

Plan: build the API for all of them, plus minimal UI in the existing style where the product cannot work without it: change-password step on `index.html` (Phase 1), office-location and holiday sections in `admin/settings.html` (Phase 3), reject-note dialogs (each phase that has a reject), a "Payroll setup" tab in the admin employee view (Phase 8), audit-log and reports pages (Phase 9), forgot/reset password pages (Phase 10). Departments are seeded (IT, HR, Finance, Marketing) and managed through the API only. The retention screen and notifications page stay API-only until the frontend author adds UI.

## 7. Non-functional requirements

- **Security:** OWASP ASVS L2 baseline, HTTPS only, BCrypt/Argon2 hashing, no secrets in git.
- **Privacy:** Indian DPDP Act 2023 applies to employee PII. No Aadhaar or PAN numbers stored as text (documents only, private storage, short-lived signed URLs). Bank account numbers encrypted at rest, masked in responses.
- **Performance:** list endpoints paginated server-side (default 10, max 100). p95 under 500 ms for typical reads on free-tier hardware, excluding cold start.
- **Availability on free tier:** cold starts of about 1 minute are expected. The app must tolerate DB wake-up and must not depend on cron firing at exact times (see ARCHITECTURE §Jobs).
- **Auditability:** BR-S2.
- **Testing:** unit tests for all BR rules, integration tests with Testcontainers PostgreSQL, authorization tests for every endpoint (including cross-team access attempts).
- **Not for production payroll** on free tiers. Real employee data needs paid hosting, backups with retention, and a data-processing agreement.
