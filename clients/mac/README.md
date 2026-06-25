# Clippy macOS Client

Java clipboard client for posting macOS text clipboard changes to the Clippy server.

The client is an explicit foreground process. It reads the local system text clipboard, checks for a change from the last successfully sent value, and posts changed content to the server.

## Requirements

- JDK 17+
- Maven 3.9+
- A running Clippy server

## Start the Server

From the repository root:

```bash
docker compose up -d postgres
mvn -pl server spring-boot:run
```

## Run the Client

Run the client from a logged-in graphical session so Java can access the system clipboard:

```bash
export REMOTE_SERVER_URL=http://localhost:8080
export CLIENT_ID=my-mac
export CLIPBOARD_POLL_INTERVAL_MS=1000
mvn -pl clients/mac package
java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
```

`REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to the machine hostname. `CLIPBOARD_POLL_INTERVAL_MS` is optional and defaults to `1`.

If the client cannot save a clipboard change to the remote server, it appends the same JSON payload to `clippy-offline-clipboard.json` in the directory where the client was launched. It also prints the remote server failure and the local file write to the terminal.

## Server Contract

The client sends:

```json
{
  "clientId": "macbook-pro",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
