# Test Plan

## Required first-release tests

| Test | Expected result |
| --- | --- |
| Recipe and inventory matching | One canonical ingredient and unit rule produces one result |
| Locked meal slots | Regeneration preserves locked slots |
| Plan versus meal log | Future plans never enter actual meal statistics |
| Cooking and inventory | Confirm cooking deducts according to batch rules |
| Duplicate request | Double tap or retry does not duplicate inventory movements |
| Household sharing | Members see consistent shared data |
| Authorization | Non-members and removed members cannot read or write |
| Personal isolation | One user's meal log is not copied to another member |
| Historical snapshot | Editing a recipe does not rewrite saved meal-entry data |
| Concurrent edits | Conflicts are detected and not silently overwritten |
| Unit validation | Incompatible units are rejected or clearly reported |
| Unknown calories | Unknown calories are not treated as zero |

## Ownership

- AC owns recipe and inventory tests.
- BD owns planning, recommendation, cooking, and meal-record tests.
- EF owns shared authorization, concurrency, synchronization, and integration
  tests.
- EF wires all required tests into CI but does not replace feature-owner tests.

## Evidence

Each pull request must list:

- commands actually executed;
- actual pass or fail results;
- tests that were not run;
- remaining risk for uncovered behavior.


## Recipe view lifecycle regression (2026-11-24)

- Open a recipe, then select Today; repeat with several public recipes and each source list.
- Pending/cancelled/queued-success callbacks must not touch a destroyed recipe view.
- Returning to a recreated list must still load; old callbacks cannot mutate the new view.
- Verify normal system back, rapid tab changes and wide/narrow view recreation; preserve saved plans.
- RecipeViewCallbacksTest covers the callback boundary with 8 unit tests; device evidence and exact results are in RECIPE_CATALOG.md.

## Bakery onboarding/profile regression (2026-11-25)

- Cold-launch: supplied ~7.04s CIVA video with shared meow + bubble; then sheet rise,
  supplied cat reveals upward and form fades. Screen recreation must not restart the full intro.
- Login/register/create/join retain their real backend actions. Login and
  Household show no bottom/rail navigation, in narrow and wide windows.
- Logout replays Login entrance without the long intro and without navigation.
- Profile initial display name comes from registration, not shortened UUID.
  Open language dialog: both intentional placeholder buttons remain inert; X closes.
- Edit profile: default cat, optional system photo import, save name/avatar,
  reopen, sign out/in and restart/login; another account must not inherit them.
  X/back cancel without overwriting stored values; test invalid names/images.
- Recipe X returns to the default list. Revisit detail and Today; retain the
  previous callback lifecycle regression tests and existing plans.
- Home cat click: preserve bounce, show temporary bubble and play bundled sound;
  release playback on detach. Check media volume and real-device audibility.
- `ProfileIdentityTest`: 8 new isolation/name/filename checks. All 120 Android unit
  tests and lint pass; evidence and actual coverage limits are recorded in
  [EF_BAKERY_UI_VERIFICATION.md](EF_BAKERY_UI_VERIFICATION.md).


## Cat/video visual regression, round 2 (2026-11-25)

- Confirm the 26/74 Login background, paper grain, 和我一起CIVA title and removed
  subtitle. Short logout entrance must reveal the cat from below and hide navigation.
- Verify all five supplied illustration roles in narrow and wide views; saved
  custom avatars must keep precedence over the new egg-cat default.
- Home no longer has eyebrow/caption; BdHomeLayout must not access a deleted
  caption view. All three empty/populated meal cards retain their existing bounds.
- Recipes' cat stays to the right of source choices and above Search; sources,
  recipe loading and the previous detail-to-Today callback guards remain usable.
- Language dialog shows its heading and enlarged labels; both language buttons
  remain placeholders and X closes. Existing edit-avatar picker is preserved.
- Media must have only one VP8 video stream and no original audio. Verify
  CivaIntro video_started/video_completed in the same app process with no fallback;
  inspect the actual CIVA sequence, matching background, meow timing and Login transition.
- The current API 37.1 emulator failed initial H.264 decoding; use the final VP8
  resource, and keep that failed attempt distinct from final successful evidence.
- Exact checks/screenshots/remaining limits: [CAT_REFRESH_VERIFICATION.md](CAT_REFRESH_VERIFICATION.md).

## Three-screen spacing regression (2026-11-25, round 3)

- Login: larger greeting near the cat; entire form higher; all input/actions remain
  reachable through keyboard and scroll behavior. Warm logout still has no bottom bar.
- Today: title left of the reduced cat in both phone and wide layouts; meal cards
  retain bounds and the cat stays clickable.
- Recipes: larger complete cat touches the space above Search without clipping,
  covering source actions or intercepting search input. Check actual rendered
  outline-to-search spacing, not just ImageView bounds.
- Results: [EF_LAYOUT_POLISH.md](EF_LAYOUT_POLISH.md).


## Updated green-video and spacing regression (2026-11-25, round 4)

- Compare complete empty Login form positions against the third round; preserve scroll access with input focus.
- Verify Today title moves toward the cat without moving its bounds or clipping single-line wide text.
- Verify full new cat/CIVA video sequence, removed green background, preserved white paint, correct aspect ratio, actual completion without fallback, and meow bubble.
- Verify warm logout still skips the long intro; retain production local files across update.
- Actual results and remaining audio/device limits: [EF_GREEN_INTRO_HANDOFF.md](EF_GREEN_INTRO_HANDOFF.md).


## Catalogue / household skip and leave regression (2026-11-25)

