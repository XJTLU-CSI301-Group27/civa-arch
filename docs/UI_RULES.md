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
  cards. All window sizes stack the three meals vertically; do not restore
  a wide-screen parallel-meal override. The screen never scrolls vertically; `BdHomeLayout` shrinks
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

## EF bakery onboarding and profile (2026-11-25)

- Follow the existing cute, warm bakery-cat visual system. `BakeryWelcomeLayout`
  owns the 26/74 light-caramel / textured butter-paper scene, supplied chef-cat cutout, greeting and 1100ms
  panel-rise entrance; Login and Household supply forms and localized copy.
- A ~7042ms bundled silent-video cold-launch scene belongs to app; logout only replays the short Login
  entrance. Hide bottom/rail navigation on Login and Household. Preserve keyboard
  access with scrollable forms and a bounded form width in wide windows.
- `ChefCatView` remains available for old callers; new scenes use the supplied
  transparent cat assets. `MeowSound` owns the bundled CC0
  sound. Home's `BakeryCatStage` plays it alongside the existing transient bubble.
  Release sounds/animations with the view. Do not force device media volume.
- Profile uses an overlapping circular cat avatar, identity card with registered
  display name/ID/edit action, cat on the upper-right rim and the existing family
  card. Language and edit dialogs have small X glyphs with usable touch targets.
- Recipe details have a small top-left X returning to a fresh default recipe list.
  Keep the existing recipe async-view lifecycle guard intact.
- Details, source attribution, screenshots and limitations:
  [EF_BAKERY_UI_HANDOFF.md](EF_BAKERY_UI_HANDOFF.md).


## Supplied cat illustrations and video revision (2026-11-25, round 2)

- Login title: 和我一起CIVA; remove the login-sharing subtitle. Reveal the tall-hat
  illustration upwards from behind the panel rim, instead of dropping it down.
- Reuse `civa_welcome_brown` in the welcome scene, intro and auth system-bar
  backdrop. The welcome sheet uses the same PaperBackdropView as Today.
- Today keeps only the main 今天吃什么 title beside the supplied bread-cat art.
  Remove eyebrow/caption in both ordinary and wide layouts; preserve meal bounds.
- Recipes show the supplied pan-cat above Search, to the right of source chips;
  allow source chips to scroll horizontally in narrow windows.
- Profile uses the full four-cat group on the rim, a globe icon in a rounded
  square language button, and the supplied egg-chef default avatar. Never replace
  a user's saved custom avatar when changing the default drawable.
- Language dialog: 语言选择 heading, 22sp 中文/英文 buttons. Language switching is
  still intentionally unimplemented. Keep the existing close/press behavior.
- The intro uses the supplied frame sequence with no original audio track,
  then plays the shared meow separately; verify actual video completion, not the
  fallback poster. Full details: [EF_CAT_REFRESH_HANDOFF.md](EF_CAT_REFRESH_HANDOFF.md).

## Layout polish (2026-11-25, round 3)

- Welcome greeting: 19sp, anchored next to the cat and closer to its face; Login's
  form uses 28dp top / 116dp bottom padding to move the full group upward safely.
- Today: title left, smaller cat right; match ordinary and wide layouts. Preserve
  meal bounds and cat interaction while adding 12dp padding to its region.
- Recipes: 112×110dp cat at header bottom/end, close to Search. Reserve 116dp at
  the right and retain source-chip scrolling. Allow overflow only inside the
  header+search group; keep page-level clipping for recipe rows and pagination.
- Current measurements, screenshots and source diff: [EF_LAYOUT_POLISH.md](EF_LAYOUT_POLISH.md).


## Current login spacing and green intro (2026-11-25, round 4)

- Login inner padding is now 20dp top / 180dp bottom (about 36dp further up on the full phone). Keep the form scrollable.
- Today title container translates 24dp toward the right-hand cat in ordinary and wide resources; keep text width and cat bounds.
- Intro now uses the updated green-source video, composited onto #97745A, silent VP8 720×780 / 169 frames / 24fps; preserve aspect ratio and shared meow.
- Current file audit, source details and screenshots: [EF_GREEN_INTRO_HANDOFF.md](EF_GREEN_INTRO_HANDOFF.md).


