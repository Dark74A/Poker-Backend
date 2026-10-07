# Poker Ledger Backend

Spring Boot REST API and WebSocket service. Requires Java 21, MongoDB, and the Maven wrapper included in this folder.

## Configuration

Copy `.env.example` to `.env`, then set `MONGODB_URI` and a strong base64-encoded `JWT_SECRET`. Do not commit `.env`.

Spring Boot does not automatically load a `.env` file. Export its values in the shell (or configure them in your IDE/deployment environment) before starting the service:

```sh
set -a
. ./.env
set +a
./mvnw spring-boot:run
````

The service listens on `http://localhost:${PORT:-8080}`. Configure `CORS_ALLOWED_ORIGINS` with the exact frontend origin(s), comma separated. MongoDB must be reachable at `MONGODB_URI`.

## Build

```sh
./mvnw -DskipTests compile
./mvnw test
```

The WebSocket endpoint is `/ws`; clients authenticate with their JWT in the STOMP `CONNECT` frame. Session subscriptions are restricted to hosts and linked participants.
