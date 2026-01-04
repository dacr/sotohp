# SOTOHP Project Context

## Project Overview
**SOTOHP** is a comprehensive photo management and annotation software designed to handle large photo collections (100k+). It emphasizes privacy (self-hosted, typically for home networks) and performance, utilizing a cache-based approach and embedded database.

### Key Features
- **Photo Management:** Supports browsing, diaporama, and timeline mosaic views.
- **Annotation:** People face identification, location fixing, tagging, and event management.
- **Architecture:** 
  - **Backend:** Modular Scala 3 application using the ZIO ecosystem.
  - **Frontend:** TypeScript-based Web UI interacting via a REST API.
  - **Storage:** Local file system (read-only for originals), LMDB for metadata/cache, optional Elasticsearch/Opensearch for advanced search.

## Tech Stack
- **Language:** Scala 3.7.x (Backend), TypeScript (Frontend).
- **Build Tool:** Mill (Backend), npm/tsc (Frontend).
- **Runtime:** Java 21 (requires JavaFX support for GUI components).
- **Frameworks/Libraries:** 
  - **Scala:** ZIO (Effects, Config, Logging), Tapir (API definitions), Elastic4s, DJL (Deep Java Library for ML).
  - **Frontend:** Axios, Leaflet (implied for maps).
- **DevOps:** Docker, Nix.

## Project Structure
- `modules/`: Core backend logic split into submodules:
  - `model`: Domain models and data structures.
  - `core`: Core utilities and configuration.
  - `imaging`: Image processing logic.
  - `processor`: Background processing (e.g., face detection) using DJL.
  - `search`: Search engine integration (Elasticsearch).
  - `service`: Business logic layer connecting core, persistence, and search.
- `user-interfaces/`: Application entry points:
  - `api`: HTTP REST API server (Tapir/ZIO-HTTP).
  - `cli`: Command-line tools (e.g., inference, maintenance).
  - `gui`: Desktop GUI (ScalaFX).
- `frontend-user-interface/`: Web frontend source code (TypeScript).
- `frontend-user-interface-dist/`: Compiled frontend assets (served by the API).
- `build.mill`: Main build configuration file.
- `default.nix`: Nix shell configuration for reproducible development environments.

## Building and Running

### Prerequisites
- Java 21 (with JavaFX)
- Mill Build Tool
- Node.js & npm (for frontend)
- Docker (optional, for containerized run)

### Common Commands (via Makefile)
The project includes a `Makefile` to simplify common tasks:

*   **Run API Server:**
    ```bash
    make run-api
    ```
    *Note: This automatically builds the UI (`make ui`) before starting the backend.*

*   **Build Frontend UI:**
    ```bash
    make ui
    ```
    *Compiles TypeScript from `frontend-user-interface` and copies assets to `frontend-user-interface-dist`.*

*   **Run Tests:**
    ```bash
    make test
    ```

*   **Run CLI Tools:**
    - Face Inference: `make run-face-inference`
    - Statistics: `make run-stats`

*   **Generate OpenAPI Specification:**
    ```bash
    make openapi-spec
    ```
    *Generates the OpenAPI specification file at `docs/sotohp-api-docs.json`.*

*   **Docker Build:**
    ```bash
    make docker-build
    ```

### Environment Variables
Configuration is primarily managed via environment variables. Key variables include:
- `PHOTOS_CACHE_DIRECTORY`: Path to image cache (default: `.sotohp`).
- `PHOTOS_DATABASE_PATH`: Path to LMDB database (default: `.lmdb`).
- `PHOTOS_ELASTIC_ENABLED`: Enable/disable search engine (default: `true`).
- `PHOTOS_LISTENING_PORT`: API port (default: `8080`).

## Development Conventions
- **Scala Style:** The project uses `scalafmt` (config in `.scalafmt.conf`). adhere to functional programming patterns with ZIO.
- **Frontend:** TypeScript is used for type safety. Ensure `make ui` passes after frontend changes.
- **Testing:** ZIO Test is used for unit and integration testing.
