# RULES.md — standing rules for the coding agent

(Antigravity: place this file at the repo root as `AGENTS.md`, or at `.agents/rules/rules.md`. Use ONE location, not both. Limit: 12,000 characters.)

## 0. Start of every session
1. Read `docs/MEMORY.md` first. Then `docs/PHASES.md` for the current phase. Open `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/DESIGN.md` only for the parts the task touches.
2. If a task conflicts with the docs, STOP and ask. Do not silently pick one.
3. Work on ONE phase at a time. Never start the next phase until the current "Done when" list is fully ticked.
4. End of every session: update `docs/MEMORY.md` (status, decisions, gotchas, next step).

## 1. Source of truth
- Business rules: `docs/PRD.md` (BR-xx IDs). Numbers must match the golden tests (PRD §4) exactly.
- Do not invent business rules. If a rule is missing, record the gap in MEMORY.md "Open questions" and use the default listed in PRD §5.
- The existing frontend in `/frontend` is not to be redesigned. Change it only as DESIGN.md says: wire it to the API, remove mock data. Keep markup, CSS and the 9 themes intact.

## 2. Architecture
- Modular monolith, package root `com.octopyder.payroll`. Modules: `auth, people, org, task, attendance, leave, payroll, notification, audit, report, storage, common`.
- Layers: `controller → service → repository`. Controllers hold no business logic. Services own transactions (`@Transactional`). Repositories only talk to the DB.
- Other modules are called through their service interface, never through their repositories.
- Entities never leave the service layer. Controllers take and return DTO `record`s.
- The payroll calculator is a **pure class with no Spring or DB dependency**: `PayrollCalculator.calculate(PayrollInputs) → PayrollResult`. It is unit-tested against the golden cases.
- All "now" and "today" come from the injected `BusinessClock`. Never call `Instant.now()`, `LocalDate.now()` or `System.currentTimeMillis()` directly.

## 3. Database
- Schema changes ONLY through Flyway files `V{n}__{description}.sql`. Never edit an applied migration. Never use `ddl-auto` other than `validate`.
- Money: `NUMERIC(12,2)` and `BigDecimal` (`HALF_UP`). Never `float` or `double`. Percentages: `NUMERIC(5,2)`.
- All timestamps `timestamptz`. Use `DATE` for business dates (`work_date`, `leave start`).
- Primary keys: UUID (`gen_random_uuid()`), plus human codes (`EMP001`, `TSK001`) as unique columns.
- Add constraints in SQL (CHECK, UNIQUE, FK), not only in Java. Add indexes for every foreign key and every list filter.
- Add `@Version` to `tasks, leave_requests, payroll_records, attendance_records` to prevent double-approval races.
- `audit_logs` is append-only: no UPDATE or DELETE grants, enforced by a trigger.
- No hard deletes of people, tasks, attendance, leave, or payroll. Use status and soft-delete.

## 4. Security (non-negotiable)
- Role comes from the DB account only. Ignore any role sent by the client.
- Every endpoint has `@PreAuthorize` AND a service-level ownership/team check. Never trust an ID from the client to mean "mine". Cross-user and cross-team access must return 403 or 404 and has a test.
- Passwords: BCrypt strength 12. Never log, return, or store plaintext. Admin-created and admin-reset passwords are temporary: `must_change_password = true`. Show a temporary password once only.
- Access token is short-lived. Refresh token is random, stored hashed, rotated on use, delivered as HttpOnly + Secure + SameSite=Lax cookie. Revoke on logout and password change.
- Login: rate limit, lockout after N failures (settings), generic error message ("Invalid credentials"), audit both outcomes.
- Bank account numbers: AES-GCM encrypted at rest, return masked (`XXXX-XXXX-1234`). Do NOT store Aadhaar or PAN numbers as text. Documents are files in private storage and are downloaded through short-lived signed URLs after an authorization check.
- Uploads: check size (≤ 5 MB), content type and extension allowlist (pdf, png, jpg, jpeg), random storage keys, never trust the filename, never serve from a public path.
- Use parameterised queries only. No string-built SQL.
- Never log secrets, tokens, passwords, bank numbers, or full coordinates. Logs are structured and carry a request ID.
- Secrets only from environment variables. `.env` is git-ignored. Commit `.env.example` with fake values.
- Security headers: HSTS, `X-Content-Type-Options`, frame denial, a CSP that allows the CDNs the frontend uses (jsdelivr, Google Fonts; `unsafe-inline` scripts allowed for now, see ARCHITECTURE §4), SRI on CDN tags. CORS closed (same origin).
- Actuator: only `/actuator/health` is public.
- Tokens carry a token version; offboarding, role change and password change take effect at once (BR-S5). Approvals need a note where BR-S7 says so. Nobody approves their own payroll (D23). Never log or audit passwords, hashes, tokens or bank numbers (BR-S6). Trust `X-Forwarded-For` only when `APP_TRUSTED_PROXY=true`. CSV exports escape cells that start with `= + - @`.

