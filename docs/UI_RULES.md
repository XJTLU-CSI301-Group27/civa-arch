# UI Rules

## Main navigation

The application has five primary destinations:

1. Today
2. Recipes
3. Inventory
4. Meal log
5. Profile

Navigation and back behavior must be defined once by EF and reused by feature
owners.

## Shared visual system

One visual system, owned by `core-ui` (see
`docs/decisions/ADR-003-shared-visual-system.md`):

- Palette: butter paper `civa_background`, warm surface `civa_surface`, toasted
  caramel `civa_primary` / `civa_primary_dark`, apron blue `civa_secondary`,
  outline `civa_outline`, muted text `civa_muted`, meal tints
  `civa_breakfast` / `civa_lunch` / `civa_dinner`.
- Typography: OFL ZCOOL KuaiLe (`@font/civa_kuaile`) for headings, buttons and
  navigation labels; `CivaText.Title`, `CivaText.Section`, `CivaText.Subtitle`,
  `CivaText.Navigation`.
- Surfaces: paper grain via `edu.xjtlu.civa.core.ui.PaperBackdropView`; rounded
  28dp cards and pill buttons (`CivaWidget.*`).
- Motion: shared press feedback via `PressBounce` (installed by `CivaWindow`);
  spring values live in the primitive, not in screens.
- Shared widgets: `PaperBackdropView`, `BakeryCatStage`, `DishPlateView`,
  `DishActionOverlay`, `DishPopoverPlacement`, `ElasticChoiceView`.
- Keep theme, colour, typography, spacing, radius, icon, and button rules in one
  shared source.
- Reuse shared title bars, buttons, cards, inputs, dialogs, date selectors,
  loading states, empty states, and errors.
- Do not create a second visual system inside a feature.
- Do not duplicate global theme definitions, and do not redefine a `civa_*`
  colour or `Civa*` style inside a feature module.
- Copy belongs to features; shared widgets take no dependency on feature
  storage. Image references resolve through `ImageSources.Loader`, installed by
  the owning feature.

## Layout

- Support narrow windows and tablet-wide windows.
- Preserve state across portrait, landscape, split-screen, and window resizing.
- Keep text inside its container at supported font sizes.
- Account for keyboard visibility in forms.
- Test both empty and populated states.

## Pull request evidence

User-interface changes must include:

- the affected screens and states;
- narrow and wide layout screenshots;
- any known accessibility or responsiveness limitation.

Formatting tools cannot prove visual consistency. Shared components, design
tokens, screenshots, and acceptance checks are required.
