# ADR-005: Nutrition estimates move behind the server

- Status: accepted
- Date: 2026-11-21
- Owners: BD (feature), EF (contract and infrastructure)
- Related: ADR-004 (BD module migration)

## Context

The home screen estimated nutrition by calling a third-party provider **from the phone**:
`BdNutritionClient` opened its own HTTPS connection and read a provider key from
`getNoBackupFilesDir()/civa-deepseek.json`. That made the feature debug-only, leaked a
credential into the app sandbox, bypassed the contract (no 401 handling, no rate limit, no
audit), and repeated the same paid call on every device because there was no shared cache.

ADR-004 already moved the transport into `core.data.JsonPost`, but the key and the call
configuration still lived on the client, so at least one contract endpoint and a server-side
provider were needed for the feature to be releasable.

## Decision

1. Add one contract operation, `POST /api/v1/nutrition/estimates` (`estimateNutrition`), with
   `NutritionEstimateRequest` (`name`, `ingredients[]`, optional `steps[]`) and
   `NutritionEstimate` (`basis=PER_100G`, nullable metrics, `tips`, `inputHash`,
   `providerVersion`, `cached`). Errors follow the existing envelope: `400`, `401`, `429`
   with `Retry-After`, `500`, `501`, `503` with `Retry-After`.
2. The server owns the provider key (`CIVA_NUTRITION_API_KEY`) and a `NutritionProvider`
   abstraction: `deepseek` when a valid key is configured, deterministic `stub` in CI and
   local runs without a key, `auto` selects between them. `provider=deepseek` without a valid
   key fails at startup instead of returning fake numbers.
3. Results are **content-addressed**: canonicalized dish text → SHA-256 → one
   `nutrition_estimates` row (metrics only, never the dish text), 30-day expiry, shared across
   accounts. Cache hits cost nothing and do not consume the rate limit.
4. Provider calls are limited per user with a rolling hourly/daily window (in-memory, single
   instance) and oversized or empty inputs are rejected before any provider call.
5. The client keeps only DTO mapping and defensive re-validation: unknown metrics stay `null`,
   never `0`; the local per-dish fingerprint cache still serves offline display.

## Consequences

- The key, prompt, model choice and provider can change server-side without shipping the app.
- `core.data.JsonPost` and the on-device `civa-deepseek.json` are gone; feature code submits the
  call through `ApiCalls`, so it inherits 401 handling, cancellation and session isolation.
- CI covers the whole path with the stub provider; no provider key or network access is needed.
- The cache table stores no dish text, so a data leak does not expose what users cooked.
- Single-instance rate limiting: a multi-instance deployment needs a shared counter.
- Cost tracking: provider version is stored per row, so a model or prompt change is visible.
