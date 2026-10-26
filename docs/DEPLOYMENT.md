# DEPLOYMENT — free options and the recommended setup

> Free tiers change often. The figures below were read from comparison pages dated September 2026 (sources at the end). **Confirm each limit on the provider's own pricing page before you depend on it.**
> Free hosting is fine for a college project, demo, or portfolio. It is **not** suitable for real employee payroll data: free tiers have no backup guarantees, no data-processing agreement, and sleep or expire. For real use, move to paid hosting first.

## 1. What your app needs

| Need | Why |
|---|---|
| Runs a Docker image (Java 25) | One Spring Boot container |
| HTTPS | Browsers only allow the geolocation API on HTTPS (or localhost), and cookies are `Secure` |
| PostgreSQL | Payroll data |
| Object storage | Documents, photos, task attachments, logo |
| A scheduled backup | `pg_dump` outside the app |

## 2. Backend host options

| Host | Free allowance (as of Sept 2026) | Sleep / limits | Card needed | Verdict for this app |
|---|---|---|---|---|
| **Render** (Web Service, Docker) | 750 instance-hours/month per workspace; 512 MB RAM, 0.1 CPU | Spins down after 15 min without traffic; about a minute to wake | No | **Recommended default.** Needs JVM tuning (see TECHSTACK). |
| Oracle Cloud Always Free | 2 OCPU and 12 GB across Arm A1 instances, plus up to two AMD micro VMs (allowance halved in June 2026) | Idle instances may be reclaimed after 7 days below 20% use | Card and phone | Best if you need always-on and can handle a VM. More setup. |
| Google Cloud Run | 180,000 vCPU-seconds, 360,000 GiB-seconds, 2 million requests per month | Scales to zero; the JVM cold start is long (10–20 s reported) | Billing account required | Works, but cold starts are slow. |
| Azure Container Apps | Same style allowance plus $200 credit for 30 days | Scales to zero | Card, phone, $1 hold | Not better than Cloud Run for this. |
| Koyeb | 1 free service, 512 MB | Scales to zero after 1 hour idle | Yes ($29 pre-authorisation hold since Feb 2026) | Skip. |
| Railway | $5 one-time credit, then about $1 per month | Credit runs out fast | No | Skip. |
| Fly.io | No free allowance for new customers | — | Yes | Skip. |

## 3. Database options

