# Clippy

Clippy records text clipboard changes from desktop and Android clients in a Spring Boot server backed by PostgreSQL.

Client-specific docs:

- Auth server: [auth/server/README.md](auth/server/README.md)
- Auth client Java module: [auth/client/README.md](auth/client/README.md)
- macOS: [clients/mac/README.md](clients/mac/README.md)
- Linux GNOME: [clients/linux/README.md](clients/linux/README.md)
- Dummy: [clients/dummy/README.md](clients/dummy/README.md)
- Android: [clients/android/README.md](clients/android/README.md)

## Requirements

- JDK 17+ with `javac` available on `PATH`
- Maven 3.9+
- Docker and Docker Compose for the local PostgreSQL database
- Android Studio for the Android client

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

## Start the Server

Start PostgreSQL for the auth server and app server:

```bash
cd ~/Desktop/clippy
docker compose -f auth/server/docker-compose.yml up -d auth-postgres
docker compose -f server/docker-compose.yml up -d postgres
```

Run the auth server in one terminal:

```bash
cd ~/Desktop/clippy
mvn -pl auth/server spring-boot:run
```

Build and run the app server in another terminal:

```bash
cd ~/Desktop/clippy
mvn -pl server -am package
java -jar server/target/clippy-server-0.1.0-SNAPSHOT.jar
```

The auth server listens on `http://localhost:8081` and the app server listens on `http://localhost:8080` by default. Both servers load the repository root `.env` through the shared env manager, then overlay real process environment variables.

The auth server uses `AUTH_DATASOURCE_*` values and the app server uses `SPRING_DATASOURCE_*` values. Set those to your Azure PostgreSQL connection details in `.env` or in the shell before running Maven. Use separate database names if you want isolation between auth and clipboard data.

```text
AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5433/auth
AUTH_DATASOURCE_USERNAME=auth
AUTH_DATASOURCE_PASSWORD=auth
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy
CLIPPY_AUTH_BASE_URL=http://localhost:8081
```

Override `SERVER_PORT`, `AUTH_SERVER_PORT`, `CLIPPY_AUTH_BASE_URL`, or the datasource environment variables if you need different local settings.

Create a client identity and login before running a client:

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'

curl -s http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

Put the returned token in `CLIENT_TOKEN`.

## Run the macOS Client

Run the macOS client from a logged-in graphical session so Java can read the system clipboard:

```bash
cd ~/Desktop/clippy
export REMOTE_SERVER_URL=http://localhost:8080
export AUTH_SERVER_URL=http://localhost:8081
export CLIENT_ID=my-mac
export CLIENT_SECRET=change-me-please
export CLIPBOARD_POLL_INTERVAL_MS=1000
mvn -pl clients/mac package
java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
```

`REMOTE_SERVER_URL` can be the server base URL or the full `/clipboard` endpoint. `CLIENT_ID` defaults to the machine hostname. `CLIPBOARD_POLL_INTERVAL_MS` defaults to `1`.
`CLIENT_SECRET` lets the client log in to the auth server and refresh tokens on `401`. `AUTH_SERVER_URL` is required when `CLIENT_SECRET` is set. If you prefer a static token, keep `CLIENT_SECRET` unset and provide `CLIENT_TOKEN` instead.
Java desktop clients also read these values from `.env` in the repository root, with shell environment variables taking precedence.

See [clients/mac/README.md](clients/mac/README.md) for the macOS-client-specific notes.

## Run the Linux GNOME Client

Install a native clipboard helper on Ubuntu GNOME:

```bash
sudo apt install wl-clipboard xclip
```

Start the Clippy server first, then run the Linux client from a logged-in graphical session:

```bash
cd ~/Desktop/clippy
export REMOTE_SERVER_URL=http://localhost:8080
export AUTH_SERVER_URL=http://localhost:8081
export CLIENT_ID=ubuntu-gnome
export CLIENT_SECRET=change-me-please
export CLIPBOARD_POLL_INTERVAL_MS=1000
mvn -pl clients/linux package
java -jar clients/linux/target/clippy-linux-client-0.1.0-SNAPSHOT.jar
```

`REMOTE_SERVER_URL` is required. It can be the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to the machine hostname. `CLIPBOARD_POLL_INTERVAL_MS` is optional and defaults to `1000`.
`CLIENT_SECRET` lets the client log in to the auth server and refresh tokens on `401`. `AUTH_SERVER_URL` is required when `CLIENT_SECRET` is set. If you prefer a static token, keep `CLIENT_SECRET` unset and provide `CLIENT_TOKEN` instead.
Java desktop clients also read these values from `.env` in the repository root, with shell environment variables taking precedence.

The client polls the local text clipboard and sends changed text to the server. It uses `wl-paste` on GNOME Wayland, `xclip` or `xsel` on X11, and Java AWT as a fallback.

To force a clipboard backend, set `CLIPBOARD_BACKEND` before starting the client:

```bash
export CLIPBOARD_BACKEND=wl-paste
```

Supported values are `wl-paste`, `wayland`, `xclip`, `xsel`, `awt`, and `java`. See [clients/linux/README.md](clients/linux/README.md) for the Linux-client-specific notes.

## Run the Dummy Client

Use the dummy client to send command text directly to the server without reading a system clipboard:

```bash
cd ~/Desktop/clippy
mvn -pl clients/dummy package
java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
```

If Maven is not already running on JDK 17, pin `JAVA_HOME` for the build:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -pl clients/dummy package
```

The dummy client reads `REMOTE_SERVER_URL` and `CLIENT_ID` from `.env` in the repository root. You can also pipe commands on stdin. Each non-empty line is sent as one command.
`CLIENT_TOKEN` is required and should be the token returned by the auth server `/login` endpoint.

See [clients/dummy/README.md](clients/dummy/README.md) for the dummy-client-specific notes.

## Run the Android Client

Open `clients/android` in Android Studio and run the `app` configuration.

Use your computer's LAN IP address from a physical Android device:

```text
http://192.168.1.10:8080
```

Use this address from the Android emulator:

```text
http://10.0.2.2:8080
```

See [clients/android/README.md](clients/android/README.md) for Android behavior, build, and local-server notes.

## Run Envoy

Start the local Envoy proxy for external services:

```bash
cd ~/Desktop/clippy
./scripts/start-envoy.sh
```

It listens on `http://localhost:10080` and routes:

- `http://localhost:10080/clipy` to `http://localhost:8080`
- `http://localhost:10080/auth` to `http://localhost:8081`

## API

Clients send clipboard entries to:

```http
POST /clipboard
Content-Type: application/json
Authorization: Bearer <client-token>
```

```json
{
  "clientId": "macbook-pro",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```

The server returns `201 Created` with the saved entry id, client id, and timestamp.

## Tests

Run the Maven tests:

```bash
cd ~/Desktop/clippy
mvn test
```

The server integration tests use Testcontainers and require a working Docker daemon.
