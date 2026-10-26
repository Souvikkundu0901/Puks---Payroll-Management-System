# DESIGN — Frontend ↔ Backend contract

Derived from reading all 20 pages. Frontend root: `CloudPayrollSystem - Frontend/` with `index.html`, `admin/` (8), `manager/` (5), `employee/` (6). Every page is static with inline JS and mock data, and loads Bootstrap 5.3.2 from jsDelivr. Desktop and mobile layouts live in the same file, so each list is rendered twice (table and cards). Wire both from the same data.

## 1. Conventions

**Base URL:** `/api/v1` (same origin).
**Auth:** `POST /auth/login` returns `{accessToken, expiresIn, user:{id, code, name, role, mustChangePassword, theme}}` and sets an HttpOnly refresh cookie. `api.js` keeps the access token in memory, adds `Authorization: Bearer …`, and on 401 calls `POST /auth/refresh` once, then redirects to `index.html`. Page reload: call `/auth/refresh` first, then `/auth/me`.
**Landing by role:** `ADMIN → admin/dashboard.html`, `MANAGER → manager/dashboard.html`, `EMPLOYEE → employee/dashboard.html`.
**Guard:** each page calls `Auth.require('ADMIN')` (and so on). Wrong role goes to the user's own dashboard. Logout buttons call `POST /auth/logout`.
**Errors:** `{type, title, status, detail, code, errors:[{field, message}]}`. Show `detail` in the existing toast or alert. Highlight fields from `errors`.
**Lists:** `?page&size&sort&q&…filters` → `{items, page, size, totalItems, totalPages}`. Desktop uses the page links ("Showing 1-10 of 125"). Mobile "Load More" requests the next page and appends.
**Dates:** `YYYY-MM-DD` and ISO-8601 with offset. The frontend formats for display (`en-GB`, for example "21 Jul 2026").
**Money:** JSON numbers with 2 decimals. Format with `toLocaleString('en-IN')` and the ₹ symbol.
**Theme:** `localStorage.payrollTheme` stays as is. Optionally also `PUT /me/preferences {theme}` and apply `user.theme` after login.

## 2. Enum ↔ display label

| API value | Display label(s) in the UI |
|---|---|
| Task `ASSIGNED` | Pending / Assigned / Not Started |
| Task `IN_PROGRESS` | In Progress |
| Task `PENDING_APPROVAL` | Pending Mgr Approval / Pending Approval / Awaiting Verification |
| Task `APPROVED` | Manager Approved / Completed |
| Task `REJECTED` | Rejected |
| Task `CANCELLED` | Cancelled (hidden by default) |
| Task `overdue=true` (derived flag) | Overdue |
| Priority `HIGH/MEDIUM/LOW` | High / Medium / Low |
| Attendance approval `PENDING/APPROVED/REJECTED` | Pending / Approved / Rejected |
| Attendance punctuality `PRESENT/LATE/HALF_DAY` | Present / Late / Half Day (Absent, Leave, Holiday, Weekend are calendar-derived, not stored) |
| Work mode `OFFICE/REMOTE/HYBRID` | Office / Remote / Hybrid |
| Leave type `PAID/CASUAL/SICK/EMERGENCY/UNPAID` | Paid Leave (PL) / Casual / Sick (also called "Medical") / Emergency / Unpaid |
| Leave status | Pending / Approved / Rejected / Cancelled |
| Payroll status `GENERATED/APPROVED/REJECTED/RELEASED` | Generated / Approved / Rejected / Released ("Paid" on the employee page) |
| Retention status `HELD/RELEASED/FORFEITED` | Retained / Released / Forfeited |
| User status `ACTIVE/INACTIVE/RESIGNED` | Active / Inactive / Resigned. "On Leave" is derived (`onLeaveToday=true`), not stored. |

Employee salary-page banner "Partially Retained" = record has `retentionAmount > 0` and status is not `RELEASED`.

## 3. API catalog

Roles: A = Admin, M = Manager (own team unless stated), E = Employee (self). Everything requires auth except `/auth/login`, `/auth/refresh`, `/auth/forgot-password`, `/auth/reset-password`, `/actuator/health`.