## Catalogue actions and optional household setup (2026-11-25)

- Keep the create-recipe pill beside the page title, visible for My/Household and invisible otherwise; source chips retain their full row. Empty owned lists offer Add Recipe.
- Favorites reserve 32dp below the overflowing pan-cat when search/categories are hidden; preserve page clipping for list/footer.
- Household X/system Back go to Profile, including first login. First-login onboarding replaces Login to prevent an authenticated Login from redirecting Skip back to Household.
- Profile without membership shows 还尚未加入家庭哦 / 加入家庭. Joined users retain the ID/settings card.
- Ordinary members can confirm Leave beneath Join; owners see the existing restriction, with no transfer/dissolve. Retain shared motion and usable small-glyph close targets.
- Exact changes/screenshots: [EF_FAMILY_CATALOG_HANDOFF.md](EF_FAMILY_CATALOG_HANDOFF.md).

## Current empty states, title and household navigation (2026-11-25)

This section supersedes the preceding round's empty-action / onboarding-return rules. Remove the central Add Recipe button; retain the title-row Create action. Loaded empty Favorites says 这里还空着，快加入你最想吃的菜吧. Today title moves toward the cat (36dp ordinary / 28dp wide), max text 38sp / compact 25sp, preserving cat/meal bounds.
Initial login setup: create/join/X/Back → Today. Profile-origin setup: create/join/leave/X/Back → Profile. Persist the entry origin on recreation. Preserve owner leave restriction. Owner-only Generate Invitation offers selectable/copyable 26-character code with expiry/single-use guidance; family UUID receives a specific error, not a network request. Hide keyboard on submit and scroll errors into view.
Invitation candidate approval status and screenshot evidence: [EF_EMPTY_INVITATION_HANDOFF.md](EF_EMPTY_INVITATION_HANDOFF.md).

## Split household settings (2026-11-25, current)

Joined Profile shows actual household name, ID and Settings. Joined Household shows family heading, 邀请码, selectable fixed code, 复制邀请码 and 退出家庭; no setup fields, generate/rotate action, role restriction or recovery UI. Unjoined Household retains Create-name and Join-code forms; remove code-vs-ID helper sentence. Every member follows the same display and leave flow. Keep the small X target, shared bounce and warm cat theme.
Initial setup outcomes → Today; Profile-origin outcomes → Profile. Unknown writes keep same-key recovery; definitively rejected writes clear their persisted intent so no false “abandon” prompt. Screens and current rule precedence: [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md).

## Joined family name editor (2026-11-25, current)

Only joined Household content uses TOP gravity with30dp top/bottom padding. Header is 修改家庭名, then editable current name, fixed invitation/copy, primary 保存 and 退出家庭. Save stays on page and confirms success; closing unsaved draft cancels, recreation preserves draft. Conflict must preserve input and request explicit retry. Do not replace editor header with actual family name. Unjoined setup/Login retain their layout and behavior; preserve shared warm cat theme, small close target and press bounce. See [EF_FAMILY_NAME_HANDOFF.md](EF_FAMILY_NAME_HANDOFF.md).


## Tablet layout policy (2026-11-26, current)

User explicitly requests bottom navigation for all authenticated main tabs, including wide/tablet windows; Login/Household continue hiding navigation. Three meals stay in one vertical column, with identical empty/populated card bounds. Remove both obsolete w600dp overrides when integrating. Welcome paper children must be explicitly top-corner clipped on API27; do not rely on asymmetric outline clipping. Exact audit, real-tablet screenshots and checks: [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md). This supersedes historical wide rail/parallel-meal descriptions.


## Startup keyboard (2026-11-26)

Login gives initial touch-mode focus to its welcome container, not an EditText. MainActivity starts with stateHidden|adjustResize. Never repeatedly hide IME on resume/animation; tapping username/password must still open it normally. Current real-tablet verification: [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md).


## Today title sizing stability (2026-11-26)

Do not combine framework autosize with this wrap_content hero title. BdHomeLayout fits from the parent width/hero-height budget using current font metrics, starting from38sp normal/25sp compact on every measure; minimum16sp remains. Never use previously shrunken title height as the available height. Full window restoration must restore normal size without navigation/date/meal changes. See [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md).