| Provider | Free plan (per Koyeb's 2026 comparison) | Notes |
|---|---|---|
| **Neon** | 1 project, 10 branches, 3 GiB per branch, shared compute with 1 GB RAM | **Recommended.** Compute auto-suspends when idle and wakes on the first query. |
| Supabase | 2 free projects, 500 MB database, 1 GB file storage | Free projects have historically been paused after about a week of inactivity. Confirm the current rule. |
| Aiven | 1 node, 1 GB RAM, 5 GB storage | The free plan has been available on DigitalOcean only. |
| Render Postgres | Expires after 30 days | Do not use for anything you want to keep. |
| Koyeb Postgres | 50 active hours, sleeps after 5 minutes | Skip. |

## 4. Object storage

Use any S3-compatible store through the `StorageService` interface (the app only needs endpoint, bucket, keys, region):
- **Supabase Storage** (1 GB on the free plan) or **Cloudflare R2** (generous free allowance; check the current figure on Cloudflare's pricing page). Either works. Create a **private** bucket.
- For a pure demo, local disk on the host works only until the next redeploy, because the free container filesystem is ephemeral. Do not rely on it.

## 5. Recommended stack ("Option A", no card, about 30 minutes)

```
Browser ──HTTPS──► Render Web Service (Docker)  ──► Neon PostgreSQL (pooled URL)
                         │                      ──► S3-compatible private bucket
                         └─ serves the frontend too (one origin)
GitHub Actions (cron) ──► pg_dump (direct URL) ──► private artifact / bucket
UptimeRobot (free)    ──► GET /actuator/health every 5 minutes (keeps Render awake)
```

### Step by step

1. **Neon:** create a project (PostgreSQL 16). Copy two connection strings: the **pooled** one (host contains `-pooler`) for the app, and the **direct** one for Flyway and backups.
2. **Storage:** create a private bucket and an access key limited to it.
3. **GitHub:** push the repo (private). Add repository secrets for the backup workflow: `NEON_DIRECT_URL`, `BACKUP_PASSPHRASE` (any long random string; keep a copy somewhere safe, because without it the backups cannot be restored), `BACKUP_WEBHOOK_SECRET`, `APP_URL`, and (if storing dumps in a bucket) the storage keys.
4. **Render:** New → Web Service → connect the repo → Runtime **Docker** → Instance type **Free** → Health check path `/actuator/health`. Add environment variables:

   | Variable | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DATABASE_URL` | `jdbc:postgresql://<pooled-host>/<db>?sslmode=require` |
   | `DATABASE_USER`, `DATABASE_PASSWORD` | from Neon |
   | `FLYWAY_URL` | the **direct** JDBC URL (Flyway should not run through the pooler) |
   | `JWT_SECRET` | 32+ random bytes, base64 (`openssl rand -base64 48`) |
   | `APP_ENCRYPTION_KEY` | 32 random bytes, base64 (`openssl rand -base64 32`) |
   | `STORAGE_ENDPOINT`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_REGION` | from your storage provider |
   | `APP_BASE_URL` | the Render URL (`https://….onrender.com`) |
   | `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD` | the first admin. **Remove these after first login.** |
   | `BACKUP_WEBHOOK_SECRET` | random string (shared with the backup workflow) |
   | `APP_TRUSTED_PROXY` | `true` on Render (the app then reads the client IP from `X-Forwarded-For` set by Render only; leave `false` locally) |
   | `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError` |

5. **First deploy:** watch the build log. Open the URL, log in with the bootstrap admin, and change the password.
6. **Keep-alive:** create an UptimeRobot (or similar) HTTP monitor on `https://<app>/actuator/health` every 5 minutes. This keeps the service awake. It also uses nearly all of the 750 free hours (24 × 31 = 744), so run **only one** free Render service in that workspace.
7. **Smoke test:** run `smoke-test.sh` against the live URL.

### Production settings that matter on this stack
- Hikari: `maximumPoolSize=5`, `minimumIdle=1`, `connectionTimeout=30000`, `keepaliveTime=300000` so a suspended Neon database can wake without failing the first request.
- Flyway uses `FLYWAY_URL` (direct). The app uses `DATABASE_URL` (pooled).
- Swagger is disabled in `prod`. Only `/actuator/health` is public.
- Cookies are `Secure; HttpOnly; SameSite=Lax`, so use the HTTPS URL only.

## 6. Dockerfile (reference)

```dockerfile
# build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package

# run
FROM eclipse-temurin:25-jre
RUN useradd -r -u 1001 app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER app
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java","-jar","app.jar"]
```
(Use `eclipse-temurin:21-*` if you fall back to Java 21. Render passes `PORT`; `application.properties` must contain `server.port=${PORT:8080}` so the app listens on it. `JAVA_TOOL_OPTIONS` carries the JVM flags.)

## 7. Backup workflow (GitHub Actions, reference)

```yaml
name: nightly-backup
on:
  schedule: [{ cron: "30 20 * * *" }]   # 02:00 IST
  workflow_dispatch:
jobs:
  backup:
    runs-on: ubuntu-latest
    steps:
      - name: Install client
        run: sudo apt-get update && sudo apt-get install -y postgresql-client
      - name: Dump
        env: { URL: ${{ secrets.NEON_DIRECT_URL }} }
        run: pg_dump "$URL" --no-owner --format=custom -f backup.dump
      - name: Encrypt
        env: { PASS: ${{ secrets.BACKUP_PASSPHRASE }} }
        run: gpg --batch --yes --passphrase "$PASS" --symmetric --cipher-algo AES256 backup.dump
      - uses: actions/upload-artifact@v4
        with: { name: backup, path: backup.dump.gpg, retention-days: 7 }
      - name: Tell the app
        env: { SECRET: ${{ secrets.BACKUP_WEBHOOK_SECRET }}, APP: ${{ secrets.APP_URL }} }
        run: |
          SIZE=$(stat -c%s backup.dump.gpg)
          curl -fsS -X POST "$APP/api/v1/backups/webhook" -H "X-Webhook-Secret: $SECRET" \
            -H "Content-Type: application/json" -d "{\"status\":\"SUCCESS\",\"sizeBytes\":$SIZE,\"location\":\"github-artifact\"}"
```
The dump contains employee personal data, so it is **encrypted before upload** and the repository must stay private. Check the current `actions/upload-artifact` version when you add this. Test a restore into a scratch database (`pg_restore`) at least once, and write the steps in `docs/RUNBOOK.md`.

## 8. Alternative: always-on (Option B, Oracle Always Free)

Choose this only if sleeping and cold starts are not acceptable. You get a VM instead of a managed service: install Docker, run `docker compose` with the app, PostgreSQL, and Caddy (automatic HTTPS and a free domain via DuckDNS or similar), and keep your own backups. It needs a card and phone for signup, the allowance was reduced in June 2026, and idle instances can be reclaimed. Expect an extra hour or two of setup and ongoing maintenance.

## 9. Known behaviour on free tiers (tell your demo audience)

- First request after idle can take about a minute (container wake plus database wake). The keep-alive monitor reduces this.
- Free databases have small storage. Keep attachments small (5 MB limit is enforced) and prune old data.
- If the provider changes a free plan, the app does not change. Only the environment variables do.

## 10. Sources (checked October 2026)

- Free backend and Docker hosting limits: snapdeploy.dev/state-of-free-hosting (rows verified 26–28 Sept 2026)
- Spring Boot hosting experience: docs.bswen.com/blog/2026-02-28-springboot-free-hosting
- Free Postgres tiers: koyeb.com/blog/top-postgresql-database-free-tiers-in-2026
- Spring Boot versions and support dates: issoh.co.jp/tech/details/5172
