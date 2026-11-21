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

## Home screen (BD)

`feature-bd` owns the home screen; the app only places it in the `TODAY`
destination.

- Layout: paper backdrop, weekday/date header with a calendar button, the cat
  hero (`BakeryCatStage` + bundled artwork), then breakfast / lunch / dinner
  cards. `layout-w600dp` arranges the three meals side by side instead of
  stacking them. The screen never scrolls vertically; `BdHomeLayout` shrinks
  meal cards to fit short windows and keeps the Add button visible.
- States: loading, ready, empty slot (card hidden), message/error toast, saving,
  importing a photo, and the add-dish flow phases (choose, manual entry,
  preferences, recommendation, dish actions, recipe, missing recipe, recipe
  editor, nutrition).
- Add-dish flow is a `DialogFragment` (`BdFlowDialog`): close (X) returns Home
  from the detail flow and steps back one phase inside the add flow; system back
  follows the same rule; a drag outside the anchored popover dismisses it.
- Actions: dish tap opens recipe / nutrition / remove; manual dishes require a
  name and accept one optional photo; recommendation rerolls never repeat the
  current dish and never relax the chosen filters.
- Copy belongs to the feature; every string is `bd_*` and every label is
  localized, so no user-visible text is hard-coded in Java.
- Accessibility: interactive tiles expose content descriptions, the cat bubble
  is a polite live region, and press feedback never consumes clicks or ignores
  keyboard activation.

## Pull request evidence

User-interface changes must include:

- the affected screens and states;
- narrow and wide layout screenshots;
- any known accessibility or responsiveness limitation.

Formatting tools cannot prove visual consistency. Shared components, design
tokens, screenshots, and acceptance checks are required.
