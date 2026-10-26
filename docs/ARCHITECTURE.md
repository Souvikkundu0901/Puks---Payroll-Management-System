# ARCHITECTURE — Cloud-Based Payroll Management System

## 1. Shape

A **modular monolith**: one Spring Boot app, one PostgreSQL database, one deployable. It also serves the static frontend, so the browser talks to a single origin.

```
Browser (Admin / Manager / Employee)
   │  HTTPS, same origin
   ▼
Spring Boot app
 ├─ static/            frontend HTML/CSS/JS (existing)
 ├─ web layer          controllers, DTOs, ProblemDetail errors
 ├─ security           JWT filter, RBAC, scope checks, rate limit
 ├─ modules            auth · people · org · task · attendance · leave · payroll
 │                     notification · audit · report · storage · common
 ├─ jobs               @Scheduled, idempotent, with catch-up
 └─ persistence        Spring Data JPA + Flyway
   │                         │
   ▼                         ▼
PostgreSQL            S3-compatible object storage (documents, photos, attachments, logo)
```

External, outside the app: a GitHub Actions cron that runs `pg_dump` (backup), and an uptime pinger (keep-alive on sleeping free hosts).

## 2. Package layout

```
com.octopyder.payroll
├─ PayrollApplication
├─ common/      BusinessClock, ApiError/ProblemDetail handler, PageResponse, enums, CurrentUser, Money utils
├─ config/      SecurityConfig, WebConfig, JacksonConfig, SchedulingConfig, StorageConfig
├─ auth/        AuthController, AuthService, RefreshTokenService, LoginAttemptService, PasswordPolicy
├─ people/      User, EmployeeProfile, Department, BankAccount, EmployeeDocument, EmployeeDeduction
│               EmployeeController, ManagerController, ProfileController, TeamScope
├─ org/         SystemSetting, SettingsService, OfficeLocation, Holiday, BackupRun
├─ task/        Task, TaskActivity, TaskComment, TaskAttachment, TaskService, TaskController
├─ attendance/  AttendanceRecord, AttendanceService, GeofenceService, AttendanceJobs
├─ leave/       LeaveRequest, LeaveLedgerEntry, LeaveService, LeaveLedgerService, LeaveJobs
├─ payroll/     PayrollRun, PayrollRecord, PayrollLine, PayrollRetention,
│               PayrollCalculator (pure), PayrollInputsAssembler, PayrollService, PayslipPdfService
├─ notification/ Notification, NotificationService, EmailSender (optional)
├─ audit/       AuditLog, AuditService (+ @Audited aspect), AuditController
├─ report/      ReportController, Csv/Xlsx/Pdf writers, DashboardService
└─ storage/     StorageService (interface), S3StorageService, LocalStorageService
```

**Dependency rules:** `controller → service → repository`. Modules call each other only through public service interfaces. `payroll` reads from `task`, `attendance`, `leave`, `people` and `org` through their services (via `PayrollInputsAssembler`). Nothing depends on `payroll` except `report` and `notification`. `PayrollCalculator` imports nothing from Spring or JPA.

## 3. Database schema

PostgreSQL 16. Keys are `uuid` with a `code` where people need to see one. All tables have `created_at` and `updated_at` (`timestamptz`). Tables marked (v) have a `version` column for optimistic locking.

**Identity and people**
- `users` — id, code (unique, `EMP001`), email (unique, `citext`), password_hash, role (`ADMIN|MANAGER|EMPLOYEE`), status (`ACTIVE|INACTIVE|RESIGNED`), must_change_password, failed_attempts, locked_until, last_login_at, two_factor_enabled, password_changed_at.
- `employee_profiles` — user_id (PK/FK), first_name, last_name, gender, date_of_birth, phone, address, blood_group, emergency_contact, marital_status, photo_key, department_id (FK), designation, employment_type (`FULL_TIME|PART_TIME|CONTRACT`), joining_date, exit_date, manager_id (FK users, nullable), base_salary `NUMERIC(12,2)` with CHECK `>= 0`, experience_text, skills `text[]`.
- `departments` — id, name (unique), active.
- `bank_accounts` — user_id (PK/FK), bank_name, account_number_enc, account_last4, ifsc.
- `employee_deductions` — id, user_id, type (`TDS|PF|PT|OTHER`), monthly_amount, effective_from.
- `employee_documents` — id, user_id, doc_type (`RESUME|OFFER_LETTER|ID_CARD|CERTIFICATE|PAN|AADHAAR|OTHER`), storage_key, original_name, content_type, size_bytes, verification_status (`UPLOADED|VERIFIED|SIGNED|REJECTED`), verified_by, uploaded_at.
- `refresh_tokens` — id, user_id, token_hash (unique), expires_at, revoked_at, replaced_by, user_agent, ip.
- `password_reset_tokens` — id, user_id, token_hash, expires_at, used_at.
- `user_preferences` — user_id (PK), theme, notify_email, notify_push, notify_payroll.

