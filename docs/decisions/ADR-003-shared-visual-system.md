# ADR-003: One shared visual system ("bakery" paper and cat)

- Status: accepted
- Date: 2026-11-21
- Owners: EF/F (shared UI), reviewed by all feature groups
- Supersedes: the neutral placeholder palette in the bootstrap baseline

## Context

The bootstrap baseline deliberately shipped neutral grey placeholder colours in
`core-ui/src/main/res/values/civa_colors.xml` so that the project could compile
before the team agreed on a visual system. The BD group then designed the real
product look (butter paper, toasted caramel, apron blue, OFL ZCOOL KuaiLe
headings, drawn-in-code kitten/plate/paper primitives) inside the `app` module
while building the home screen prototype.

That prototype proves the look works, but it also means the visual system lives
in the wrong module: features cannot reuse it, two groups can drift, and every
page inherits whatever the last prototype changed.

## Decision

1. `core-ui` is the single source of truth for colour, typography, shape and
   shared view primitives. The bakery palette replaces the grey placeholders,
   keeping the existing `civa_*` token names.
2. The following widgets move from the prototype into
   `edu.xjtlu.civa.core.ui` and are consumed by feature modules:
   `PaperBackdropView`, `BakeryCatStage`, `DishPlateView`, `DishActionOverlay`,
   `DishPopoverPlacement`, `ElasticChoiceView`, `PressBounce`, `CivaWindow`.
3. Shared style tokens live in `values/civa_styles.xml`
   (`CivaText.*`, `CivaWidget.*`, `CivaShape.Navigation`) so no feature defines
   its own typography or button metrics.
4. `core-ui` keeps no storage knowledge: `DishPlateView` resolves images through
   the pluggable `ImageSources.Loader`, which the owning feature installs.
5. Feature modules must reference `@color/civa_*`, `@style/Civa*`,
   `@font/civa_kuaile` and the shared widgets. A feature-specific palette or
   duplicate button style is a review rejection.
6. The application keeps the five primary destinations from
   `docs/UI_RULES.md`; the visual shell (paper backdrop, floating pill
   navigation, playful headings) changes, the navigation information
   architecture does not.

## Consequences

- Every existing screen is re-skinned at once, including `feature-ef`, because
  the colour tokens changed value rather than name.
- The look is light-only for now: the theme inherits
  `Theme.MaterialComponents.Light`. Adding dark mode later means adding a
  `values-night` layer rather than recolouring features.
- `civa_kuaile.ttf` is bundled under the OFL licence; the licence text ships in
  `core-ui/src/main/assets/fonts/OFL-ZCOOLKuaiLe.txt`.
- Primitives are public API of `core-ui`; their registered surface is tracked in
  `contracts/public-surface.draft.json`.
- The prototype's `app`-local copies are deleted when BD code is migrated, so a
  single implementation remains.
