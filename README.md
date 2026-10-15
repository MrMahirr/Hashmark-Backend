# Hashmark Backend

Hashmark is a developer tooling platform that scans GitHub repositories for `TODO`, `FIXME`, `HACK`, and `XXX` comments (plus `NOTE`, `DOC`, `INFO`, which never affect the health score) and tracks them as technical debt.

## Technical Requirements
- **Java**: 17 or higher
- **Maven**: 3.6 or higher
- **PostgreSQL**: 16 or higher

## Getting Started

1. **Database Setup**
   Ensure PostgreSQL is running and create a database named `hashmark`:
   ```sql
   CREATE DATABASE hashmark;
   ```

2. **Environment Variables**
   Copy the `.env.example` file to `.env` and fill in the corresponding values.
   ```bash
   cp .env.example .env
   ```

   **Environment Variables List**:
   | Variable | Description |
   |----------|-------------|
   | `SERVER_PORT` | Port for the backend server (default: 8080) |
   | `DATABASE_URL` | PostgreSQL connection URL (e.g., `jdbc:postgresql://localhost:5432/hashmark`) |
   | `DATABASE_USERNAME` | PostgreSQL username |
   | `DATABASE_PASSWORD` | PostgreSQL password |
   | `GITHUB_CLIENT_ID` | GitHub OAuth App Client ID |
   | `GITHUB_CLIENT_SECRET` | GitHub OAuth App Client Secret |
   | `JWT_SECRET` | Secret key for signing JWTs (Base64 string or long secure string) |
   | `ENCRYPTION_SECRET` | AES-256 Secret Key (32 characters long) |
   | `RESEND_API_KEY` | Resend API Key for email sending |
   | `RESEND_FROM_EMAIL` | Sender email address (e.g., `noreply@hashmark.dev`) |

3. **Running Locally (with Docker Compose)**
   The easiest way to start both the PostgreSQL database and the backend application is using Docker Compose. Make sure you have filled in your `.env` file first.
   ```bash
   docker-compose up --build -d
   ```

   To view logs:
   ```bash
   docker-compose logs -f backend
   ```

4. **Running Locally (Manual)**
   If you prefer to run the application directly without Docker, ensure your PostgreSQL database is running, then use Maven:
   ```bash
   mvn spring-boot:run
   ```

## API Documentation

When the application is running, the Swagger UI is available at:
`http://localhost:8080/swagger-ui.html`

### Endpoints Overview

| Module | Endpoints | Description |
|--------|-----------|-------------|
| **Auth** | `GET /auth/github`<br>`GET /auth/callback`<br>`POST /auth/refresh`<br>`POST /auth/logout` | GitHub OAuth login and JWT management |
| **Repo** | `GET /repos`<br>`POST /repos/sync`<br>`PUT /repos/{id}/branch`<br>`DELETE /repos/{id}` | Connect repositories and choose the tracked branch (empty = default branch) |
| **Scan** | `POST /scan/{repoId}`<br>`GET /scan/{repoId}/status` | Queue a scan (`202`; `409` if one is already queued/running) and poll its status |
| **Debt** | `GET /debts`<br>`GET /debts/stats`<br>`GET /debts/export?format=csv\|json\|sarif`<br>`PATCH /debts/{id}` | List (filter, `q` search, `sort`/`dir`, `size` ≤ 100), export, and triage (`OPEN`/`IGNORED`/`WONT_FIX`, assignee) |
| **Report** | `GET /report/summary`<br>`POST /report/send-test` | Dashboard summary and email testing |
| **Settings**| `GET /settings`<br>`PUT /settings` | Manage user settings and email notifications |

### API versioning
Every endpoint is also served under the `/v1` prefix (`/v1/debts` is the same as `/debts`). New clients should use `/v1`; the unversioned paths remain for existing clients. The OpenAPI spec lives at `/v1/api-docs`.

### Scanning
- A scan downloads the tracked branch as **one tarball** and processes it as a stream (no per-file API calls).
- Jobs are stored in the `scan_jobs` table, which doubles as a **persistent queue**: jobs survive restarts, a worker that stops sending heartbeats is re-queued (up to `scan.max-attempts`), and only one scan per repository can be active.
- Debts are identified by a **fingerprint** (file + label + normalized text + occurrence), not the line number, so moving code keeps a debt's id, age, assignee and triage state.
- Put a `.hashmarkignore` in the repository root to exclude paths (`docs/`, `*.gen.js`, `/scripts`, `src/generated/**`).
- After each scan, `git blame` (GraphQL) fills in author and introduced-at for new debts (best effort, `scan.blame-max-files` files per scan).
- Limits and tuning live under `scan:` in `application.yml`.

### Tests
`mvn test` runs unit tests. The PostgreSQL integration test is skipped unless `HASHMARK_TEST_DB_URL` is set (see the class comment in `DatabaseIntegrationTest`).

## Architecture Notes
- The application uses `JdbcTemplate` for all database interactions to ensure high performance and strict adherence to raw SQL queries without ORMs.
- Flyway is used to manage schema migrations.
- Scans run from a database-backed queue (`ScanJobDispatcher`, `@Scheduled`); Weekly Email Reports use `@Scheduled`.