**Auth**
`POST /auth/login` · `POST /auth/refresh` · `POST /auth/logout` · `GET /auth/me` · `POST /auth/change-password {current, new}` · `POST /auth/forgot-password {email}` · `POST /auth/reset-password {token, new}`

**People**
| Endpoint | Roles | Notes |
|---|---|---|
| `GET /employees` | A, M | Filters: `q, departmentId, managerId, status, role, page, size`. Returns `onLeaveToday`. |
| `POST /employees` | A | Body: all Add Employee fields (below). Returns the temporary password **once**. |
| `GET /employees/{id}` | A, M, E(self) | Overview |
| `PUT /employees/{id}` | A | Name, department, designation, manager, salary, type, status… |
| `POST /employees/{id}/offboard {exitDate, status}` | A | Disables login, keeps history |
| `POST /employees/{id}/reset-password` | A | Generates a temporary password, forces change |
| `DELETE /employees/{id}` | A | Soft delete. 409 if payroll exists (offboard instead). |
| `GET /employees/{id}/summary/{attendance\|tasks\|leaves\|payroll\|documents}` | A, M, E(self) | The six tabs of the employee view modal |
| `POST /employees/import` (CSV) · `GET /employees/export?format=` | A | |
| `GET/POST/PUT /managers`, `GET /managers/{id}`, `GET /managers/{id}/team`, `GET /managers/{id}/performance` | A | Add Manager adds `experience` |
| `POST /managers/{id}/team {employeeIds}` · `DELETE /managers/{id}/team/{employeeId}` | A | Assign / remove |
| `DELETE /managers/{id}` | A | 409 `MANAGER_HAS_TEAM` when team > 0 |
| `GET/POST/PUT /departments` | A (read: all) | |
| `GET/PUT /me/profile` · `POST /me/photo` · `PUT /me/preferences` | all | Preferences: theme and three notification toggles |
| `GET/POST /me/documents` · `GET /documents/{id}/download` | all (own); A all | Signed URL |
| `PUT /employees/{id}/bank-account` · `PUT /employees/{id}/deductions` | A | Not in the UI yet |

