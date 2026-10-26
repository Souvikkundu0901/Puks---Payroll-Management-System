# TECHSTACK — Cloud-Based Payroll Management System

Versions were checked in October 2026. At project creation, use the **latest patch** of the minor listed. If any pin below conflicts with start.spring.io, trust start.spring.io.

## Core

| Concern | Choice | Why |
|---|---|---|
| Language / JVM | Java 25 (LTS). Fallback: Java 21 | Spring Boot 4.1 supports Java 17–26. 25 is the recommended LTS. |
| Framework | Spring Boot **4.1.x** (4.1.1 as of Aug 2026) | 3.5 lost open-source support on 30 Jun 2026. 4.0 ends 31 Dec 2026. 4.1 is supported to 31 Jul 2027. |
| Build | Maven (wrapper checked in) | Simple, agent-friendly. Gradle is fine if you prefer it. |
| Web | Spring MVC (servlet), not WebFlux | Blocking JPA, simple model. |
| Database | PostgreSQL 16 (works on Neon / Supabase / Aiven) | Transactional, `NUMERIC` for money, `JSONB` for snapshots. |
| Data access | Spring Data JPA (Hibernate) + a few native queries for reports | |
| Migrations | Flyway. `spring.jpa.hibernate.ddl-auto=validate` always | Schema is code-reviewed. |
| Security | Spring Security 7 (comes with Boot 4), method security (`@PreAuthorize`) | |
| Tokens | Short-lived JWT access token (Nimbus, built into spring-security-oauth2-jose) + rotating refresh token in an HttpOnly Secure cookie, stored hashed in DB | No extra JWT library. Avoids the Jackson 2 vs 3 mismatch some JWT libraries have. |
| Password hashing | BCrypt (strength 12) via `DelegatingPasswordEncoder` | |
| Validation | Jakarta Bean Validation | |
| Mapping | MapStruct for entity↔DTO. DTOs are Java `record`s | |
| API docs | springdoc-openapi (use the release line compatible with Boot 4) | Gives Swagger UI in dev only. |
| Money | `BigDecimal`, `NUMERIC(12,2)`, `RoundingMode.HALF_UP` | Never `double`. |
| Time | `Instant` / `timestamptz` stored in UTC. Business dates in `Asia/Kolkata` via a single `BusinessClock` bean | Makes tests deterministic. |

> **Boot 4 caution for the agent:** Boot 4 modularised its starters and moved to Jackson 3 (package `tools.jackson`). Do not guess artifact names from older tutorials. Generate the project from start.spring.io with the dependencies below, then read what it produced.

**start.spring.io dependencies:** Spring Web, Spring Security, OAuth2 Resource Server, Spring Data JPA, Validation, Flyway Migration, PostgreSQL Driver, Actuator, Mail (optional, Phase 10), Lombok (optional), Testcontainers, Spring Boot DevTools (dev only).

## Libraries added by hand

| Need | Library | Notes |
|---|---|---|
| PDF (payslips, reports) | OpenPDF | Light. Avoid headless-browser PDF on 512 MB hosts. |
| Excel export | Apache POI with `SXSSFWorkbook` (streaming) | Keeps memory low. |
| CSV import/export | Apache Commons CSV | |
| Login rate limiting | Bucket4j (in-memory) | Single instance only. |
| Object storage | AWS SDK v2 `s3` client behind a `StorageService` interface | Works with any S3-compatible store. Local-disk implementation for dev. |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers (`postgres:16-alpine`), Spring Security Test | |

## Not to add (keep the footprint small)

No Redis, no Kafka or RabbitMQ, no microservices, no GraphQL, no Spring Cloud, no Hibernate second-level cache, no WebSockets (polling is enough for notification counts), no extra scheduler library (Spring `@Scheduled`; single instance).

## Frontend (existing, do not rewrite)

HTML5, CSS3, Bootstrap 5.3.2, Bootstrap Icons 1.11.1, Google Font Inter, vanilla JS. Added by the integration work: a small shared `js/api.js` and `js/auth.js`. Spring Boot serves the frontend files from `src/main/resources/static` (single origin, no CORS, simplest cookies).

## Profiles and config

- `local`: local PostgreSQL via Docker Compose, local-disk storage, Swagger on, verbose SQL off.
- `test`: Testcontainers.
- `prod`: everything from environment variables. Swagger off, actuator limited to `/health`.

Environment variables (names are fixed; values never committed):
`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FLYWAY_URL` (direct, non-pooled connection), `JWT_SECRET` (at least 32 random bytes, base64), `APP_ENCRYPTION_KEY` (32 bytes, base64, for bank account encryption), `STORAGE_ENDPOINT`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_REGION`, `APP_BASE_URL`, `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD` (used once to seed the first admin, then ignored), `BACKUP_WEBHOOK_SECRET`, `APP_TRUSTED_PROXY` (true on Render), `PORT` (read through `server.port=${PORT:8080}`), `MAIL_*` (optional).

## Test-only additions
ArchUnit (architecture tests: calculator purity, layering), Testcontainers PostgreSQL, Spring Security Test. Add ArchUnit in Phase 7.

## Runtime sizing for 512 MB hosts

`-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError`
HikariCP `maximumPoolSize=5`, `minimumIdle=1`. `spring.main.lazy-initialization` stays off (fail fast). Turn on `spring.threads.virtual.enabled=true` only if memory allows.

## Quality gates

`./mvnw verify` must pass: unit and integration tests, plus a build that fails if Flyway and the JPA model disagree (`ddl-auto=validate`). The CI workflow (GitHub Actions) runs the same command on every push.
