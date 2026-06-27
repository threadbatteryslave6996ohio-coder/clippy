# Clippy macOS Client

Java clipboard client for posting macOS text clipboard changes to the Clippy server.

The client is an explicit foreground process. It reads the local system text clipboard, checks for a change from the last successfully sent value, and posts changed content to the server.

## Requirements

- JDK 25+
- Maven 3.9+
- A running Clippy auth server and app server

## Start the Server

Start the auth database on port `5433` and the app database on port `5432` using your preferred local PostgreSQL setup, then run the auth server and app server.

## Run the Client

Run the client from a logged-in graphical session so Java can access the system clipboard:

Create or update `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=my-mac
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
```

Then build and start the client:

```bash
mvn -pl clients/mac package
java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
```

Shell environment variables override values from `.env` when both are set.

`REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to the machine hostname, with a random fallback if hostname lookup fails. `CLIPBOARD_POLL_INTERVAL_MS` is optional and defaults to `1`.

`CLIENT_SECRET` lets the client log in to the auth server and refresh tokens when the server returns `401`. `AUTH_SERVER_URL` is required when `CLIENT_SECRET` is set. If you prefer a static token, keep `CLIENT_SECRET` unset and provide `CLIENT_TOKEN` instead.

## Populate Auth Creds

The mac client does not take auth flags on the command line. It reads everything from environment variables or `.env`, then logs in to the auth server if you give it a secret.

Choose one of these setups:

### Option 1: Static token

Use this if you want to log in once and reuse the returned token.

1. Create an identity on the auth server.

   ```bash
   curl -i http://localhost:8081/identities \
     -H 'Content-Type: application/json' \
     -d '{"clientId":"my-mac","secret":"change-me-please"}'
   ```

2. Log in with the same `clientId` and `secret`.

   ```bash
   curl -s http://localhost:8081/login \
     -H 'Content-Type: application/json' \
     -d '{"clientId":"my-mac","secret":"change-me-please"}'
   ```

3. Copy the returned `token` value into `CLIENT_TOKEN`.

   ```dotenv
   REMOTE_SERVER_URL=http://localhost:8080
   CLIENT_ID=my-mac
   CLIENT_TOKEN=returned-token-value
   ```

4. Run the client without `CLIENT_SECRET`.

   ```bash
   mvn -pl clients/mac package
   java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
   ```

### Option 2: Secret-based login and refresh

Use this if you want the client to fetch a fresh token from the auth server automatically and refresh on `401`.

1. Create an identity with the client secret.

   ```bash
   curl -i http://localhost:8081/identities \
     -H 'Content-Type: application/json' \
     -d '{"clientId":"my-mac","secret":"change-me-please"}'
   ```

2. Set `CLIENT_SECRET` and `AUTH_SERVER_URL` in `.env` or the shell.

   ```dotenv
   REMOTE_SERVER_URL=http://localhost:8080
   AUTH_SERVER_URL=http://localhost:8081
   CLIENT_ID=my-mac
   CLIENT_SECRET=change-me-please
   ```

3. Start the client.

   ```bash
   mvn -pl clients/mac package
   java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
   ```

With this mode, the client calls `/login` on startup and again when the server returns `401`.

### Recommended env file

If you want a single `.env` file for the mac client, use this shape:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=my-mac
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
```

If the client cannot save a clipboard change to the remote server, it appends the same JSON payload to `clippy-offline-clipboard.json` in the directory where the client was launched. It also prints the remote server failure and the local file write to the terminal.

## Server Contract

The client sends:

```http
POST /clipboard
Authorization: Bearer <client-token>
Content-Type: application/json
```

```json
{
  "clientId": "macbook-pro",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
