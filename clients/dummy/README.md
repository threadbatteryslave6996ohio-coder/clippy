# Clippy Dummy Client

Small Java client for sending command text directly to the Clippy server over HTTP.

This client does not read the system clipboard. It sends command text as the `content` field in the same `/clipboard` request contract used by the other clients.

## Requirements

- JDK 17+
- Maven 3.9+
- A running Clippy auth server and app server

## Setup

From the repository root:

1. Start PostgreSQL, the auth server, and the app server.

   The dummy client sends requests to the same `/clipboard` endpoint as the desktop and Android clients. For local development, the server normally runs at `http://localhost:8080`.

   Start the auth database on port `5433` and the app database on port `5432` using your preferred local PostgreSQL setup, then run the auth server and app server.

2. Create a client identity and login.

   ```bash
   curl -i http://localhost:8081/identities \
     -H 'Content-Type: application/json' \
     -d '{"clientId":"dummy","secret":"change-me-please"}'

   curl -s http://localhost:8081/login \
     -H 'Content-Type: application/json' \
     -d '{"clientId":"dummy","secret":"change-me-please"}'
   ```

   Copy the returned `token` value into `CLIENT_TOKEN`.

3. Configure the client.

   Create or update `.env` in the repository root:

   ```dotenv
   REMOTE_SERVER_URL=http://localhost:8080
   CLIENT_ID=dummy
   CLIENT_TOKEN=token-from-auth-login
   ```

   `REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

   `CLIENT_TOKEN` is required. `CLIENT_ID` is optional and defaults to `dummy-` plus the machine hostname, with a random fallback if hostname lookup fails.

   Shell environment variables override values from `.env` when both are set.

4. Build the dummy client.

   ```bash
   mvn -pl clients/dummy package
   ```

   If Maven is not already running on JDK 17, pin `JAVA_HOME` for the build:

   ```bash
   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -pl clients/dummy package
   ```

5. Send a command.

   ```bash
   java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
   ```

## Run the Client

Build and send one command:

```bash
mvn -pl clients/dummy package
java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
```

Or override `.env` from the shell:

```bash
REMOTE_SERVER_URL=http://localhost:8080 CLIENT_ID=dummy CLIENT_TOKEN=token-from-auth-login \
  java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
```

You can also pipe commands on stdin. Each non-empty line is sent as one command:

```bash
printf 'ping\nstatus\n' | java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar
```

## Failure Logs

If the server cannot be reached, the client logs a clear error and exits with status `1` for one-shot commands:

```text
Cannot reach remote server. endpoint=http://localhost:8080/clipboard error=...
```

If the server responds with a non-2xx status code, the client logs:

```text
Remote server rejected command. endpoint=http://localhost:8080/clipboard httpStatus=...
```

## Server Contract

The client sends:

```http
POST /clipboard
Authorization: Bearer <client-token>
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "content": "ping",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