**Tasks**
| Endpoint | Roles |
|---|---|
| `GET /tasks` (filters `status, priority, employeeId, managerId, departmentId, dueToday, overdue, q`) | A all · M team · E own |
| `POST /tasks {title, description, priority, assigneeId, managerId?, departmentId?, deadlineAt, estHours?}` | A, M |
| `GET /tasks/{id}` (+ `/activities`, `/comments`) · `POST /tasks/{id}/comments` | scoped |
| `PUT /tasks/{id}` · `DELETE /tasks/{id}` (cancel) | A, M(team) |
| `POST /tasks/{id}/start` · `PATCH /tasks/{id}/progress {progress}` · `POST /tasks/{id}/submit` | E(own); M may PATCH progress |
| `POST /tasks/{id}/approve` · `POST /tasks/{id}/reject {note}` | **M (task's manager) only** |
| `POST /tasks/{id}/attachments` | E(own), M, A |
| `GET /tasks/summary` · `POST /tasks/import` · `GET /tasks/export` | scoped / A |

**Attendance**
| Endpoint | Roles |
|---|---|
| `POST /attendance/check-in {latitude, longitude, accuracyM, devicePlatform}` · `POST /attendance/check-out {latitude?, longitude?}` | E |
| `GET /attendance/me/today` · `GET /attendance/me?month=YYYY-MM` (records, stats, calendar days) | E |
| `GET /attendance` (filters `employeeId, departmentId, from, to, period=today\|week\|month, approvalStatus, punctuality`) | A, M(team) |
| `GET /attendance/pending` | M(team), A |
| `POST /attendance/{id}/approve` · `/reject {note}` · `POST /attendance/bulk-decision {ids, decision, note}` | A, M(team) |
| `GET /attendance/employee/{userId}/timeline?days=30` | A, M(team) |
| `GET /attendance/summary?date=` | A, M |
| `GET /attendance/export?format=` | A |

**Leave**
| Endpoint | Roles |
|---|---|
| `GET /leave/me/balance` (`annualAllowance, earned, used, available, carryForward`) · `GET /leave/me/ledger` · `GET /leave/me/requests` | E |
| `POST /leave/requests` (multipart: type, startDate, endDate, reason, attachment?) | E |
| `POST /leave/requests/{id}/cancel` | E(own) |
| `GET /leave/requests` (filters `status, type, departmentId, month, employeeId`) | A, M(team) |
| `POST /leave/requests/{id}/approve {asUnpaid?, note?}` · `/reject {note}` · `POST /leave/requests/bulk-decision` | A, M(team) |
| `GET /leave/ledger/{userId}` (+ balance) · `GET /leave/summary` | A, M(team) |
| `GET /holidays?year=` (all) · `POST/DELETE /holidays` (A) · `GET /leave/export` (A) | |

**Payroll**
| Endpoint | Roles |
|---|---|
| `POST /payroll/runs {month, departmentId?, treatPendingAsPresent?, recalculate?}` | A |
| `POST /payroll/preview {month, userId}` (no persistence) | A |
| `GET /payroll/records` (filters `month, departmentId, status, band=GTE70\|B50_69\|LT50, q`) | A all · M team |
| `GET /payroll/records/{id}` (full breakup and snapshot) | A, M(team), E(own) |
| `POST /payroll/records/{id}/approve` · `/reject {note}` | M(team); A for managers' own payroll |
| `POST /payroll/records/{id}/release-default` (tranche 1) · `POST /payroll/records/{id}/release` (remainder, after APPROVED) · `POST /payroll/runs/{id}/release-all` | A |
| `GET /payroll/summary?month=` (total, released, pending, retained, paid count, per-department) | A, M |
| `GET /payroll/me` (history by `year`) · `GET /payroll/me/{month}` | E |
| `GET /payroll/records/{id}/payslip.pdf` | A, E(own). Managers get 403 (no payslip PDF, no bank details; D26) |
| `GET /payroll/retentions?status=` · `POST /payroll/retentions/{id}/release\|forfeit {note}` | A, M(team) |
| `GET /payroll/export?format=pdf\|xlsx\|csv` | A |

**People helpers:** `GET /employees/kpis` (counts for the KPI cards) · `GET /employees?q&departmentId&status&experience=0-1|1-3|3-5|5%2B` · `POST /employees/{id}/documents/{docId}/verify` (Admin) · `GET /employees/{id}/id-card.pdf` · `PUT /employees/{id}/skills` · `GET /managers/{id}/summary` (includes `avgTeamRating`, null until ratings exist).

**Settings, reports, system**
`GET /settings` · `PUT /settings/{group}` (company, payroll, leave, attendance, security, appearance) · `POST /settings/logo` · `GET/POST/PUT/DELETE /settings/office-locations` · `GET /backups` · `POST /backups/webhook` (secured by a shared secret, called by the GitHub Actions job) · `GET /audit-logs` (A; filters `actorId, action, entityType, from, to`) · `GET /reports/{employees\|attendance\|tasks\|payroll\|leave}?format=` · `GET /dashboard/{admin\|manager\|employee}` · `GET /calendar/events?from&to` · `GET /notifications?unreadOnly=` · `POST /notifications/{id}/read` · `POST /notifications/read-all`

### Key shapes

`POST /employees` body (Add Employee modal): `{firstName, lastName, gender, dateOfBirth, phone, email, address, departmentId, designation, managerId, joiningDate, baseSalary, employmentType, role}`. The form's "Username" and "Password" fields: the email is the username. The password field becomes optional (empty means generate a temporary one). Employee code is generated (`EMP004` is the form's default).

`POST /attendance/check-in` response: `{id, workDate, checkInAt, workMode, punctuality, approvalStatus:"PENDING", latitude, longitude, device}` — feeds the modal's "Status / Location / Timestamp / Device Data".

