# Clippy Auth Server

Spring Boot service that owns Clippy client identities and issues login tokens.

The clipboard app server does not store client secrets or token records. It receives a bearer token on clipboard writes and calls this auth server's `/tokens/check` endpoint to verify that the token belongs to the request `clientId`.

## Responsibilities

- Create one identity per Clippy client.
- Store client secrets as salted PBKDF2 hashes.
- Issue random login tokens for valid client credentials.
- Store token hashes, not raw token values.
- Validate a token against a `clientId` for the app server.

## Requirements

- JDK 25+ with `javac` available on `PATH`
- Maven 3.9+
- Docker and Docker Compose for local PostgreSQL

Verify the Java install before running Maven:

```bash
java -version
javac -version
mvn -version
```

## Start Locally

Start the auth database on port `5433` using your preferred local PostgreSQL setup, then run the server:

```bash
mvn -pl auth/server spring-boot:run
```

The auth server listens on `http://localhost:8081` by default. Its local PostgreSQL container exposes database `auth` on host port `5433`.

To build a runnable jar instead:

```bash
cd ~/Desktop/clippy
mvn -pl auth/server -am package
java -jar auth/server/target/clippy-auth-server-0.1.0-SNAPSHOT.jar
```

## Configuration

The auth server loads configuration from a `.env` file if one is present in the current directory or any parent directory. The shared env manager handles this loading, and shell exports are ignored for server startup.

The default local configuration matches the local development settings.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `AUTH_SERVER_PORT` | `8081` | HTTP port for the auth server. |
| `AUTH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/auth` | PostgreSQL JDBC URL. |
| `AUTH_DATASOURCE_USERNAME` | `auth` | Database username. |
| `AUTH_DATASOURCE_PASSWORD` | `auth` | Database password. |
| `AUTH_LOGGING_FILE_NAME` | `logs/clippy-auth-server.log` | File path for server logs. |

## Logging

The auth server uses two logging paths:

- Spring Boot / Logback writes the normal application logs to the file configured by `AUTH_LOGGING_FILE_NAME` and `logging.file.name`.
- The custom file logger writes a separate `auth-server.txt` file for startup and request-level audit messages.

The custom logger currently records:

- startup messages when the auth server detects a local database URL
- each `/login` request
- each successful token issuance
- each `/tokens/check` request
- each token check result

The auth server configures the custom logger to use the same directory as `AUTH_LOGGING_FILE_NAME`, so both auth log files land in the same folder by default. The custom logger file name is `auth-server.txt`.

If you run the `CustomLogger` directly elsewhere, you can still redirect it with the JVM system property `custom.logger.dir`, for example:

```bash
java -Dcustom.logger.dir=/tmp/clippy-logs -jar auth/server/target/clippy-auth-server-0.1.0-SNAPSHOT.jar
```

Raw secrets and raw bearer tokens are not written to the custom log file.

Example `.env`:

```dotenv
AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5433/auth
AUTH_DATASOURCE_USERNAME=auth
AUTH_DATASOURCE_PASSWORD=auth
AUTH_SERVER_PORT=8081
AUTH_LOGGING_FILE_NAME=logs/clippy-auth-server.log
```

For Azure, point `AUTH_DATASOURCE_URL` at the `auth` database on the deployed PostgreSQL server.

The service uses Hibernate `ddl-auto: update`, so it creates or updates the local schema on startup. The persistent tables are:

- `client_identities`
- `client_tokens`

## API

All endpoints accept and return JSON.

### Create an Identity

```http
POST /identities
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "secret": "change-me-please"
}
```

Response:

```http
201 Created
```

```json
{
  "clientId": "dummy",
  "createdAt": "2026-06-25T12:00:00Z"
}
```

Constraints:

- `clientId` is required and must be at most 128 characters.
- `secret` is required and must be 8 to 256 characters.
- Duplicate `clientId` values return `409 Conflict`.

Example:

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

### Login

```http
POST /login
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "secret": "change-me-please"
}
```

Response:

```json
{
  "clientId": "dummy",
  "token": "generated-token"
}
```

Use the returned token as `CLIENT_TOKEN` in Clippy client configuration. The raw token is shown only in this response. The database stores a SHA-256 hash of the token.

Invalid credentials return `401 Unauthorized`.

Example:

```bash
curl -s http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

### Check a Token

This endpoint is primarily for the app server.

```http
POST /tokens/check
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "token": "generated-token"
}
```

Response:

```json
{
  "valid": true,
  "clientId": "dummy"
}
```

The endpoint returns `valid: false` when the token is unknown, when the identity is inactive, or when the token belongs to a different `clientId`.

Example:

```bash
curl -s http://localhost:8081/tokens/check \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","token":"generated-token"}'
```

## App Server Integration

Run this auth server before starting the main app server. The app server defaults to this auth base URL:

```text
CLIPPY_AUTH_BASE_URL=http://localhost:8081
```

Clipboard clients send the token from `/login` to the app server as a bearer token:

```http
Authorization: Bearer <client-token>
```

The app server passes the clipboard request `clientId` and bearer token to `/tokens/check`. The token must have been issued for the same `clientId`.

## Operational Notes

- There is no token expiry or revocation endpoint yet. Issuing a new token does not invalidate older tokens.
- Identity records have an `active` flag in the database, but there is no HTTP endpoint for changing it yet.
- Secrets are never returned by the API.
- Token values cannot be recovered from the database because only token hashes are stored.

## Tests

Run the auth server tests from the repository root:

```bash
cd ~/Desktop/clippy
mvn -pl auth/server -am test
```

Tests that use PostgreSQL require a working Docker daemon when Testcontainers is involved.