- Source tabs must not be displaced by Create. Verify My/Household editors and empty-state action, Favorites cat clearance, quick source switching and narrow/wide views.
- New account: login, Household X, Profile with no membership; reopen/X and system Back return Profile. No restored Login may redirect Skip.
- Member: join A, leave with confirmation, stay logged in, no-family Profile, join B; old shared requests/cache must not affect B. Owner: reject leave and keep membership.
- Server: permission isolation, expectedVersion conflict, inactive history, repeat delete and revoked reads. Core-data: stale account callback rejection and lost-response reconciliation.
- Current verified results and coverage limits: [EF_FAMILY_CATALOG_HANDOFF.md](EF_FAMILY_CATALOG_HANDOFF.md).

## Empty states, return origin and invitation regression (2026-11-25)

This supersedes the preceding first-login → Profile expectation: initial Skip/Create/Join must select Today; Profile Close/Create/Join/Leave must select Profile. Verify arguments survive recreation, no old Login redirect, owner cannot leave. Verify no center empty-action and exact Favorites copy on phone/wide screens; title closer/larger, no cat overlap.
Use isolated real PostgreSQL: OWNER creates a code through the API/UI, same-key replay returns the original code with one invitation row, persisted receipt excludes plaintext, nonowner denied, used/expired invite denied, another account joins. Family UUID must show actionable guidance with keyboard dismissed. Test cipher restart, corruption/wrong key and invalid key-file protection. Do not seed invitations in SQL for the final end-to-end flow.
Final local results: Android124, server6 (three integration methods plus three cipher methods), contract tools16 all pass; lint0errors/83warnings. Independent QA package removed, production files unchanged. Phone/wide screenshots and coverage limits: [EF_EMPTY_INVITATION_HANDOFF.md](EF_EMPTY_INVITATION_HANDOFF.md).

## Permanent shared family acceptance (2026-11-25, current)

Supersede owner-cannot-leave/one-use assertions with equal MEMBER, creator-leave and reusable-code tests. Verify auto-provision in creation transaction, same code to multiple members and POST/GET/retries, expiresAt explicit null, encrypted DB value, nonmember denied, no removing others, version conflict, revoked replay denial, last-leave closure and old-code rejection. Run join-vs-final-leave and simultaneous final-two-leave races; closed household must never retain active members.
Verify upgrade from old roles/invitations without losing household/member/history/private data; back up database/key first. Verify fixed code after real backend restart. Real UI: Profile actual name/ID; joined settings only code/copy/leave; unjoined create/join; reusable code after creator leaves; final leave, failed old-code retry and no stale pending record; origin navigation and phone/wide recreation. Production update must preserve existing local files.
Executed locally: Android124 + complete server59 + contract-tools16 pass, lint0errors/83warnings; normal dev migration/data audit, isolated UI and restart verified. Logs/limits: [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md).

## Family name settings acceptance (2026-11-25)

Verify joined group moves up near sheet top, order name input/invitation-copy/save/leave, loaded value editable and top header fixed. Empty/max120 validation, real save and success-stay, Profile/other member reads new shared name, cancel doesn't save, recreation retains draft, stale version preserves draft and explicit retry works. Invitation and ID must remain unchanged. Check ordinary member as well as creator, unjoined setup unchanged, short-height scroll reachability.
Run server rename permission/version/no-op and concurrent same-version tests with real isolated PostgreSQL; keep all previous lifetime/invitation tests. Locally executed Android124, full server61, contract tools16, lint0errors/83warnings, real UI phone/wide. Lost-response failure injection/physical device/latest main not executed. Evidence and exact scope: [EF_FAMILY_NAME_HANDOFF.md](EF_FAMILY_NAME_HANDOFF.md).


## Tablet layout regression (2026-11-26)

On API27 verify both welcome top corners against paper children, including warm logout; all five main tabs keep bottom navigation at narrow/wide sizes. Today breakfast/lunch/dinner must form one visible column above navigation; add a dish to each and compare exact empty/populated bounds. Remove both obsolete w600dp resources from packaged APK; clean/rebuild if incremental resources persist. Actual real-tablet, 1920x1200/820x1820 simulated-window results and retained production data: [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md).


## Startup keyboard regression (2026-11-26)

On real Xiaomi API27 check cold launch after intro and force-stop/relaunch: IME hidden and no input focused. Tap username then password: each opens IME, Back dismisses it. No credentials entered/submitted; original app files retained. Executed checks and two-file source diff: [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md).


## Personal Today drafts versus household changes (2026-11-26)

Seed old same-account null-family meals with photo; verify migration preserves date/slot/snapshot/photo bytes and source file. Create, leave, join another household: personal draft JSON remains identical. Sign out/in same user retains meals; another account sees none. Migration duplicate IDs prefer current values; deletion/restart must not resurrect old entries; interrupted photo copy must be retryable. Actual unit/device results and verified production recovery: [BD_PERSONAL_PLAN_HANDOFF.md](BD_PERSONAL_PLAN_HANDOFF.md).


## Debug API target precedence (2026-11-26)

Verify actual installed APK target as well as USB connectivity: Studio/no-flag debug uses local civa.debugApiBaseUrl, explicit CIVA_API_BASE_URL overrides it, no local setting falls back to10.0.2.2. Release ignores local debug key and requires explicit HTTPS. Keep old SDK settings and original app data. Actual tests and successful user login: [EF_USB_TABLET_HANDOFF.md](EF_USB_TABLET_HANDOFF.md).


## Today title resize regression (2026-11-26)

Measure title bounds after repeated Recipes/Today transitions, two short-window→normal cycles, narrow-window restoration, and add-dish keyboard dismissal. Normal title height must match its initial full-size value; short windows may intentionally use one compact line. Keep actual physical size, personal files and localhost USB target unchanged. Executed API27 evidence: [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md).