Payroll record (used by employee salary page, manager approval cards, admin table and modals):
`{id, month, employee:{id, code, name, department}, presentDays, workingDays, taskLinkedAmount, defaultReleaseAmount, taskUnlockedAmount, releasedPct, paidAmount, gross, completionPct, tasksTotal, tasksApproved, retentionDays, dayRate, unpaidDays, unpaidDeduction, retentionAmount, statutoryTotal, netPayable, tranche1, tranche2, status, lines:[{kind,label,amount}], bank:{bankName, accountMasked, ifsc}, paymentDate, transactionRef}`.

## 4. Page-by-page mapping

### Shared
- `index.html` (login): `POST /auth/login` with `email, password, rememberMe`. Keep the role `<select>` as a hint only; redirect by the server's role. "Forgot password?" goes to a new page (D15). If `mustChangePassword`, show a change-password step first. Error `AUTH_LOCKED` shows the lockout message.
- All pages: theme selector (localStorage), logout modal → `POST /auth/logout`, notification bell → `GET /notifications`.

### Employee (6)
| Page | Data and actions | Endpoints |
|---|---|---|
| `dashboard.html` | Welcome (name, code, department, role) · today's attendance status · assigned/pending/completed counts · attendance % this cycle · leave usage "2 / 12" · Mark Attendance modal · Apply Leave modal · salary slip modal (base, default released, retained, net) | `GET /dashboard/employee`, `POST /attendance/check-in`, `POST /leave/requests`, `GET /payroll/me/{month}` |
| `attendance.html` | Today card (status, check-in/out, hours, overtime) · Check Out button · monthly stats (%, working days, present, absent, late, half day, leaves) · calendar (Present/Absent/Leave/Holiday/Weekend) · history table (date, in, out, hours, location, status, manager approval) · Export | `GET /attendance/me/today`, `GET /attendance/me?month=`, check-in/out, `GET /attendance/me?export` |
| `leave.html` | Balance cards (annual allowance, earned, used, available) · apply form (type, start, end, reason, attachment) · calendar with approved leave and holidays · history (date, type, reason, status, manager, approved on) · Filter | `GET /leave/me/balance`, `POST /leave/requests`, `GET /leave/me/requests`, `GET /holidays` |
| `salary.html` | Month heading · gross / deductions / net / status · performance panel (approved completion %, default 50%, unlocked, retained) · earnings and deductions tables · bank info (masked) · payslip View/Print/PDF · history by year | `GET /payroll/me/{month}`, `GET /payroll/me?year=`, `GET /payroll/records/{id}/payslip.pdf` |
| `tasks.html` | Counters · search · filters (All, Pending, In Progress, Completed, High Priority, Due Today) · cards (title, priority, description, assigned by, deadline, est. time, status, progress) · actions Start, Continue, Submit, View Details, Upload · deadline calendar | `GET /tasks`, `POST /tasks/{id}/start`, `PATCH …/progress`, `POST …/submit`, `POST …/attachments` |
| `profile.html` | Header (name, designation, status, ID, email, phone) · skills · tabs Overview, Documents, Account Settings · edit modal · photo upload · change password · 2FA and notification toggles · ID card download | `GET/PUT /me/profile`, `POST /me/photo`, `GET /me/documents`, `POST /auth/change-password`, `PUT /me/preferences` |

Employee edit-profile modal currently edits name and email. Per D14 only phone, address and emergency contact are saved. Make name and email read-only, or send a change request to Admin later.

