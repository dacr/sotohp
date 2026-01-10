# SOTOHP Project Context

## Project Overview
**SOTOHP** is a comprehensive photo management and annotation software designed to handle large photo collections (100k+). It emphasizes privacy (self-hosted), performance, and security.

### Key Features
- **Photo Management:** Browsing, diaporama, timeline mosaic, map view.
- **Annotation:** Face identification, location fixing, tagging, event management.
- **Architecture:** 
  - **Backend:** Modular Scala 3 application using ZIO 2 and Tapir.
  - **Frontend:** Vanilla TypeScript/JavaScript Web UI interacting via REST API.
  - **Security:** Role-Based Access Control (RBAC) via JWT and Keycloak.
  - **Storage:** Local file system (originals), LMDB (metadata/cache), Elasticsearch/Opensearch (search).

## Tech Stack
- **Language:** Scala 3.7.x (Backend), TypeScript/JavaScript (Frontend).
- **Build Tool:** Mill (Backend), npm (Frontend dependencies).
- **Runtime:** Java 21.
- **Frameworks/Libraries:** 
  - **Scala:** ZIO 2, Tapir 1.13.x, Sttp Client 4, Elastic4s 8.x, DJL (ML), JWT-Scala.
  - **Frontend:** Axios, Leaflet, Keycloak-JS.
- **DevOps:** Docker, Nix.

## Project Structure
- `modules/`: Core backend logic:
  - `model`: Domain models.
  - `core`: Utilities and configuration.
  - `imaging`: Image processing.
  - `processor`: ML/AI background processing.
  - `search`: Elasticsearch integration.
  - `service`: Business logic and data access (LMDB).
- `user-interfaces/`: Application entry points:
  - `api`: REST API server (Tapir/ZIO-HTTP). **Contains Security Logic.**
  - `cli`: Command-line tools.
- `frontend-user-interface/`: Frontend source.
  - `assets/js/app.js`: **Manually authored** main application logic.
  - `src/`: TypeScript sources (`apiClient.ts`, `auth.ts`) - primarily for reference/types, runtime relies on `app.js`.
- `docs/`: Documentation and specifications.
  - `sotohp-api-docs.json`: OpenAPI specification.
  - `SECURITY_PLAN.md`, `TECHNICAL_REVIEW.md`: Architectural details.

## Development Guidelines

### 1. Backend (Scala/ZIO)
- **Style:** Follow `scalafmt`. Use ZIO functional patterns.
- **Security Pattern:** 
  - All sensitive endpoints **MUST** use the `SecureEndpoints` pattern.
  - Use `zServerSecurityLogic` to wire authentication.
  - Use `errorOutVariantPrepend` to handle endpoint-specific errors while preserving the base security error type (`ApiIssue`).
  - **Example:**
    ```scala
    secureMyEndpoint()
      .get
      .in("path")
      .out(jsonBody[MyResponse])
      .errorOutVariantPrepend(statusForMyError)
      .serverLogic[ApiEnv](user => input => myLogic(input))
    ```
- **Dependencies:** Manage via `build.mill`. Use `mill mill.javalib.Dependency/showUpdates` to check.

### 2. Frontend (TS/JS)
- **Manual JS:** `frontend-user-interface/assets/js/app.js` is currently the main entry point and is **manually maintained**. Do not overwrite it with build artifacts unless the build process is explicitly fixed to handle it.
- **Auth:** Use `keycloak-js` for authentication. The `ApiClient` in `app.js` handles token injection.
- **API Specs:** Refer to `docs/sotohp-api-docs.json` for API contracts.

### 3. Build & Run
- **API Server:** `make run-api` (builds UI first).
- **OpenAPI Spec:** `make openapi-spec` (generates to `docs/sotohp-api-docs.json`).
- **Tests:** `make test`.
- **Docker:** `make docker-build`.

### 4. Security
- **Authentication:** Enabled via `AuthConfig` in `ApiConfig`. Defaults to `false` for dev, but `true` for production/secure modes.
- **CORS:** Be validation-aware. Explicit CORS configuration is currently minimal/missing (see `SECURITY_REVIEW.md`).
- **Input:** rely on Tapir schema validation.

## Agent Behavior
- **Refactoring:** When refactoring API endpoints, **always** verify security logic is preserved.
- **Frontend Changes:** Be cautious with `app.js`. It is a large monolith. Prefer small, surgical edits over full rewrites unless requested.
- **Compilation:** Always run `mill user-interfaces.api.compile` after backend changes.