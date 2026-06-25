# Clippy Auth Client

Java wrapper for the Clippy auth server HTTP API.

Use `ClippyAuthClient` when another Java module needs auth behavior without knowing endpoint paths or request/response DTO details.

```java
ClippyAuthClient authClient = new ClippyAuthClient("http://localhost:8081");
LoginResponse login = authClient.login("dummy", "change-me-please");
boolean valid = authClient.isTokenValidForClient("dummy", login.token());
```

## Build

```bash
mvn -pl auth/client package
```