### Manager (5) — layout differs from Admin (top nav, glass cards)
| Page | Data and actions | Endpoints |
|---|---|---|
| `dashboard.html` | Counters (employees under me, active tasks, pending attendance, leave requests, salary approvals, tasks awaiting) · profile modal · team performance (per-employee %) · team attendance (present of total) · attendance requests · pending task approvals table (Approve / Reject / View) · recent activities · mini and master calendar · notifications · change password | `GET /dashboard/manager`, `GET /attendance/pending`, `POST /tasks/{id}/approve\|reject`, `GET /calendar/events`, `GET /notifications`, `POST /auth/change-password` |
| `assign-task.html` | Form (title, priority, description, assign-to, deadline) · team list · active tasks pipeline with progress select (0, 10, 20, 50, 90, 100), Edit, Delete, Approve | `POST /tasks`, `GET /tasks`, `PATCH …/progress`, `PUT/DELETE /tasks/{id}`, `POST …/approve`, `GET /managers/{id}/team` |
| `approve-attendance.html` | Filter (employee, date) · recent history · pending cards (check-in time, device, IP, lat/lng, geofence match) · Approve / Reject | `GET /attendance`, `GET /attendance/pending`, `POST /attendance/{id}/approve\|reject` |
| `review-leave.html` | Employee balance check and full ledger · recent history · pending cards (type, range, days, balance, reason, "Insufficient Balance" warning) · Approve / Reject / "Approve Unpaid" | `GET /leave/ledger/{userId}`, `GET /leave/requests`, `POST …/approve {asUnpaid}`, `…/reject` |
| `salary-approval.html` | Rules panel · "Select Payroll Month / Generate Final Batch" · recent disbursements · pending approvals (tasks %, attendance days, base, unpaid deduction, retention, final payable) · Reject / Approve Payout | `POST /payroll/runs` (D18), `GET /payroll/records?status=GENERATED`, `POST …/approve\|reject` |

### Admin (8)
| Page | Data and actions | Endpoints |
|---|---|---|
| `dashboard.html` | KPIs (employees +growth, managers, attendance present/absent/pending, pending leaves, tasks, payroll status and amount) · weekly attendance bars · salary donut (released/retained/pending) · department task completion · leave stats by type · recent activities · quick actions · mobile summary | `GET /dashboard/admin` |
| `employees.html` | KPIs (total, active, on leave, new this month) · search and filters (department, manager, status, role) · table and cards · Add / View (6 tabs) / Edit / Reset Password / Delete · Import / Export · pagination | `/employees…` as above |
| `managers.html` | KPIs (managers, employees managed, average team size, departments) · filters (department, status, experience, team size) · Add / View (5 tabs) / Edit / Assign Employees / Delete (blocked when team > 0) · Import / Export | `/managers…` |
| `payroll.html` | Cycle header · KPIs (total, released, pending, retained, paid x/y) · filters (department, status, task %) · table (employee, department, base, task %, released, retained, status) · Details modal · Release modal · Payslip modal · Generate Payroll modal with progress bar · Export (PDF/Excel/CSV) · rules panel · distribution and department charts | `GET /payroll/records`, `GET /payroll/summary`, `POST /payroll/runs`, `POST …/release`, payslip, export |
| `attendance.html` | Today cards · leave summary · records table (select, employee, date, time, GPS, device, status, actions) with Approve/Reject Selected · filters · location modal · history timeline · leave table with Approve / Reject / Ledger · leave filters · charts · Export (attendance and leave, PDF/Excel) | `/attendance…`, `/leave…` |
| `tasks.html` | KPIs (total, completed, pending, overdue) · filters (employee, manager, department, priority, status) · table and cards · Assign / View (Overview, Timeline, Progress, Comments) / Edit / Delete · Import / Export · status, department and monthly charts | `/tasks…`, `GET /tasks/{id}/activities`, `…/comments` |
| `profile.html` | Own profile, documents, 2FA toggle, change password, edit modal | `/me…`, `/auth/change-password` |
| `settings.html` | Company, Payroll, Leave, Attendance, Security, Appearance, Backup & Restore sections; Save / Reset / Refresh | `GET /settings`, `PUT /settings/{group}`, `POST /settings/logo`, `GET /backups` |

**Admin task approval:** the Admin task modal has "Update Progress (Employee Action Only)", and Admin has no Approve button. This is correct per BR-T4. Do not add one.

## 5. Settings key map (Admin → Settings page)

