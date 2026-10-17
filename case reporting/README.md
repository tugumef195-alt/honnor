# Harbor   confidential campus reporting

Harbor is a small, mobile-friendly reporting and case-tracking app. The static interface deploys to Vercel; the API is Java 21 / Spring Boot and stores records in PostgreSQL.

## Deployment decision: Java on Vercel

Vercel's runtime list does **not** include a native Java function runtime. Its current [Container Runtime](https://vercel.com/docs/functions/runtimes/container) is an official **beta** that can run an OCI image containing Java. The container is request-driven, scales down after idle time, and has Vercel Function limits; it is not a durable disk or an always-running worker. A Java service can therefore run on Vercel Containers, but I would not make a confidential campus case system depend on that beta runtime without institutional acceptance and a full deployment assessment.

This project keeps the **frontend on Vercel** and deploys the Java API as a separately managed container service with managed PostgreSQL (for example, an always-on Render web service and Render PostgreSQL in the same region). This gives the API and database an explicit, durable operational home while retaining Vercel's static CDN for the public interface. Requests to `/api/*` are proxied through the Vercel rewrite so browser sessions remain same-origin.

## Run locally

Requirements: Java 21, Maven 3.9+, Docker Compose, and a base64-encoded 32-byte encryption key.

1. Start PostgreSQL from `backend/`:

   ```sh
   docker compose up -d database
   ```

2. Create a key (PowerShell):

   ```powershell
   $bytes = New-Object byte[] 32
   $random = [Security.Cryptography.RandomNumberGenerator]::Create()
   $random.GetBytes($bytes)
   $env:APP_ENCRYPTION_KEY = [Convert]::ToBase64String($bytes)
   ```

3. Set local connection and staff credentials, then start the API:

   ```powershell
   $env:DATABASE_URL = "jdbc:postgresql://localhost:5432/harbor?sslmode=disable"
   $env:DATABASE_USERNAME = "harbor"
   $env:DATABASE_PASSWORD = "local-development-only"
   $env:COOKIE_SECURE = "false"
   $env:COOKIE_SAME_SITE = "lax"
   $env:CORS_ORIGINS = "http://localhost:5500"
   $env:STAFF_EMAIL = "case.staff@example.edu"
   $env:STAFF_PASSWORD = "replace-this-with-a-long-local-password"
   $env:COMMITTEE_EMAIL = "committee@example.edu"
   $env:COMMITTEE_PASSWORD = "replace-this-with-another-long-password"
   mvn spring-boot:run
   ```

   Start the API from `backend/`. Users are provisioned only when a password is at least 16 characters. These example addresses are fictional; use institution-controlled accounts and secrets outside local development.

4. Serve the workspace root on `http://localhost:5500` with any static file server. For example, if Node.js is installed:

   ```sh
   npx --yes http-server . -p 5500
   ```

   The public report form and status lookup work without an account. The case workspace requires the configured staff login. Committee credentials can open the pattern-lead view.

## Deploy

1. In Render, create a managed PostgreSQL database and an **always-on** Docker web service in the same region. Point the web service at this repository's `backend/` root directory and `Dockerfile`; set its health check to `/api/health`. Use the database's internal hostname and credentials to form the JDBC URL below. Do not use a free service that sleeps for a production reporting channel.
2. Add these API environment variables in the hosting provider's secret manager:
   - `DATABASE_URL`: JDBC PostgreSQL URL, with TLS enabled where supported (for example `jdbc:postgresql://INTERNAL_DB_HOST:5432/DB?sslmode=require`; use the provider's documented TLS settings for its private network).
   - `DATABASE_USERNAME`, `DATABASE_PASSWORD`.
   - `APP_ENCRYPTION_KEY`: base64 for exactly 32 random bytes. Back it up separately from the database. Losing it makes encrypted report text unreadable.
   - `STAFF_EMAIL`, `STAFF_PASSWORD`, `COMMITTEE_EMAIL`, `COMMITTEE_PASSWORD`: provision institution-managed accounts; use unique, randomly generated passwords with at least 16 characters.
   - `COOKIE_SECURE=true`, `COOKIE_SAME_SITE=lax` when served through the same-origin Vercel rewrite.
   - `REPORT_RETENTION_DAYS` (default 365), `AUDIT_RETENTION_DAYS` (default 730), and `DB_POOL_SIZE` as required.
   - For direct browser-to-API development only, set `CORS_ORIGINS` to the exact frontend origin(s). Do not use `*` with credentials.
3. Replace `harbor-api.example.com` in [vercel.json](./vercel.json) with the API's HTTPS host, then import the project into Vercel. Deploy the repository root as a static site.
4. Use a custom production domain, verify TLS, configure institutional backups/access policies, test staff provisioning and the proxy/session behavior, then submit a fictional test report and delete it. Do not place real reports into an unapproved demo environment.
5. Configure a gateway/WAF rate limit as well as the API's per-peer fallback limits. Because Vercel and the API host proxy requests, verify which client address the API receives and ensure trusted proxies overwrite forwarded-address headers; never trust caller-supplied forwarding headers at a public origin.

## Roles, privacy boundaries, and review

- **Reporter:** submits with no account. The API returns a random reference and a separate high-entropy access code once; only the SHA-256 hash of that code is stored. Both are required for status lookup. Optional contact details are never shown in status responses. Harbor does not send email or SMS.
- **Case staff:** authenticated, provisioned accounts can see the case queue, its report preview, and update status/action notes. The API has a full-detail endpoint for authorized staff. Committee accounts do not inherit case-staff access.
- **Authorized committee:** a separate role can see candidate pairs only: two report references and a similarity percentage. It cannot fetch case text or contact details. A committee member must explicitly confirm or dismiss each suggestion. Confirming a suggestion records a human-reviewed link; the software does not link cases or initiate action on its own.
- **Language processing:** pattern leads use only local category, matching optional involved-person text, and overlap between longer words in report text. No model or third-party language service is called, and report data is not used for model training. Similarity is an uncalibrated heuristic, not a probability, fact, finding, or proof. Review reports separately and follow institutional policy.
- **Security boundaries:** narrative, optional involved-person text, contact details, and internal notes are encrypted with AES-256-GCM before database storage. Passwords use BCrypt; sessions are server-side in PostgreSQL; cookies are HTTP-only and production-secure; state-changing requests require CSRF tokens. Staff and committee routes are separately role-gated. API responses are not cacheable/indexable, and the client does not load analytics or third-party fonts.
- **Audit and retention:** case views, case changes, pattern-lead views, and pattern decisions create audit events without copying report text. Report and audit retention are configurable; scheduled deletion removes expired reports and linked action/review rows, and separately expires audit rows. Check the institution's retention policy and legal obligations before choosing values.

## Before real-world use

This is a working starter, **not a certified or institution-approved reporting service**. It does not establish who is legally allowed to access a report, verify committee membership, send protected updates, manage account recovery, handle emergency workflows, or replace trained advocates. The initial staff provisioning uses deployment secrets and is not an account-administration product. A production launch needs institutional privacy/security review, threat modeling and penetration testing, verified support/emergency details, operational backups and key rotation, a process for user support and account lifecycle, and a reviewed retention/legal policy. Do not promise absolute confidentiality: explain local policy and its exceptions before collecting real information.
