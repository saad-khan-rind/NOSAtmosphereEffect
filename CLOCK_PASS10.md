# Tenth pass — the Vulkan subject-mask bug, and taller faces

## 1. Background-only and clock depth applied to the whole wallpaper on Vulkan

Real bug, and it was in `VulkanAtmosphereHost.updateState`:

    val current = currentEffectState()          // (A)
    ...
    updateEffectState {                          // (B)
        safe.copy(
            hasSubject = if (isolationEnabled) current.hasSubject else false,
            clockTextureAspect = current.clockTextureAspect,
            clockFaceUploaded = current.clockFaceUploaded && safe.clockEnabled,

The lambda ignores its own argument and reads the `current` snapshot taken at
(A). `hasSubject`, `clockTextureAspect` and `clockFaceUploaded` are all
written by `prepareFrameOnWorker` on the **render worker**, while `updateState`
runs on whichever thread called `applyState` — and `applyState` runs on every
progress tick of the lock/unlock animation. A mask that finished extracting
between (A) and (B) was overwritten with `false`.

The loss is permanent, which is why it presented as "these two features never
work" rather than as a flicker:

- `takePending()` had already consumed the mask, so it is gone;
- `SubjectMaskCoordinator.request` will not re-dispatch for a generation it
  has already served (`latestRequest == generation`);
- so nothing sets `hasSubject` again until the image itself changes.

Segmentation takes a few hundred milliseconds and the unlock animation runs
for exactly that long, so the window is hit most times rather than rarely.

With `hasSubject` false the shader takes its documented fallback —
`viewport.w <= 0.5` means "no mask, so do not withhold the effect from the
whole frame" — and `clockMeta.z` is `clockDepth && hasSubject`, so depth
silently switches off too. One flag, both symptoms, exactly as reported.

Fixed by reading from the lambda's own argument:

    updateEffectState { previous ->
        safe.copy(
            hasSubject = isolationEnabled && previous.hasSubject,
            clockTextureAspect = previous.clockTextureAspect,
            clockFaceUploaded = previous.clockFaceUploaded && safe.clockEnabled,

`updateEffectState` is `AtomicReference.updateAndGet`, so the argument is the
value the CAS will actually replace — it cannot be stale by construction.

**`VulkanGlassHost` and `VulkanHalftoneHost` already did this correctly**
(`updateEffectState { current -> ... current.hasSubject }`). This host was the
odd one out, which is consistent with the report being specific to Original
Atmosphere.

Worth knowing for the next report: the GLES path stores `hasSubject` on the
texture set rather than in a snapshot-copied state object, so it was never
exposed to this. "Works in the app, not on the wallpaper" and "works on GLES,
not on Vulkan" are different failures with different causes; this was the
second.

## 2. Taller faces

`verticalStretch` raised across the board, and the height budget with it:

    Modern   1.34 -> 1.62      DEFAULT_HEIGHT  0.20 -> 0.24
    Mono     1.26 -> 1.52      MAX_HEIGHT      0.55 -> 0.65
    Serif    1.22 -> 1.46
    Stacked  1.20 -> 1.40
    Display  1.18 -> 1.38

Measured on 1080x2400, Modern, `HH:MM`:

                        bitmap      px/upload   width    glyph height
    original            1302x778    1,012,746   60%      9.4%
    pass 9              946x888       840,048   47%      11.9%
    pass 10             946x1025      969,650   49%      14.2%

Glyphs are now 51% taller on screen than the original, and the bitmap is
still smaller than the one the original shipped, so the per-frame upload cost
during the digit and entry animations has not regressed.

Stacked stays lower than the rest on purpose: two rows already carry vertical
presence, and pushing it further turns it into a thin column (it is already
only 18% of screen width against ~49% for the inline faces).

## 3. Clock on the other effects — still not done, and why

`AtmosphereClockPolicy.SUPPORTED_EFFECT_IDS` is still `setOf("ORIGINAL")`, so
the settings screen hides the clock section — including the new
lock/home/both control — for every other effect. That gate is deliberate: no
other renderer composites a clock, so showing the toggle would give you a
switch that does nothing.

The lock/home/both control has no effect anywhere yet either, because
Original Atmosphere is sharp only while locked, so `resolveScreen()` collapses
it to LOCK. Color Fill is the only pair sharp on both sides — it is the only
effect that will ever show the three-way choice, and it is one of the effects
that has no clock yet. The policy and the UI are correct; there is nothing
behind them.

What remains is one architectural change, not twelve small ones:

**Overlay pass.** A small blended quad drawn over the clock rect after each
effect's own draw, with its own program, sampling the clock face plus (where
one exists) the subject mask and sharp texture for depth.

- GLES: one new class, one line per renderer. No effect shader is touched.
- Vulkan: an optional second pipeline in the same render pass in
  `OnePassEngine`, with its own descriptor set and a push-constant rect. Every
  effect gets the clock at once.
- It also takes the clock back out of the Atmosphere UBO, which is what
  forced the binding shuffle that broke Vulkan in pass 8.

The alternative — extending each effect's own shader and uniform block — is
roughly 45 files and six more `std140` layouts to keep in sync with six
`static_assert` blocks. Passes 2 through 9 were spent on exactly that class
of error twice over (the vert/frag binding split, and this snapshot bug).
I am not going to add six more copies of it.

Nothing else in this pass depends on that decision, so it is all in and
testable now.
