# Local development stack (Docker database + backend)

Everything needed for a full manual acceptance run is reachable from Android Studio,
without linking the `server/` build as a second IDE project.

## Prerequisites

1. Docker Desktop installed and **running** (Engine running in the tray).
2. A local `.env` (gitignored). Copy `.env.example` and set `POSTGRES_PASSWORD`,
   `DB_PASSWORD` (same value) and keep `DB_URL=jdbc:postgresql://localhost:5432/civa`.
3. JDK 17 for Gradle (the project already uses it).

## Gradle tasks

`gradle/local-stack.gradle` registers a `local` task group. It is applied from the root
`build.gradle`; CI never executes these tasks and never needs a `.env`.

| Task | What it does |
| --- | --- |
| `dbUp` | `docker compose --env-file .env -f infra/compose.yaml up -d` |
| `dbPs` | container status |
| `dbLogs` | follow database logs (blocking) |
| `dbDown` | stop the container, keep the data volume |
| `serverJar` | build `server/build/libs/civa-server.jar` with the server wrapper |
| `serverRun` | run that jar with `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`CIVA_MEDIA_DIR`/`SERVER_PORT` read from `.env` |
| `serverStop` | Windows helper that kills a stray listener on `SERVER_PORT` |
| `devDoctor` | prints Docker/context/.env/jar/port diagnostics and never fails the build |

`serverRun` never hard-codes credentials: it loads `.env` at execution time and creates
`server/build/media` before starting.

## Android Studio

Run configurations are local IDE state (`.idea/` is gitignored). This workspace already
contains them under `.idea/runConfigurations/`:

- `Civa DB up` -> `dbUp`
- `Civa DB ps` -> `dbPs`
- `Civa DB down` -> `dbDown`
- `Civa Backend (bootRun)` -> `serverRun`
- `Civa Doctor` -> `devDoctor` (run this first when something does not start)

If they are not visible after opening the project: **File -> Reload All from Disk** (or
restart the IDE). Alternatively open **Gradle tool window -> civa -> Tasks -> local** and
double-click the task — no run configuration needed.

## Typical acceptance flow

1. Start Docker Desktop.
2. Run `Civa DB up`, confirm with `Civa DB ps` (container `infra-db-1`, `127.0.0.1:5432`).
3. Run `Civa Backend (bootRun)`; wait for `Flyway ... Successfully applied` and
   `Started CivaServer`. The first run also builds the jar (`serverJar`, ~30 s).
4. Run the `app` configuration on the emulator. Debug builds target
   `http://10.0.2.2:8080/`, so no extra configuration is needed for an emulator.
5. Stop the backend with the Run window; `serverStop` is only for a stray process.

## Reset

```powershell
# app data (fresh first launch)
adb shell pm clear edu.xjtlu.civa

# database, keeping the volume
.\gradlew.bat dbDown

# database, wiping users and households
docker compose --env-file .env -f infra/compose.yaml down -v
.\gradlew.bat dbUp
```

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `permission denied while trying to connect to the docker API` | Docker Desktop is not running |
| `password authentication failed for user "civa"` | the volume was initialised with another password: `docker compose ... down -v` then `dbUp` |
| `ports are not available: 5432` | a local PostgreSQL service or a WinNAT excluded range owns the port |
| backend starts but the app shows a network error | wrong base URL; emulators use `10.0.2.2`, real devices need `-PCIVA_API_BASE_URL=http://<host-ip>:8080/` |
| 8080 already in use | `.\gradlew.bat serverStop` |