## 5. API conventions
- Base path `/api/v1`. JSON camelCase. Dates ISO-8601 (`2026-07-21`, `2026-07-21T09:12:00+05:30`). Money as JSON numbers with 2 decimals (computed server-side only).
- Lists: `?page=0&size=10&sort=field,dir` and a response `{items, page, size, totalItems, totalPages}`. Max size 100.
- Errors: RFC 9457 `ProblemDetail` with a stable `code` field (for example `LEAVE_INSUFFICIENT_BALANCE`) and field errors for validation. Never leak stack traces.
- Status codes: 201 on create, 204 on delete/no-content, 400 validation, 401 unauthenticated, 403 forbidden, 404 not found (also used to hide other users' records), 409 conflict or optimistic-lock failure, 422 business-rule violation.
- State changes are explicit action endpoints (`POST /tasks/{id}/approve`), not "PUT status". Every action checks the allowed source status and returns 409/422 otherwise.
- Every mutating endpoint writes an audit entry (PRD BR-S2).
- Enum values are `UPPER_SNAKE` in the API. The frontend maps them to display labels (DESIGN.md §2).

## 6. Code quality
- Java records for DTOs. Constructor injection. No field `@Autowired`. No wildcard imports. Methods under about 40 lines.
- No business logic in SQL beyond constraints and reporting aggregates.
- No `TODO` without a matching line in MEMORY.md "Open questions".
- No new dependency that is not in `TECHSTACK.md` without asking. Never add: Redis, Kafka, microservices, GraphQL.
- Keep memory low (512 MB host): stream exports (SXSSF), paginate everything, no loading whole tables.

## 7. Testing (required to finish a phase)
- Unit tests for every BR rule touched. Payroll: all golden cases and boundaries in PRD §4.
- Integration tests with Testcontainers PostgreSQL for each repository-heavy service and each controller (MockMvc, with real security config).
- Authorization tests per endpoint: no token → 401, wrong role → 403, other user's data → 403/404, correct role → success.
- Tests use a fixed `BusinessClock`. No sleeps, no dependence on today's date.
- `./mvnw verify` must be green before a phase is called done. Do not disable, skip, or weaken a test to make it pass. If a test is wrong, say why and fix it openly.

## 8. Git and delivery
- Small commits, imperative messages (`feat(leave): add monthly credit job`). One logical change each.
- Never commit secrets, `target/`, local DB files, or uploaded files.
- Each phase ends with: green build, updated `docs/MEMORY.md`, the phase's "Done when" list ticked in the final message, and a short manual test script (curl or Swagger steps).

## 9. Frontend wiring
- Add only `frontend/js/api.js` (fetch wrapper: base URL, JSON, 401 → refresh once → login redirect, ProblemDetail → toast) and `frontend/js/auth.js` (guard: no session → `index.html`; wrong role for the folder → own dashboard). Include them in the pages that need them.
- Replace hard-coded or mock data with API calls. Keep existing element IDs and class names so the CSS and themes keep working.
- Never put tokens in `localStorage`. Access token lives in memory; refresh cookie is HttpOnly.
- `index.html`: keep the role `<select>` only as a visual hint. Redirect by the role the server returns.
- All data inserted into the DOM must be escaped (`textContent`, not `innerHTML` with user data).

## 10. When unsure
Ask one short, specific question and offer a default. Prefer a small safe choice over a clever one. Never claim a test passed without running it, and never say "done" with items unticked.

## 11. Workflow, TDD, Code Minimization & Security Agent Role
- **TDD Mandatory:** Every feature must follow Test-Driven Development (TDD). Write unit and integration tests first before implementing functionality.
- **Comprehensive Testing:** Every feature requires both unit tests and integration tests (MockMvc + Testcontainers PostgreSQL).
- **Code Minimization:** Keep code minimal, concise, and focused. Avoid unnecessary abstractions, boilerplate, or overengineering.
- **Security Agent Red-Teaming:** Immediately after completing any feature, switch into the role of a dedicated security agent / adversary. Actively attempt to break the implementation (probing IDOR, token lifecycle, auth bypasses, edge cases, input validation, concurrency/races).
- **No Unprompted Fixes:** Report all discovered bugs, security gaps, and flaws clearly to the user. **Do NOT fix them without explicit instructions from the user.**
