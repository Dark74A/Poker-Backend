# Poker Ledger Backend

A real-time multiplayer poker backend built with **Java 21, Spring Boot, MongoDB, JWT authentication, REST APIs, and WebSockets**.

The backend is responsible for user authentication, poker session management, game state, event handling, persistence, and real-time communication with connected clients.

> **Repository:** [Dark74A/Poker-Backend](https://github.com/Dark74A/Poker-Backend)

---

## Overview

**Poker Ledger Backend** is the server-side component of a multiplayer poker application.

The application combines traditional REST APIs with real-time WebSocket communication:

- **REST API** for authentication, session management, and regular HTTP operations.
- **JWT-based authentication** for securing API and WebSocket connections.
- **MongoDB** for persistent storage.
- **STOMP over WebSocket** for real-time game communication.
- **Spring Security** for authentication and authorization.
- **Event-driven game architecture** for handling poker state changes.
- **AOP** for cross-cutting concerns.
- **Docker** for containerized deployment.
- **Spring Actuator** for application monitoring.
- **Testcontainers** for MongoDB integration testing.

The server can be used independently of the frontend and exposes the APIs and WebSocket endpoints required by a poker client.

---

## Features

### Authentication & Security

- User registration and login
- Password hashing using BCrypt
- JWT-based stateless authentication
- JWT validation through a security filter
- Protected REST endpoints
- JWT authentication during WebSocket/STOMP connection
- CORS configuration
- Secure environment-based configuration

### Poker Session Management

The backend manages multiplayer poker sessions including:

- Creating sessions
- Joining sessions
- Managing players
- Maintaining session state
- Tracking players associated with a session
- Handling poker-game commands
- Broadcasting state changes to connected clients

### Real-Time Communication

The application uses:

**WebSocket + STOMP**

for real-time communication between the backend and frontend.

The WebSocket endpoint is:

```text
/ws
```

Clients authenticate by providing their JWT during the STOMP `CONNECT` frame.

Session subscriptions are restricted so that only authorized users, such as the session host and linked participants, can receive session-specific updates.

### Event-Driven Architecture

Poker actions are treated as events that can modify the current game state.

This approach provides a clean separation between:

```text
Command
   ↓
Domain Logic
   ↓
Domain Event
   ↓
Event Store
   ↓
Projection / State
   ↓
WebSocket Broadcast
```

This makes the poker engine easier to reason about and provides a foundation for features such as:

- Game history
- Event timelines
- State reconstruction
- Historical views
- Auditing
- Replay functionality

### MongoDB Persistence

MongoDB is used as the application's persistent database.

The backend uses **Spring Data MongoDB** to interact with the database.

MongoDB is particularly suitable for this project because poker sessions and events contain structured but flexible state that can evolve as the application grows.

### 📊 Monitoring

Spring Boot Actuator is included for application monitoring and operational visibility.

This allows the application to expose useful information about its runtime state and health.

### Testing

The project includes Spring Boot testing infrastructure and **Testcontainers** dependencies for MongoDB integration testing.

This allows tests to run against an actual MongoDB container instead of relying exclusively on mocks.

### Docker Support

The repository includes a multi-stage Dockerfile that builds with Maven and runs on:

```text
eclipse-temurin:21-jre
```

The image builds the application using the Maven wrapper and runs the generated Spring Boot JAR. Use `docker compose up --build` from the repository root to run the local two-instance deployment with Nginx, MongoDB, and Redis.

---

# Architecture

At a high level, the system follows this architecture:

```text
                    ┌─────────────────────┐
                    │      Frontend       │
                    │   React / Browser   │
                    └──────────┬──────────┘
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
             HTTP/REST                  WebSocket
                 │                       + STOMP
                 │                           │
                 ▼                           ▼
        ┌─────────────────────────────────────────┐
        │              Spring Boot                │
        │                                         │
        │  ┌─────────────┐   ┌─────────────────┐ │
        │  │ Controllers │   │ WebSocket Layer │ │
        │  └──────┬──────┘   └────────┬────────┘ │
        │         │                   │          │
        │         └─────────┬─────────┘          │
        │                   ▼                    │
        │            ┌─────────────┐             │
        │            │   Services  │             │
        │            └──────┬──────┘             │
        │                   ▼                    │
        │          ┌──────────────────┐          │
        │          │  Domain / Poker  │          │
        │          │      Logic       │          │
        │          └────────┬─────────┘          │
        │                   ▼                    │
        │          ┌──────────────────┐          │
        │          │ Event / Session  │          │
        │          │    Management    │          │
        │          └────────┬─────────┘          │
        │                   ▼                    │
        │          ┌──────────────────┐          │
        │          │ MongoDB / Event  │          │
        │          │     Storage      │          │
        │          └──────────────────┘          │
        └─────────────────────────────────────────┘
```

---

# Request Flow

## REST Request

A typical authenticated REST request follows:

```text
Client
  │
  │ HTTP Request
  ▼
Spring Security
  │
  │ JWT validation
  ▼
JwtAuthenticationFilter
  │
  ▼
Controller
  │
  ▼
Service
  │
  ▼
Repository
  │
  ▼
MongoDB
```

The JWT filter validates the token before protected application logic is executed.

---

## WebSocket Request

Real-time communication follows:

```text
Client
   │
   │ WebSocket CONNECT
   │ + JWT
   ▼
Spring WebSocket
   │
   ▼
STOMP Authentication
   │
   ▼
Authorized Session
   │
   ├── Subscribe
   │
   └── Send Poker Command
           │
           ▼
      Game / Domain Logic
           │
           ▼
       State Change
           │
           ▼
      Event / Broadcast
           │
           ▼
        Clients
```

---

# Authentication

Authentication is implemented using **Spring Security + JWT**.

The basic flow is:

```text
Register
   ↓
Password → BCrypt
   ↓
MongoDB

Login
   ↓
Validate Credentials
   ↓
Generate JWT
   ↓
Client

Authenticated Request
   ↓
JWT
   ↓
JwtAuthenticationFilter
   ↓
SecurityContext
   ↓
Protected Controller
```

## JWT

JWT is used because the backend follows a stateless authentication model.

The client sends its token with authenticated requests.

For REST APIs this is typically:

```http
Authorization: Bearer <JWT>
```

For WebSockets, the JWT is provided during the STOMP `CONNECT` operation.

The project uses the JJWT library:

```text
jjwt-api
jjwt-impl
jjwt-jackson
```

---

# API

The backend exposes HTTP endpoints under the configured Spring Boot application.

Authentication-related endpoints are exposed through the authentication controller.

A typical client flow is:

```text
POST /auth/register
        ↓
Create account

POST /auth/login
        ↓
Receive JWT

Use JWT
        ↓
Authenticated REST requests

WebSocket /ws
        ↓
Real-time poker communication
```

> The exact API contract should be treated as the source of truth in the controller classes. The README intentionally avoids hard-coding every request/response field so the documentation does not become stale when the API evolves.

---

# WebSocket / STOMP

The WebSocket endpoint is:

```text
/ws
```

The backend uses **STOMP** as the messaging protocol on top of WebSockets.

STOMP provides a structured messaging model using:

- `CONNECT`
- `SUBSCRIBE`
- `SEND`
- `DISCONNECT`

A simplified communication model looks like:

```text
Client A ──────┐
               │
Client B ──────┼──► WebSocket / STOMP ──► Backend
               │
Client C ──────┘
```

The backend can then broadcast changes to authorized participants.

### Authorization

WebSocket subscriptions are not treated as public channels.

A client must be authenticated and authorized to interact with a session.

This prevents unrelated users from subscribing to another player's private session data.

---

# 🎮 Poker Game Model

The backend separates the concept of a **session** from the individual commands/events that modify that session.

A simplified model is:

```text
Poker Session
│
├── Session Identity
├── Host
├── Players
├── Game State
├── Current Turn
├── Poker Actions
└── Event History
```

Players interact with the session through commands such as:

```text
Join Session
Leave Session
Start Game
Place Action
Update Game State
End / Settle Game
```

The domain layer is responsible for ensuring that game operations follow the application's business rules.

---

# Event-Driven Design

The project uses an event-oriented approach for important state changes.

Instead of thinking only in terms of:

```text
Update Database
```

the application can model a change as:

```text
Player Action
      ↓
Command
      ↓
Domain Logic
      ↓
Domain Event
      ↓
Persist Event
      ↓
Update / Project State
      ↓
Broadcast Update
```

For example:

```text
Player raises
     ↓
Raise Command
     ↓
Validate action
     ↓
Raise Event
     ↓
Persist event
     ↓
Update session state
     ↓
Broadcast new state
```

This design is useful for multiplayer applications because game state changes become explicit and traceable.

---

# Technology Stack

| Technology              | Purpose                         |
| ----------------------- | ------------------------------- |
| **Java 21**             | Programming language            |
| **Spring Boot 4.1**     | Backend framework               |
| **Spring Web MVC**      | REST APIs                       |
| **Spring Security**     | Authentication & authorization  |
| **JWT / JJWT**          | Token-based authentication      |
| **Spring Data MongoDB** | Database access                 |
| **MongoDB**             | Persistent storage              |
| **Spring WebSocket**    | Real-time communication         |
| **STOMP**               | WebSocket messaging protocol    |
| **Spring Validation**   | Request validation              |
| **Spring AOP**          | Cross-cutting concerns          |
| **Spring Actuator**     | Monitoring and health           |
| **Lombok**              | Boilerplate reduction           |
| **Maven**               | Build and dependency management |
| **JUnit / Spring Test** | Testing                         |
| **Testcontainers**      | Integration testing             |
| **Docker**              | Containerization                |

The Maven configuration currently targets Java 21 and Spring Boot 4.1.0 and includes the above Spring modules along with JJWT and Testcontainers dependencies.

---

# Project Structure

The project follows a standard Spring Boot structure.

```text
Poker-Backend/
│
├── .mvn/
│   └── wrapper/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── ...
│   │   │
│   │   └── resources/
│   │       └── ...
│   │
│   └── test/
│       └── java/
│           └── ...
│
├── .env.example
├── .gitattributes
├── .gitignore
├── Dockerfile
├── mvnw
├── mvnw.cmd
├── pom.xml
└── README.md
```

### Main responsibilities

```text
config/
    Application configuration
    Security configuration
    WebSocket configuration

controller/
    REST API endpoints

service/
    Application/business services

domain/
    Poker domain logic
    Commands
    Events
    Aggregates

repository/
    MongoDB persistence

security/
    JWT authentication
    Security filters

websocket/
    STOMP/WebSocket communication
```

> Package names and individual classes may evolve as the project develops. The architectural responsibilities above describe the intended separation of concerns.

---

# Configuration

The project uses environment variables for configuration.

Create your local environment file:

```bash
cp .env.example .env
```

Example:

```env
MONGODB_URI=mongodb://localhost:27017/poker_ledger
JWT_SECRET=<your-base64-secret>
CORS_ALLOWED_ORIGINS=http://localhost:5173
PORT=8080
```

### Configuration variables

| Variable               | Description               | Example                                  |
| ---------------------- | ------------------------- | ---------------------------------------- |
| `MONGODB_URI`          | MongoDB connection string | `mongodb://localhost:27017/poker_ledger` |
| `JWT_SECRET`           | Secret used to sign JWTs  | `<base64-secret>`                        |
| `CORS_ALLOWED_ORIGINS` | Allowed frontend origins  | `http://localhost:5173`                  |
| `PORT`                 | Backend HTTP port         | `8080`                                   |

The repository's current `.env.example` defines these four configuration values.

---

# 🚀 Getting Started

## Prerequisites

Install:

- Java 21
- MongoDB
- Git

Maven does not need to be installed separately because the repository contains the Maven Wrapper.

Verify Java:

```bash
java -version
```

You should see Java 21.

---

## 1. Clone the repository

```bash
git clone https://github.com/Dark74A/Poker-Backend.git
cd Poker-Backend
```

---

## 2. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env`:

```bash
nvim .env
```

Set your MongoDB URI and JWT secret.

---

## 3. Start MongoDB

For a local MongoDB installation, make sure MongoDB is running.

For example:

```bash
sudo systemctl start mongodb
```

or use your preferred MongoDB deployment.

The default database configuration is:

```text
mongodb://localhost:27017/poker_ledger
```

---

## 4. Export environment variables

Because Spring Boot does not automatically read `.env`, load the file into the current shell:

```bash
set -a
. ./.env
set +a
```

---

## 5. Run the application

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
mvnw.cmd spring-boot:run
```

The backend starts on:

```text
http://localhost:8080
```

unless `PORT` is changed.

---

# Build

Compile the project:

```bash
./mvnw -DskipTests compile
```

Build the complete application:

```bash
./mvnw clean package
```

Run the generated JAR:

```bash
java -jar target/*.jar
```

---

# Testing

Run the complete test suite:

```bash
./mvnw test
```

The project includes Spring Boot testing support and Testcontainers dependencies for MongoDB-based integration tests.

Testcontainers allows the test environment to start a temporary MongoDB container, which helps make integration tests closer to the real production environment.

---

# Docker

The project includes a Dockerfile based on Eclipse Temurin Java 21.

Build the image:

```bash
docker build -t poker-backend .
```

Run the container:

```bash
docker run -p 8080:8080 \
  -e MONGODB_URI="mongodb://host.docker.internal:27017/poker_ledger" \
  -e JWT_SECRET="<your-secret>" \
  -e CORS_ALLOWED_ORIGINS="http://localhost:5173" \
  -e PORT=8080 \
  poker-backend
```

The Docker build:

```text
Java 21 image
      ↓
Copy source
      ↓
Maven build
      ↓
Spring Boot JAR
      ↓
Run application
```

The repository's current multi-stage Dockerfile builds with Maven and launches the resulting JAR on `eclipse-temurin:21-jre`.

---

# Health & Monitoring

Spring Boot Actuator is included in the project.

This provides a foundation for:

- Application health checks
- Operational monitoring
- Deployment health verification
- Runtime metrics
- Production diagnostics

This is particularly useful when the backend is deployed to a cloud platform because the platform can use health endpoints to determine whether the application is running correctly.

---

# CORS

The backend supports configurable CORS origins through:

```env
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Multiple frontend origins can be provided as a comma-separated list.

Example:

```env
CORS_ALLOWED_ORIGINS=http://localhost:5173,https://my-frontend.example.com
```

Only trusted frontend origins should be allowed in production.

---

# Security Considerations

Before deploying this project publicly:

### 1. Use a strong JWT secret

Do not use:

```env
JWT_SECRET=secret
```

Generate a cryptographically strong secret instead.

### 2. Never commit `.env`

The `.env` file may contain:

- Database credentials
- JWT secrets
- Deployment-specific configuration

Keep it outside Git.

### 3. Restrict CORS

Avoid:

```text
*
```

when the application is intended for a known frontend.

Use explicit origins.

### 4. Protect MongoDB

Production MongoDB should use:

- Authentication
- TLS
- Network restrictions
- Strong credentials
- Appropriate database permissions

### 5. HTTPS

Production REST and WebSocket traffic should be served through HTTPS.

The WebSocket connection should therefore use:

```text
wss://
```

rather than an unencrypted:

```text
ws://
```

---

# Deployment Architecture

A production deployment can look like:

```text
                    Internet
                       │
                       ▼
              ┌─────────────────┐
              │ Reverse Proxy   │
              │   / Load Bal.   │
              └────────┬────────┘
                       │
            ┌──────────┴──────────┐
            │                     │
            ▼                     ▼
     REST API                WebSocket
            │                     │
            └──────────┬──────────┘
                       ▼
              ┌─────────────────┐
              │ Spring Boot    │
              │ Poker Backend  │
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │    MongoDB      │
              └─────────────────┘
```

The backend can be deployed to platforms such as Render, AWS, Railway, or any infrastructure capable of running a Java/Docker application.

---

# Design Principles

The project is built around several software engineering principles.

## Separation of Concerns

Different parts of the application have different responsibilities:

```text
Controller
   ↓
Request handling

Service
   ↓
Application/business logic

Domain
   ↓
Poker rules and state

Repository
   ↓
Persistence

Security
   ↓
Authentication/authorization

WebSocket
   ↓
Real-time communication
```

This prevents controllers from becoming responsible for database access, authentication, game logic, and messaging simultaneously.

---

## Stateless Authentication

JWT allows the API to remain stateless.

Instead of storing an authenticated HTTP session on the server:

```text
Client
  │
  │ JWT
  ▼
Server
  │
  └── Validate JWT
```

This makes horizontal scaling easier because authentication information is contained in the token rather than a server-local session.

---

## Event-Oriented State Changes

Important game transitions can be represented as events.

For example:

```text
PlayerJoined
PlayerReady
GameStarted
PlayerFolded
PlayerCalled
PlayerRaised
TurnChanged
HandCompleted
SessionUpdated
```

This provides a clear history of how a game reached its current state.

---

# Example Poker Flow

A simplified game flow is:

```text
1. User registers
        ↓
2. User logs in
        ↓
3. Backend issues JWT
        ↓
4. Client connects to /ws
        ↓
5. JWT is authenticated
        ↓
6. User creates/joins a session
        ↓
7. Players connect to the session
        ↓
8. Game starts
        ↓
9. Players send actions
        ↓
10. Backend validates actions
        ↓
11. Domain state changes
        ↓
12. Events are generated
        ↓
13. Events/state are persisted
        ↓
14. Updated state is broadcast
        ↓
15. Clients update their UI
```

---

# Why MongoDB?

MongoDB fits the project because poker state and event data are naturally document-oriented.

A conceptual session document can contain:

```json
{
  "sessionId": "...",
  "host": "...",
  "players": [
    {
      "playerId": "...",
      "name": "...",
      "chips": 1000
    }
  ],
  "state": {
    "phase": "...",
    "pot": 500,
    "currentTurn": "..."
  }
}
```

Event-oriented data can similarly be stored as documents containing:

```text
Event ID
Session ID
Event Type
Timestamp
Payload
Version
```

This also leaves room for future event-sourcing and replay functionality.

---

# Scalability Considerations

The current architecture can be extended toward a horizontally scalable deployment.

Potential future components include:

```text
                    Load Balancer
                         │
             ┌───────────┼───────────┐
             ▼           ▼           ▼
         Backend 1   Backend 2   Backend 3
             │           │           │
             └───────────┼───────────┘
                         │
                  Shared Messaging
                         │
                         ▼
                      MongoDB
```

For large-scale WebSocket deployments, a shared message broker or distributed WebSocket infrastructure can be introduced so that clients connected to different backend instances can still receive the correct game events.

---

# Development Commands

### Run

```bash
./mvnw spring-boot:run
```

### Compile

```bash
./mvnw -DskipTests compile
```

### Test

```bash
./mvnw test
```

### Clean build

```bash
./mvnw clean package
```

### Run JAR

```bash
java -jar target/*.jar
```

### Docker build

```bash
docker build -t poker-backend .
```

### Docker run

```bash
docker run -p 8080:8080 poker-backend
```

---

# Troubleshooting

## Application does not start

Check Java:

```bash
java -version
```

The project requires:

```text
Java 21
```

Also verify:

```bash
echo $JAVA_HOME
```

---

## MongoDB connection failure

Check:

```env
MONGODB_URI=...
```

Make sure MongoDB is running and reachable from the backend.

---

## JWT errors

Verify:

```env
JWT_SECRET=...
```

The backend requires a valid secret for signing and validating tokens.

If the secret is changed, previously issued JWTs will no longer validate.

---

## CORS errors

Verify:

```env
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

The value must match the frontend origin exactly.

For example:

```text
http://localhost:5173
```

is different from:

```text
http://localhost:3000
```

---

## WebSocket connection fails

Verify:

```text
/ws
```

and ensure the client sends the JWT during the STOMP `CONNECT` process.

Also verify that the frontend origin is included in:

```env
CORS_ALLOWED_ORIGINS
```

---

# Future Improvements

Potential improvements for the project include:

- [ ] Complete API documentation with OpenAPI/Swagger
- [ ] Comprehensive unit test coverage
- [ ] More MongoDB integration tests
- [ ] Complete event-sourcing implementation
- [ ] Event replay functionality
- [ ] Persistent poker hand history
- [ ] Distributed WebSocket messaging
- [x] Redis Pub/Sub integration for shared real-time updates across local backend instances
- [ ] Rate limiting
- [ ] Refresh-token authentication
- [ ] Improved observability
- [ ] Prometheus/Grafana metrics
- [ ] CI/CD pipeline
- [ ] Automated Docker deployment
- [ ] Production-grade logging
- [ ] API versioning

---

# Contributing

Contributions are welcome.

### 1. Fork the repository

```bash
git fork https://github.com/Dark74A/Poker-Backend
```

### 2. Create a branch

```bash
git checkout -b feature/my-feature
```

### 3. Make your changes

Follow the existing project structure and keep responsibilities separated.

### 4. Run tests

```bash
./mvnw test
```

### 5. Commit

```bash
git commit -m "feat: add my feature"
```

### 6. Push

```bash
git push origin feature/my-feature
```

### 7. Open a Pull Request

Describe:

- What changed
- Why it changed
- How it was tested
- Any known limitations

---

# License

No license has currently been specified for this repository.

If you intend to make the project open source, add an appropriate `LICENSE` file before publishing.

---

# Author

**Dark74A**

GitHub:  
https://github.com/Dark74A

---

# Project Summary

**Poker Ledger Backend** is a Spring Boot backend designed for a real-time multiplayer poker application.

Its core architecture combines:

```text
Java 21
   +
Spring Boot
   +
Spring Security
   +
JWT
   +
MongoDB
   +
REST
   +
WebSocket / STOMP
   +
Event-driven domain logic
   +
AOP
   +
Docker
   +
Testing / Testcontainers
```

The result is a backend that separates authentication, API handling, poker-domain logic, persistence, and real-time communication while providing a foundation for scalable multiplayer gameplay.

---
