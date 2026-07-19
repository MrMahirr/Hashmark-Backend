# Hashmark Backend

Hashmark is a developer tooling platform that scans GitHub repositories for `TODO`, `FIXME`, `HACK`, and `XXX` comments and tracks them as technical debt.

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

3. **Running Locally**
   You can run the application directly with Maven:
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
| **Repo** | `GET /repos`<br>`POST /repos`<br>`DELETE /repos/{id}` | Connect and manage GitHub repositories |
| **Scan** | `POST /scan/{repoId}`<br>`GET /scan/{repoId}/status` | Trigger async scan jobs and check status |
| **Debt** | `GET /debts`<br>`GET /debts/stats` | List technical debts and view overall stats |
| **Report** | `GET /report/summary`<br>`POST /report/send-test` | Dashboard summary and email testing |
| **Settings**| `GET /settings`<br>`PUT /settings` | Manage user settings and email notifications |

## Architecture Notes
- The application uses `JdbcTemplate` for all database interactions to ensure high performance and strict adherence to raw SQL queries without ORMs.
- Flyway is used to manage schema migrations.
- Background tasks like GitHub API scanning and Weekly Email Reports are executed asynchronously using Spring's `@Async` and `@Scheduled`.