**Tasks**
- `tasks` (v) — id, code (`TSK001`), title, description, priority, assignee_id, manager_id, created_by, department_id, deadline_at, est_hours, status, progress `smallint` CHECK (`0..100` and `% 10 = 0`), submitted_at, approved_at, approved_by, rejection_note, cancelled_at.
- `task_activities` — id, task_id, actor_id, type, from_value, to_value, note, at. Append-only.
- `task_comments` — id, task_id, author_id, body, at.
- `task_attachments` — id, task_id, uploaded_by, storage_key, original_name, size_bytes, at.

**Attendance**
- `attendance_records` (v) — id, user_id, work_date, check_in_at, check_out_at, check_in_lat/lng/accuracy_m, check_out_lat/lng, work_mode, geofence_matched, office_location_id, device_platform, user_agent, ip_address, punctuality (`PRESENT|LATE|HALF_DAY`), approval_status (`PENDING|APPROVED|REJECTED`), decided_by, decided_at, decision_note, worked_minutes, overtime_minutes, flags `jsonb`. UNIQUE (user_id, work_date).
- `office_locations` — id, name, latitude, longitude, radius_m, allowed_ip_cidrs `text[]`, active.

**Leave**
- `leave_requests` (v) — id, user_id, leave_type, start_date, end_date, days `NUMERIC(4,1)`, reason, attachment_key, status, insufficient_balance, manager_id, decided_by, decided_at, decision_note.
- `leave_ledger_entries` — id, user_id, leave_year (the starting year of 1 Apr), entry_type (`CREDIT|DEBIT|RESET|CARRY_FORWARD|REVERSAL|ADJUSTMENT`), days `NUMERIC(4,1)` (signed), period_month `date` (for CREDIT), request_id, note. Append-only. UNIQUE (user_id, entry_type, period_month) where entry_type = `CREDIT`.
- `holidays` — holiday_date (PK), name.

**Payroll**
- `payroll_runs` — id, month (first day), department_id (nullable), status (`OPEN|CLOSED`), generated_by, params `jsonb`.
- `payroll_records` (v) — id, run_id, user_id, month, gross, tasks_total, tasks_approved, completion_pct `NUMERIC(5,2)`, retention_days, unpaid_days, unpaid_deduction, retention_amount, statutory_total, net_payable, present_days, working_days, default_release_amount, task_linked_amount, task_unlocked_amount, released_pct `NUMERIC(5,2)`, tranche1_amount, tranche2_amount, paid_amount, tranche1_paid_at, status (`GENERATED|APPROVED|REJECTED|RELEASED`), approved_by, approved_at, released_by, released_at, reject_note, snapshot `jsonb`. UNIQUE (user_id, month).
- `payroll_lines` — id, record_id, kind (`EARNING|DEDUCTION|RETENTION|ADJUSTMENT`), label, amount.
- `payroll_retentions` — id, record_id, user_id, days, amount, status (`HELD|RELEASED|FORFEITED`), decided_by, decided_at, note, released_in_record_id.

**System**
- `system_settings` — key (PK), value `jsonb`, updated_by, updated_at. Keys are grouped by prefix: `company.*, payroll.*, leave.*, attendance.*, security.*, appearance.*, tasks.*`.
- `notifications` — id, user_id, type, title, body, link, read_at, created_at.
- `audit_logs` — id (bigserial), at, actor_id, actor_role, action, entity_type, entity_id, before `jsonb`, after `jsonb`, ip, user_agent, request_id. Append-only (trigger blocks UPDATE and DELETE).
- `backup_runs` — id, started_at, finished_at, size_bytes, status, location, triggered_by.

**Indexes (minimum):** every FK; `tasks(assignee_id, status)`, `tasks(manager_id, status)`, `tasks(deadline_at)`; `attendance_records(user_id, work_date)`, `attendance_records(approval_status, work_date)`; `leave_requests(user_id, status)`, `leave_requests(manager_id, status)`; `payroll_records(month, status)`; `audit_logs(at)`, `audit_logs(entity_type, entity_id)`, `audit_logs(actor_id, at)`; `notifications(user_id, read_at)`.