| UI field | Setting key | Default |
|---|---|---|
| Company name, GST, address, email, phone, website, logo | `company.*` | — |
| Salary cycle | `payroll.cycle` | MONTHLY (others disabled for now) |
| Working days per month | `payroll.workingDays` | 26 (informational) |
| *(new)* Day-rate divisor | `payroll.dayRateDivisor` | 30 |
| Salary generation date / release date | `payroll.generationDay`, `payroll.releaseDay` | 28th / 30th |
| Enable overtime | `payroll.overtimeEnabled` | false |
| Monthly leave credit | `leave.monthlyCredit` | 1 |
| Annual reset date | `leave.resetDate` | 04-01 |
| Maximum carry forward | `leave.carryForwardOnReset` | 0 (see D8) |
| Manager approval for leave | `leave.managerApproval` | true |
| Office start / end | `attendance.officeStart`, `attendance.officeEnd` | 09:00 / 18:00 |
| Late grace | `attendance.graceMinutes` | 15 |
| Geofence radius | on `office_locations.radius_m` (default 100) | 100 |
| GPS verification / Remote attendance / Manager approval | `attendance.gpsVerification`, `attendance.remoteAllowed`, `attendance.managerApproval` | true / true / true |
| 2FA required for admins | `security.adminTwoFactor` | false (until Phase 10) |
| Password min length and complexity | `security.passwordMinLength`, `security.passwordRules` | 8 / all four |
| Session timeout / max login attempts / auto lock | `security.sessionTimeout`, `security.maxLoginAttempts`, `security.autoLockMinutes` | 30 / 5 / 15 |
| UI animations, theme | `appearance.*` (also per-user preference) | — |
| Backup frequency, cloud auto-backup | `backup.frequency`, `backup.cloudAuto` (informational; the job is external) | daily |

### Settings keys added by the review
| UI element | Key | Default |
|---|---|---|
| Tasks with none due count as 100% | `payroll.noTasksCountsAs100` | true (D1) |
| Allow generation before month end (demo) | `payroll.allowMidMonthGeneration` | false |
| Salary breakup percentages | `payroll.breakupPercent` | Basic 50, HRA 20, Medical 10, Travel 10, Performance bonus 10 |
| Employees may assign tasks to themselves | `tasks.employeeSelfAssign` | false (D6) |
| Weekly off days | `attendance.weeklyOffDays` | SAT,SUN (D9) |
| Half-day threshold (minutes) | `attendance.halfDayMinutes` | 240 |
| Low GPS accuracy flag (metres) | `attendance.lowAccuracyM` | 200 |
| Include Admin users in payroll | `payroll.includeAdmins` | false (D23) |
| Leave year start | `leave.resetDate` | 04-01 |
| Carry forward limit | `leave.carryForwardOnReset` | 0 (D8) |

## 6. Changes required in the frontend (and nothing more)

1. Add `js/api.js` and `js/auth.js`. Reference them from every page (`../js/…` in subfolders).
2. `index.html`: real login, redirect by returned role, handle `mustChangePassword`.
3. Replace each page's mock arrays and hard-coded rows with API calls, and render with `textContent` or escaped templates.
4. Employee `executeMarkAttendance()` and `executeCheckOut()`: keep the browser geolocation call, then POST the coordinates. Show the server's response, not `new Date()`.
5. Replace every `alert('… successfully')` stub with the real call, and show the real result.
6. Manager password-change form (repeated in a modal on each page) calls `/auth/change-password`.
7. Remove client-side simulated delays (`simulateDataLoad`) or use them only as the real loading state.
8. Do not change CSS, themes, or layout. The only new UI elements are those listed here and in PRD §6 "Plan": change-password step on `index.html`, reject-note dialogs, progress control and Submit button on `employee/tasks.html`, Unpaid option in the leave form, date range and type in the admin leave modal, office-location and holiday sections in settings, Payroll setup tab in the admin employee view, audit-log and reports pages, forgot/reset pages. Each reuses the existing Bootstrap components.
9. Pages must read the progress, reject and note fields from the API; managers see payroll summaries only (no bank, no payslip PDF).
10. The employee dashboard salary modal and `employee/salary.html` must show the same numbers from `GET /payroll/me/{month}` (the dashboard mock values are removed).
