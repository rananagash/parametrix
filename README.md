# Parametrix

Local AI-to-CAD workspace: Gemini → parametric OpenSCAD → STL, with compiler-driven repairs, SSE logs, an editable source pane, and an interactive Three.js preview.

## Requirements

- Java **21**, Node.js **22**, and OpenSCAD with binary STL export support.
- A Gemini API key with access to the configured model.
- macOS example: `brew install openjdk@21` and `brew install --cask openscad@snapshot`. Use the [OpenSCAD development snapshot](https://openscad.org/downloads.html#snapshots) on macOS; the older stable Homebrew cask is disabled. Set `JAVA_HOME` to the Java 21 installation (Homebrew: `$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`). The backend includes a Maven Wrapper, which downloads Maven on first use.

## Run locally

In one terminal:

```sh
cd backend
cp .env.example .env
# Edit .env with your Gemini key and OpenSCAD executable path.
set -a
source .env
set +a
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./mvnw spring-boot:run
```

For Linux, use an installed Java 21 JDK and set `OPENSCAD_PATH` to your headless OpenSCAD executable. The `.env` file is shell-sourced; quote values containing spaces. Spring does not automatically load it.

In another terminal:

```sh
cd frontend
npm ci
cp .env.example .env.local
npm run dev
```

Open http://127.0.0.1:3000. Both services bind to loopback. Allowed frontend origins are `http://127.0.0.1:3000` and `http://localhost:3000`.

Enter a description including dimensions (millimetres by default), then Generate. The backend performs one generation and up to three repairs. You can edit the source and Render code; manual renders run once without AI changes. The last successful preview remains while a new job runs or fails. Download STL for printing or SCAD for further editing. Cancellation stops native renders promptly; an in-flight Gemini request may take up to its 60-second deadline to settle.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | none | Backend-only credential; manual rendering works without it |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Model supporting structured JSON output |
| `OPENSCAD_PATH` | `openscad` | Native executable, passed directly without a shell |
| `RENDER_TIMEOUT_SECONDS` | `30` | Deadline per render |
| `NEXT_PUBLIC_API_URL` | `http://127.0.0.1:8080` | Frontend backend URL |

Gemini HTTP failures terminate jobs without retries. Compiler/validation failures trigger repairs. Compiler output is capped at 32 KiB per stream per attempt; STL files above 50 MB are rejected. Source is limited to 100,000 characters, prompts to 10,000. Jobs and artifacts expire one hour after completion and are deleted on clean shutdown; restarting loses job history.

Native OpenSCAD is **not an OS sandbox** and has no hard memory limit. This application is for trusted local single-user use. It rejects external file operations (`include`, `use`, `import`, `surface`) before running generated or edited code. Do not expose these services publicly. Timeout and cancellation terminate subprocess descendants.

## API

- `POST /api/jobs` — JSON `{ "prompt": "…" }` or `{ "source": "…" }`; `202 { "id": "…" }`. One active job; concurrent submissions return `409`.
- `GET /api/jobs/{id}` — `id`, `status`, `attempt`, `source`, `diagnostics`, `artifactAvailable`.
- `GET /api/jobs/{id}/events` — ordered SSE events: `status`, `source`, `log`, `complete`; replay using `Last-Event-ID`. Heartbeat comments every 15 seconds. Terminal events close the stream.
- `DELETE /api/jobs/{id}` — request cancellation, returns `202`.
- `GET /api/jobs/{id}/model.stl` — successful binary mesh.
- `GET /api/jobs/{id}/model.scad` — latest compiled/generated source.

Statuses: `queued`, `generating`, `repairing`, `compiling`, `cancelling`, `succeeded`, `failed`, `cancelled`. Missing or expired jobs return `404`; artifacts that are not ready return `409`.

## Validation

```sh
cd backend
./mvnw test
# Include real OpenSCAD cube render and dimension verification:
OPENSCAD_PATH=/Applications/OpenSCAD.app/Contents/MacOS/OpenSCAD ./mvnw test
```

```sh
cd frontend
npm test
npm run lint
npm run typecheck
npm run build
```

Backend tests cover repairs, failures, timeout/descendant termination, cancellation, invalid and empty meshes, busy rejection, SSE replay, and expiration. Frontend tests exercise generation, editing/manual rendering, cancellation, errors, and retention of the previous preview. To smoke-test the full pipeline, start both services with a valid key, generate a `10 × 20 × 30 mm cube`, inspect logs and mesh, and download the STL.