## 4. Security design

- **Login:** `POST /auth/login` checks lockout, verifies BCrypt, returns `{accessToken, expiresIn, user}` and sets the refresh cookie (`HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth`). "Remember me" lengthens refresh expiry (for example 30 days instead of the session timeout).
- **Access token:** JWT (HS256 with `JWT_SECRET`, or RS256 if you prefer keys), 15-minute life by default (capped by `security.sessionTimeout`). Claims: `sub` (user id), `role`, `tv` (token version), `jti`. A filter compares `tv` and the account status with the DB row (cached for 30 seconds, evicted on change), so offboarding, demotion, password change and lockout take effect on the next request, not after 15 minutes (BR-S5).
- **Refresh:** rotation with a **20-second grace window**: a token that was rotated less than 20 seconds ago is accepted again and returns the same successor, because the 20 static pages and parallel tabs can send the same cookie at once. Reuse after the window revokes the whole token family and logs a security event.
- **CSP and inline handlers:** the frontend uses inline `onclick` attributes, so the CSP is `script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'` for now, with `object-src 'none'; base-uri 'self'; frame-ancestors 'none'`. All user data is inserted with `textContent` (RULES §9), which is the real XSS defence. Removing inline handlers and the `unsafe-inline` is a later hardening task. CDN `<script>` and `<link>` tags get `integrity` (SRI) and `crossorigin` attributes.
- **Trusted proxy:** `APP_TRUSTED_PROXY=true` makes the app use the client IP from `X-Forwarded-For` (Spring `server.forward-headers-strategy=native` on Render). When false the header is ignored, so rate limits and office-IP matching cannot be spoofed. Rate limits are per IP and per account email.
- **Login errors:** same message for unknown email, wrong password and locked account (BR-S9); the lockout is visible to the owner only by email or admin.
- **Ownership resolution (IDOR):** `TeamScope` resolves the owning user per resource: task → assignee (and `manager_id`), attendance and leave → `user_id`, payroll record → `user_id`, document and bank account → `user_id`, notification → `user_id`, task attachment → task's assignee. Manager scope = `users.manager_id = me`.
- **Uploads:** check magic bytes against the claimed type, `Content-Disposition: attachment` with a sanitised name on download, `nosniff`, `spring.servlet.multipart.max-file-size=5MB` and `max-request-size=6MB`.
- **Responses:** `Cache-Control: no-store` on auth responses, temporary-password responses, payslips and exports. Audit redaction follows BR-S6.
- **Authorization layers:** (1) URL rules in `SecurityConfig`, (2) `@PreAuthorize("hasRole(...)")`, (3) a **scope check** in each service: `TeamScope.assertCanAccess(currentUser, targetUserId)` (Admin: all; Manager: own team; Employee: self). Missing the third layer is the classic IDOR bug, so every service method that takes an id must call it and every endpoint test must try another user's id.
- **Password policy:** length and complexity from settings. Changing a password revokes all refresh tokens.
- **Files:** upload → validate → store with a random key → record metadata. Download → authorize → issue a 60-second signed URL (or stream through the app for local storage).
- **PII:** bank account numbers encrypted with AES-GCM (`APP_ENCRYPTION_KEY`, random IV stored with the ciphertext). Responses return `account_last4` only.

## 5. Key flows

**Attendance check-in**
`POST /attendance/check-in {lat, lng, accuracyM, devicePlatform}` → user from token; server time via `BusinessClock`; read IP and user agent server-side → geofence match against active `office_locations` → derive work mode and punctuality → if refused by settings, return 422 `ATTENDANCE_REMOTE_NOT_ALLOWED` → insert (unique per day gives 409 on duplicate) → status `PENDING` or auto-`APPROVED` by setting → notify the manager → audit.

