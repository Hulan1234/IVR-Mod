# Render data invalidation contract

`RenderDataRevision` is bumped after `mtr.client.ClientCache.syncAdditional` and
`net.hulan.ksd.client.KSDClientCache.syncAdditional` return.  It is a publication
marker only; PIDS arrivals, countdowns, language rotation, and scrolling text
remain evaluated by their original renderer every frame.

Block entity `setChanged` marks that instance dirty.  The optional read hook marks
network `readCompoundTag` updates for IVR signs and MSD sign/PIDS entities.  A
renderer may use the instance revision to lazily rebuild geometry or layout on
the client thread; shared door meshes are not globally cleared by data updates.

Optional MSD bounds registration never loads MSD types unless the addon is
present.  The PIDS and custom-text-sign renderers expose the same six finite
layout fields (`scale`, `totalScaledWidth`, `maxHeight`, `startX`, `startY`, and
`startZ`) in every supported addon jar.  Their renderer mixin records a
conservative radius from those actual values while the native render call is
being made; until a finite radius has been captured, the visibility provider
returns no bounds and the entity remains visible.  Schedule, scrolling, and
language data continue to be evaluated by the original renderer.

Yamanote railway-sign bounds are derived from the actual block's `length` and
`getXStart()` values.  Reflection failures return no custom bounds, which keeps
the sign visible instead of applying a guessed box.
