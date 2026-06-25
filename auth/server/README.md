# Clippy Auth Server

Spring Boot service that owns client identities and login tokens for Clippy clients.

The clipboard app server does not store client secrets or tokens. It calls this auth server's `/tokens/check` endpoint to verify that a submitted bearer token belongs to the request `clientId`.

## Start Locally

From the repository root:

```bash
docker compose up -d postgres
mvn -pl auth/server spring-boot:run
```

The auth server listens on `http://localhost:8081` by default and uses the same local PostgreSQL database defaults as the app server.

## Create an Identity

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

`clientId` is the identity clients put in clipboard requests. `secret` is used only for login and is stored hashed.

## Login

```bash
curl -s http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

The response contains a token:

```json
{
  "clientId": "dummy",
  "token": "generated-token"
}
```

Use that token as `CLIENT_TOKEN` in client configuration.

## Check a Token

The app server calls this endpoint:

```bash
curl -s http://localhost:8081/tokens/check \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","token":"generated-token"}'
```

Response:

```json
{
  "valid": true,
  "clientId": "dummy"
}
```

## Configuration

```text
AUTH_SERVER_PORT=8081
AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
AUTH_DATASOURCE_USERNAME=clippy
AUTH_DATASOURCE_PASSWORD=clippy
```
