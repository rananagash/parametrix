# Parametrix

Parametrix is a local AI-powered CAD tool that turns everyday language into real 3D models. Instead of requiring you to learn complex 3D modeling software, it takes a typed prompt describing an object and its dimensions, uses Google's Gemini model to write clean parametric code in OpenSCAD, and automatically compiles that code into a standard STL file ready for 3D printing or rendering.

Behind the scenes, it acts like an automated engineer:

- It fixes its own mistakes: If OpenSCAD runs into a syntax or geometry error, Parametrix captures the exact compiler error and asks Gemini to repair the code automatically.

- It keeps you in control: You can watch compilation logs live, manually tweak the generated OpenSCAD code in a built-in code editor, and re-render on the fly without using AI tokens.

- Interactive 3D viewing: Once compiled, the model appears in an in-browser 3D viewport where you can rotate, pan, zoom, inspect dimensions, and download the raw .scad and .stl files.

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



## Architecture & Implementation

Parametrix is built as a single-user local application using a **Spring Boot (Java)** backend and a **TypeScript / React** frontend. It avoids heavy cloud infrastructure and keeps all API keys strictly on the server side.

### 1. AI Generation & Structured Output

* **Model Integration:** Interfaces with the Gemini API via Spring's REST client with a **60-second request deadline**. The API key (`GEMINI_API_KEY`) and model (`GEMINI_MODEL`) reside server-side.
* **Structured Schema:** Gemini is constrained to return a structured JSON object containing the raw OpenSCAD source.
* **Prompt Guardrails:** Instructed to generate self-contained geometry using named parametric dimension variables with millimeters as default units. It disallows external asset calls.

### 2. Multi-Pass Compiler Repair Loop

* **Cycle Limits:** Runs 1 initial generation plus up to **3 automated repair attempts** (a maximum of 4 compilation cycles per prompt job).
* **Feedback Mechanism:** When compilation fails, the system feeds the user's original prompt, the failed source code, and bounded compiler diagnostics back to Gemini to request targeted code fixes.
* **Fast Exit:** Authentication and provider errors fail immediately without burning retry attempts.

### 3. Native CAD Execution & Concurrency

* **Process Isolation:** Runs the native OpenSCAD CLI directly using Java `ProcessBuilder` (no shell execution) inside an isolated temporary directory per attempt.
* **Virtual Threads & Stream Draining:** Java virtual threads concurrently drain `stdout` and `stderr` to prevent OS-level pipe buffer deadlocks.
* **Execution Limits:**
* Strict **30-second hard timeout** per render attempt.
* Process tree termination: On timeout or cancellation, the parent process and all OS descendants are terminated immediately.
* Console output is capped at **32,768 characters per stream** to prevent memory exhaustion.
* Single active job policy: Rejects concurrent job submissions with a busy response. Completed jobs expire after **1 hour**, and temporary artifacts are wiped on shutdown.



### 4. Security & STL Binary Validation

* **Source Inspection:** Pre-execution static analysis rejects scripts containing `include`, `use`, `import`, or `surface` to prevent file access or path traversal.
* **Binary STL Inspection:** Before any model is exposed to the frontend, the raw binary STL is parsed and validated:
* Rejects files larger than **50 MB**.
* Confirms headers match declared triangle counts.
* Detects non-finite (`NaN` / `Infinity`) floating-point coordinates.
* Rejects empty, corrupt, or entirely degenerate meshes (zero-area triangles).



### 5. API Design & Real-Time SSE

The backend exposes **7 REST and Server-Sent Events (SSE) endpoints**:

* `POST /api/jobs`: Submits a text prompt (enables auto-repair) or manually edited code (compiles once). Returns `202 Accepted` with a Job ID.
* `GET /api/jobs/{id}`: Returns status, attempt counts, diagnostics, and artifact links.
* `GET /api/jobs/{id}/events`: Streams live status, log outputs, and code updates over SSE. Implements ordered event replays using `Last-Event-ID` and sends **15-second heartbeat comments** to sustain long-polling connections.
* `DELETE /api/jobs/{id}`: Cancels active compilation runs.
* `GET /api/jobs/{id}/stl` & `GET /api/jobs/{id}/source`: Secure, internal ID-resolved download endpoints for `.stl` and `.scad` files.

### 6. Interactive Frontend & 3D Dashboard

* **Stack:** Built with TypeScript, npm, and **React Three Fiber (Three.js)**.
* **State Handling:** If a new generation or repair attempt fails, the 3D viewport retains the last successful mesh rather than crashing or clearing to an empty screen.
* **3D Features:** Includes OrbitControls, automatic camera framing (fit-to-model), ambient and directional lighting, and an orientation grid.
* **Dual Workflow:** Supports automated natural-language generation as well as an in-browser code editor with an instant "Render" button for manual parameter adjustments.

### 7. Validation & Verification

The system was verified through automated end-to-end unit, integration, and UI tests:

| Scope | Test Target | Results & Verified Behaviors |
| --- | --- | --- |
| **Backend Suite** | 18 Automated Tests | Verified successful prompt generation, single-cycle and multi-cycle repair successes, exhaustion after 3 repair attempts, provider API failures, rejection of file-access syntax, empty/malformed STL detection, 30s timeouts, cancellation triggers, and concurrent job lock rejection. |
| **SSE & Replay** | Streaming Integration | Confirmed strict event ordering, successful event replay upon reconnection via `Last-Event-ID`, and delivery of terminal job states. |
| **Runner & CAD** | Native Process & Bounds Check | Verified `ProcessBuilder` execution capture and descendant termination. Tested OpenSCAD rendering of a dimensioned reference cube, confirming an exact **12-triangle STL** measuring **10 × 20 × 30 mm**. |
| **Frontend Suite** | 3 Automated Tests | Validated prompt submission, code editor state changes, manual re-compilation, error boundary presentation, and retention of previous successful meshes during render failures. |
| **Build & Quality** | Linting & Compiles | Zero TypeScript errors, clean linter runs, successful production asset build, and confirmed responsive dashboard rendering without horizontal overflow. |