**Task approval**
Assignee reaches progress 100 or calls submit → `PENDING_APPROVAL` → notify manager → `POST /tasks/{id}/approve` (must be the task's manager) → `APPROVED`, activity row, notification, audit. Payroll later counts only `APPROVED`.

**Leave approval**
Employee applies → working-day count → balance check sets `insufficient_balance` → manager notified. Approve: lock the user's ledger rows, re-check balance, write `DEBIT`, set `APPROVED`, all in one transaction. Insufficient balance → only `asUnpaid=true` is allowed.

**Payroll run**
1. `POST /payroll/runs {month, departmentId?, options}`.
2. For each active user in scope with role EMPLOYEE or MANAGER and `base_salary > 0` (D23), `PayrollInputsAssembler` gathers: gross, tasks in month (total and approved), absent working days, unpaid leave days, statutory deductions, and settings.
3. Reject if the month has not ended and `payroll.allowMidMonthGeneration` is false (`PAYROLL_MONTH_NOT_ENDED`). Block if attendance records or leave requests overlapping the month are still `PENDING` (BR-P13, `PAYROLL_PENDING_ITEMS`). Apply the cutoff rule of BR-P3.
4. `PayrollCalculator` returns a `PayrollResult` (pure).
5. Persist record, lines, snapshot, and a `HELD` retention if days > 0 (2-day retentions from last month auto-release as an earning line).
6. Approver approves or rejects with a note (manager of the employee; Admin for MANAGER-role employees; never the owner, D23).
7. Admin `release-default` marks tranche 1 paid (allowed while GENERATED or APPROVED); `release` marks the remainder paid after APPROVED and sets `RELEASED` (BR-P10, D21).
8. A manually released 7-day retention becomes an EARNING line "Retention released" in the next generated run; forfeit adds nothing (BR-P4).

**Retention decision**
`POST /payroll/retentions/{id}/release|forfeit {note}` (own-team Manager or Admin) → status change → audit → notify employee.

## 6. Scheduled jobs (built for hosts that sleep)

Free hosts sleep, so a cron may never fire. Every job is **idempotent with catch-up**:

| Job | Schedule | Catch-up behaviour |
|---|---|---|
| Monthly leave credit | 1st of month 00:10 IST | On startup and on leave reads, `ensureCreditsUpTo(today)` inserts any missing monthly `CREDIT` rows (unique constraint makes repeats safe) |
| Annual reset | 1 April | Same `ensure…` method writes `RESET` and `CARRY_FORWARD` once per leave year |
| Close open attendance | Daily 23:55 IST | On startup, close all older open records with `MISSED_CHECKOUT` |
| Deadline reminders | Hourly | Notifies tasks due in the next 24 hours, once per task (flag in `task_activities`) |
| Purge expired tokens | Daily | Deletes expired refresh and reset tokens |

Single instance only. If you ever scale out, add ShedLock. Do not add it now.

## 7. Cross-cutting

- **Audit:** an `@Audited(action, entityType)` annotation plus an aspect captures actor, request ID, IP, before and after. For complex diffs call `AuditService` explicitly.
- **Errors:** one `@RestControllerAdvice` maps domain exceptions to `ProblemDetail` with a stable `code`.
- **Pagination:** a shared `PageResponse<T>`; sort fields are allowlisted per endpoint.
- **Exports:** `GET …/export?format=csv|xlsx|pdf` streams. Same filters as the list endpoint. Audited.
- **Notifications:** rows in `notifications`, polled by the frontend (`GET /notifications?unreadOnly=true`, every 60 seconds). Email is optional and respects user preferences.
- **Observability:** JSON logs with request ID, `/actuator/health` (with DB check), basic Micrometer metrics (not exposed publicly).
- **Testing seam:** `BusinessClock` is injectable, so tests set "today".

## 8. Error codes (stable, used by the frontend)

`AUTH_INVALID_CREDENTIALS, AUTH_LOCKED, AUTH_PASSWORD_POLICY, AUTH_MUST_CHANGE_PASSWORD, FORBIDDEN_SCOPE, NOT_FOUND, VALIDATION_FAILED, CONFLICT_VERSION, TASK_INVALID_TRANSITION, TASK_NOT_YOUR_APPROVAL, ATTENDANCE_ALREADY_CHECKED_IN, ATTENDANCE_REMOTE_NOT_ALLOWED, ATTENDANCE_GPS_REQUIRED, LEAVE_OVERLAP, LEAVE_INSUFFICIENT_BALANCE, LEAVE_INVALID_RANGE, PAYROLL_PENDING_ITEMS, PAYROLL_MONTH_NOT_ENDED, PAYROLL_SELF_APPROVAL, PAYROLL_ALREADY_GENERATED, PAYROLL_INVALID_TRANSITION, MANAGER_HAS_TEAM, MANAGER_HAS_OPEN_APPROVALS, LAST_ADMIN, USER_HAS_PAYROLL, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, RATE_LIMITED`.
