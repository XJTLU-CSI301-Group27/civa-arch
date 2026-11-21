# ADR-004: BD home feature moves from `app` into `feature-bd`

- Status: accepted
- Date: 2026-11-21
- Owners: BD (feature), EF (module boundaries)
- Related: ADR-003 (shared visual system)

## Context

The home screen prototype was delivered inside the `app` module: 10 business
classes, 20 resource files, 152 recipe asset files and 4 unit tests. It ran, but
`feature-bd` stayed an empty shell and the bytecode gate
(`FeatureDependencyArchTest`) only scans `edu.xjtlu.civa.feature..`, so the
prototype bypassed the module rules entirely — it even opened its own
`HttpURLConnection` to the nutrition provider.

## Decision

1. All business classes move to `edu.xjtlu.civa.feature.bd`, renamed so that
   ownership is unambiguous:

   | Prototype (`edu.xjtlu.civa`) | feature-bd |
   | --- | --- |
   | `HomeFragment` | `BdHomeFragment` |
   | `HomeViewModel` | `BdHomeViewModel` |
   | `HomeFlowDialog` | `BdFlowDialog` |
   | `HomePlanData` | `BdDraft` |
   | `HomePlanStore` | `BdDraftStore` |
   | `HomeRecipeCatalog` | `BdRecipeCatalog` |
   | `HomePhotoFiles` | `BdPhotoFiles` |
   | `HomeNutritionClient` | `BdNutritionClient` |
   | `HomeNutritionData` | `BdNutrition` |
   | `BakeryHomeLayout` | `BdHomeLayout` |

   `BakeryHomeLayout` stays in the feature (unlike the other primitives from
   ADR-003) because it reads the home layout's own view ids.

2. Resources are renamed to the `bd_` prefix (`resourcePrefix 'bd_'`), the recipe
   catalog (312 recipes, 149 images) moves to `feature-bd/src/main/assets`, and
   shared tokens are reused instead of duplicated: `CivaWidget.PrimaryButton`,
   `CivaWidget.SecondaryButton`, `CivaText.Navigation`, the core-ui cat/plate
   primitives and their `civa_*` ids.

3. The fragment no longer reaches into the application activity. It talks to a
   narrow host interface declared by the feature:

   ```java
   public interface BdHost { SessionStore sessions(); }
   ```

   `MainActivity` implements it (it already exposed `sessions()` for
   `HouseholdHost`), and `AppNavigator` maps `Route.TODAY` to `BdHomeFragment`.

4. Direct network transport is not allowed in a feature module. The raw HTTPS
   call moves to `core.data.JsonPost` (OkHttp, redirects off, bounded response,
   cancellable); `BdNutritionClient` keeps only the prompt, request body and
   error taxonomy. This is what makes the ArchUnit rule pass for the first time
   over real feature code instead of being silently blind.

5. The debug-only visual entry becomes `BdPreviewActivity` and is a **second**
   launcher icon. `MainActivity` keeps its launcher entry, so login and household
   navigation remain reachable — the prototype had removed them.

## Consequences

- `FeatureDependencyArchTest` now actually guards the BD code; any future
  feature-side socket/OkHttp use fails the build.
- `PublicSurfaceArchTest` registers `BdHost` and `BdHomeFragment`, and the
  `JsonPost` transport is registered under core-data.
- The home screen is reachable from the real app (`TODAY`) **and** from the
  preview launcher for visual work; both use the same fragment.
- Persisted draft files keep their format and scoping (`BdDraft` version 1, per
  account/household SHA-256 scope), so no migration or data reset is needed.
- The nutrition estimate still depends on a debug-only provider key under
  `getNoBackupFilesDir()`; production wiring needs a server-side proxy, tracked
  separately.
