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

## One-click acceptance (Run app)

:app:preDebugBuild depends on devStackEnsure, so a debug build (Studio *Run app*) already:

1. checks TCP 5432 and starts the container when it is down;
2. builds the backend jar when it is missing or older than server/src;
3. starts the jar detached (log: server/build/logs/dev-stack.log) and waits for port 8080.

It never fails the build: when Docker is unavailable it prints [dev-stack] ... warnings and
the app still installs. CI=true or -PdevStackOff disables it. The database container uses

estart: unless-stopped, so it comes back when Docker Desktop starts.

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
| app window is black but the launcher renders | emulator compositor bug, not the app: open Settings on the same device - if it is black too, force software graphics. Device Manager -> Edit AVD -> Graphics = "Software - GLES 2.0", then Cold Boot Now. Equivalent: set `hw.gpu.mode=swiftshader_indirect` in `%USERPROFILE%\.android\avd\<avd>.avd\config.ini` and restart. The API 37.x preview system images are the usual culprit; an API 36 image is safer. |
| only the four placeholder tabs appear | expected: home needs login + household; recipes/inventory/diary/profile are not implemented yet |

## Nutrition provider (optional)

```ini
CIVA_NUTRITION_PROVIDER=auto        # auto | deepseek | stub
CIVA_NUTRITION_API_KEY=             # required for deepseek / auto-with-key
CIVA_NUTRITION_MODEL=deepseek-flash
CIVA_NUTRITION_HOURLY_LIMIT=60
CIVA_NUTRITION_DAILY_LIMIT=20
```

`auto` uses DeepSeek when a key is present and the deterministic stub otherwise, so CI and
key-less local runs still exercise the endpoint. The key stays on the server.

## Gradle JDK

Android Studio / Gradle 必须指向一个**独立安装的 JDK 17**（或在 Studio 里选
`Embedded JDK`）。不要使用仓库 `build/jdk17/` 下的 JDK 副本作为 Gradle JDK：
本机实测该路径里的 `java.exe` 无法写入 `%TEMP%`，会让 Room 的 sqlite 校验
（`DatabaseVerifier`）和 AGP 的 `compressDebugAssets` 报 `AccessDeniedException`，
而 Build 窗口里只显示一个 `...\Temp\<uuid>.tmp` 路径。

切换方式：`File > Sync Project with Gradle Files` 前，在
`Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`
选择独立 JDK（Studio 会写进不入库的 `<project>/.gradle/config.properties`）。

## 2026-11-25 fixed family invitation migration

The new app and backend must be updated together: new GET household invitations, expiresAt=null, all members self-leave and last-member closure. Startup applies additive Flyway V20261125210000 and backfills one permanent encrypted invitation for existing populated households. Old temporary invitations no longer grant entry.
Before upgrading, back up the database and CIVA_MEDIA_DIR/.private/invitation-receipt.key. Keep this key persistent outside build cleanup; do not commit/share it. Native launcher uses .civa-local/media. The local normal database was backed up and verified without resetting existing data. See [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md) for exact test/migration evidence.
