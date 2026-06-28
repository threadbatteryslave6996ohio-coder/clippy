# Combined Server

Spring Boot deployment target that runs the auth routes and clipboard routes in one JVM.

## Start Locally

Create a `.env` file in `combined-server/` or point `CLIPPY_ENV_FILE` at one. The combined server only reads that file and fails fast if it is missing:

```dotenv
COMBINED_SERVER_PORT=8080

AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5433/auth
AUTH_DATASOURCE_USERNAME=auth
AUTH_DATASOURCE_PASSWORD=auth

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy

CLIPPY_AUTH_BASE_URL=http://localhost:8080/auth
CLIPPY_AUTH_ROUTE_PREFIX=/auth
CLIPPY_SERVER_ROUTE_PREFIX=/api

LOGGING_FILE_NAME=logs/clippy-combined-server.log
```

Build and run from the repository root:

```bash
mvn -pl combined-server -am package
cd combined-server
java -jar target/clippy-combined-server-0.1.0-SNAPSHOT.jar
```

The combined server exposes:

- `POST /auth/identities`
- `POST /auth/login`
- `POST /auth/tokens/check`
- `POST /api/clipboard`
- `GET /api/clipboard`

The clipboard module continues to validate tokens over HTTP. In combined mode, the auth client points at the combined server's own `/auth` routes.
