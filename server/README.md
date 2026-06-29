# Clippy Server

Spring Boot API that persists clipboard entries in PostgreSQL.

The server does not own client identities. It validates each clipboard request by calling the separate Clippy auth server.

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

If Maven fails with `release version 25 not supported`, install a full JDK rather than a JRE and point `JAVA_HOME` at it. On Ubuntu, for example:

```bash
sudo apt install openjdk-25-jdk
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Start Locally

Start the app database on port `5432` and the auth database on port `5433` using your preferred local PostgreSQL setup.

Run the auth server from the repository root in one terminal:

```bash
cd ~/Desktop/clippy
mvn -pl auth/server spring-boot:run
```

Build and run this app server from the repository root in another terminal:

```bash
cd ~/Desktop/clippy
mvn -pl server -am package
java -jar server/target/clippy-server-0.1.0-SNAPSHOT-exec.jar
```

Or, if your shell is already in `~/Desktop/clippy/server`, invoke Maven from the root POM so it can include the auth client module:

```bash
cd ~/Desktop/clippy/server
mvn -f ../pom.xml -pl server -am package
java -jar target/clippy-server-0.1.0-SNAPSHOT-exec.jar
```

The app server listens on `http://localhost:8080` by default. The auth server listens on `http://localhost:8081` by default.

## Configuration

The server loads configuration from a `.env` file in the current directory or any parent directory. The launcher converts the file values into Spring application defaults instead of exposing the env loader to the service classes.

The default local configuration matches the local development settings:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy
SERVER_PORT=8080
CLIPPY_AUTH_BASE_URL=http://localhost:8081
```

Set those values in `.env` before running Maven to override them.
Explicit Spring configuration, including shell exports and command-line arguments
such as `--server.port=9090`, takes precedence over the file-derived defaults.

## Endpoint

```http
POST /clipboard
Content-Type: application/json
Authorization: Bearer <client-token>
```

```json
{
  "clientId": "android-pixel-8",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```

`timestamp` is optional. When it is omitted, the server stores the current time.

The bearer token must have been issued by the auth server for the same `clientId` in the JSON body.

Query all clipboard entries for one client in an inclusive timeframe:

```http
GET /clipboard?clientId=ubuntu-gnome&from=2026-06-23T12%3A00%3A00Z&to=2026-06-23T13%3A00%3A00Z
Authorization: Bearer <client-token>
```

The response is ordered by timestamp and id and includes each entry's `id`,
`clientId`, `content`, and `timestamp`. The bearer token must belong to the
requested client. A timeframe with `from` later than `to` returns `400`.

## Logging

The app server writes its normal Spring Boot logs to `LOGGING_FILE_NAME` and also writes a custom audit log file named `clippy-server.txt` in the same directory by default.

Each successful `POST /clipboard` request records a line noting the `clientId`, generated entry id, and timestamp. Raw clipboard content is not written to the custom log.

The custom audit log is best-effort. If it cannot be written, the clipboard insert still succeeds.

## Tests

```bash
cd ~/Desktop/clippy
mvn -pl server -am test
```

Integration tests use Testcontainers PostgreSQL and require Docker.
