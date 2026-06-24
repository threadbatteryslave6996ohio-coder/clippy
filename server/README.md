# Clippy Server

Spring Boot API that persists clipboard entries in PostgreSQL.

## Requirements

- JDK 17+ with `javac` available on `PATH`
- Maven 3.9+
- Docker and Docker Compose for local PostgreSQL

Verify the Java install before running Maven:

```bash
java -version
javac -version
mvn -version
```

If Maven fails with `release version 17 not supported`, install a full JDK rather than a JRE and point `JAVA_HOME` at it. On Ubuntu, for example:

```bash
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Start Locally

From the repository root at `~/Desktop/clippy`, start PostgreSQL:

```bash
cd ~/Desktop/clippy
docker compose up -d postgres
```

Run the server from the repository root:

```bash
cd ~/Desktop/clippy
mvn -pl server spring-boot:run
```

Or, if your shell is already in `~/Desktop/clippy/server`, run the server without `-pl server`:

```bash
cd ~/Desktop/clippy/server
mvn spring-boot:run
```

The server listens on `http://localhost:8080` by default.

## Configuration

The default local configuration matches `docker-compose.yml`:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy
SERVER_PORT=8080
```

Set those environment variables before running Maven to override them.

## Endpoint

```http
POST /clipboard
Content-Type: application/json
```

```json
{
  "clientId": "android-pixel-8",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```

`timestamp` is optional. When it is omitted, the server stores the current time.

## Tests

```bash
cd ~/Desktop/clippy
mvn -pl server test
```

Integration tests use Testcontainers PostgreSQL and require Docker.
