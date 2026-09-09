# Eleventh pass — one writer for `hasSubject`, and diagnostics that name the step

## What I got wrong twice

Passes 9 and 10 both tried to fix Vulkan subject isolation by making the
state object carry `hasSubject` correctly. Pass 10's fix (reading the
`updateAndGet` argument instead of a pre-read snapshot) was a real race and
worth fixing, but it clearly was not the whole story, and I should have
stopped trying to make the carry-forward correct and instead removed the
carry-forward.

`hasSubject` describes a **GPU resource** — whether a real mask is bound to
binding 2 — not a user setting. Carrying it inside `AtmosphereRenderState`
means every path that rebuilds the state from the controller's snapshot has
to remember to carry it forward:

- every progress tick of the lock/unlock animation (`applyState`)
- every preference change
- every auto-colour re-derivation
- every blob frame update

Segmentation finishes somewhere inside that traffic. One missed carry-forward
reverts the flag permanently, because `takePending()` has already consumed
the mask and `SubjectMaskCoordinator` will not re-dispatch for a generation
it has served.

`VulkanAtmosphereHost` now keeps `subjectMaskUploaded` as its own
`@Volatile` field, written in exactly one place (the mask upload) and cleared
in exactly three (new wallpaper, surface reset, isolation switched off). The
uniform value is derived from it at the point of use:

    hasSubject = isolationEnabled && subjectMaskUploaded

`clockFaceUploaded` and `clockTextureAspect` get the same treatment — they
are read from `clockTexture.hasUploadedFace` and `clockTexture.aspectRatio`,
which are the actual owners, instead of being carried through the state.

There is now no path by which a state rebuild can lose any of the three.

## Diagnostics, because I am not guessing a fourth time

Every step of the mask path writes one line to the renderer diagnostics log
(**Advanced Settings -> Diagnostics -> Renderer diagnostics**), category
`atmosphere-subject`:

    mask requested for generation 2 (1080x2400) -> dispatched
    mask requested for generation 2 (1080x2400) -> DROPPED by coordinator
    no mask requested for generation 2: subject isolation is off
    mask dropped: extracted for generation 1, texture is now 3
    mask dropped: subject isolation was switched off
    mask upload ok (256x256, config=ARGB_8888, generation=2,
        glassBackgroundOnly=true, clockDepth=true)
    mask upload FAILED (256x256, config=ARGB_8888, ...)

Apply the wallpaper, lock and unlock once, then read the log. Those seven
lines cover every way the mask can fail to reach the shader, and each names
a different cause:

- **no line at all** -> `onWallpaperUploadedOnWorker` is not running, so the
  problem is upstream of the mask entirely.
- **"subject isolation is off"** -> `needsSubjectMask()` is false, i.e. the
  preferences are not reaching the state. Compare with what the GLES path
  gets.
- **"DROPPED by coordinator"** -> the generation was already served; the
  request was never dispatched.
- **"mask dropped: extracted for generation N"** -> generations are slipping,
  probably a reload storm.
- **"mask upload FAILED"** -> native. `uploadBitmap` rejects anything that is
  not RGBA_8888, and the line prints the actual config and size, so this
  says which. A native detail line will also appear under it.
- **"mask upload ok" but the effect still covers everything** -> the flag is
  reaching the uniform and the problem is in the shader or the sampler, which
  is a completely different search.

## One thing to check that would change the whole picture

`VulkanGlassHost.prepareFrameOnWorker` **returns `uploaded`** — a failed mask
upload there is fatal, so Vulkan is torn down and the effect falls back to
OpenGL ES. `VulkanAtmosphereHost` deliberately made the same failure
non-fatal in pass 4.

So "Glass works fine" is consistent with two very different situations:

1. Glass really is running on Vulkan with a working mask — in which case the
   bug is specific to the Atmosphere host, or
2. Glass's mask upload is failing too, Glass fell back to OpenGL ES, and it
   "works" because it is not on Vulkan at all — in which case the bug is in
   the shared native upload and Atmosphere is simply the only effect that
   stays on Vulkan to show it.

The diagnostics screen distinguishes these in one look: if there is a
`vulkan-fallback` line for Glass, it is situation 2, and that localises the
bug to `uploadBitmap` / `installStagedTexture` rather than to anything in the
Atmosphere host. Please check this — it is the single most informative thing
in the log right now.

## Clock on all effects — not in this pass, and I am not going to fake it

`SUPPORTED_EFFECT_IDS` is still `setOf("ORIGINAL")`. I could flip that flag
in one line and the clock section, including the lock/home/both control,
would appear for every effect — and do nothing for eleven of them, because no
other renderer composites a clock. That is worse than not showing it.

The work itself is one architectural change:

**Overlay pass.** A blended quad drawn over the clock rect after each
effect's own draw, with its own program, sampling the clock face plus (where
one exists) the subject mask and sharp texture for depth.

- GLES: one new class, one line per renderer, no effect shader touched.
- Vulkan: an optional second pipeline in the same render pass in
  `OnePassEngine`, its own descriptor set, a push-constant rect. Every effect
  gets the clock at once.
- It also removes the clock from the Atmosphere UBO — the thing that forced
  the binding shuffle that broke Vulkan in pass 8.

The alternative is ~45 files and six more `std140` layouts kept in sync with
six `static_assert` blocks. This branch has already lost two passes to
exactly that class of error.

It is a full pass of work on its own, and doing it in the same change as a
blind Vulkan bug hunt is how the last three passes went. This pass is the bug
hunt with an instrument attached; the next one is the overlay pass.
