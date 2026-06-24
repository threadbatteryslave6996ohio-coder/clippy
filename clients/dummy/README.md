# Clippy Dummy Client

Small Java client for sending command text directly to the Clippy server over HTTP.

This client does not read the system clipboard. It sends command text as the `content` field in the same `/clipboard` request contract used by the other clients.

## Requirements

- JDK 17+
- Maven 3.9+
- A running Clippy server

## Run the Client

From the repository root:

```bash
mvn -pl clients/dummy package
java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
```

The client reads configuration from `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
CLIENT_ID=dummy
```

`REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to `dummy-` plus the machine hostname.

Shell environment variables override values from `.env` when both are set.

You can also pipe commands on stdin. Each non-empty line is sent as one command:

```bash
printf 'ping\nstatus\n' | java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar
```

## Server Contract

The client sends:

```json
{
  "clientId": "dummy",
  "content": "ping",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
