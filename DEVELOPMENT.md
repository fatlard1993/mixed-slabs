# Mixed Slabs - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Regenerating

`generate_assets.py` writes the palette, the four blockstates and the lifted models for vanilla things
that rest on a slab from the game jar in one run, so they cannot drift. Run it after a Minecraft
version bump:

```
python3 generate_assets.py
```

Each slab's two half-models are read out of its own blockstate rather than guessed from its name:
the eight waxed copper slabs point at the unwaxed models, and assuming `<name>.json` /
`<name>_top.json` would have silently produced eight invisible halves.

Vanilla slabs come from the `#minecraft:slabs` tag. A sibling mod's are found **structurally** —
any block whose blockstate offers both a `type=bottom` and a `type=top` variant is slab-shaped enough
to be half of a mixed one. That is how fence post slabs join in: they fill a half of a block exactly
as a slab does and say so with the very same property, but they are not in `#minecraft:slabs` and
should not be, since that tag also means things about recipes and tools that a post is not.

A mod's slabs are only mixable if this generator can see its source when it runs, so building
mixed-slabs on its own yields a vanilla-only palette. That is the right answer: a blockstate cannot
reference a model from a mod that is not installed without the client logging a missing model.

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients
need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and
`fabric.mod.json` (Java).

## How it works

A position holds one block state, and one block state cannot name two materials. So a mixed slab is
a block of its own whose state says which slab is in each half.

### Four blocks

`mixed_slab`, `mixed_dirt_slab`, `mixed_topper_slab` and `mixed_signal_slab`, split by what is in
the **top** half. A tag belongs to a block and not to a state, so "this surface is dirt, plants may be
placed on it" cannot be said about some states of one block; splitting on the surface says it about
all the states of one of them, and `mixed_dirt_slab` sits in `#minecraft:dirt`. A thing resting on a
slab is drawn where the lower half ends rather than where the upper half begins, which is a different
model for the same top half, so it gets `mixed_topper_slab`; a redstone component or candle carries
an on/off bit (`powered`) the others have no use for, so it gets `mixed_signal_slab`, which has no
`waterlogged`. The four share every possible bottom half and divide the top halves between them, so
together they hold exactly the combinations a single block would, at no extra states.
`Main.stateFor` is the one place that knows the split.

### The rule

> A mixed slab is a full-height block whose surface material is its top half.

Ask it about material and it answers from a half. Nothing should read a slab type off one or offset
a plant onto one: a plant standing on a mixed slab stands at full height, which is why vanilla plants
are right for it and dirt-slab's half-height plant variants are not. Its **shape** is the union of
the two halves: a full cube for two ordinary slabs, less for a fence post slab, and collision,
culling and light occlusion all follow the real shape.

### States and models

- The palette (`slab_palette.json`) has 240 entries: 147 slabs, 42 toppers (boards, torches,
  carpets) and 51 signal components (plates, buttons, lever, redstone torch, candles, plus lever-torch
  and player-detector). 147 bottoms x 240 tops is 35,280 combinations, and 70,560 states since each
  block carries one more bit. `Main` logs the live numbers at startup.
- The blockstates are **multipart**: each half's model is selected by its own property, so each file
  needs one case per thing per half (two for a signal component, on and off) rather than one per
  combination.
- The slab models are vanilla's own, or the sibling mod's. Only the vanilla things that rest on a
  slab get a model generated here, lifted onto the halfway line. Vanilla does the drawing, so
  lighting, AO, culling and break particles are the real ones and there is no client rendering code.
- Hardness is the harder half's (`getDestroyProgress` takes the min of the two halves' progress);
  drops ask each half its own `requiresCorrectToolForDrops` and loot table, which is why the block's
  properties never set that flag. The client stand-in uses strength 2.0, or 0.65 for the dirt block.

### Signals

The components are not reimplemented. Everything a signal is read through (`isSignalSource`,
`getSignal`, `getDirectSignal`) is answered by building the real component's state from the one bit
and asking it (`MixedSignal.componentOf`). Only the writing is ours: a plate reads entities in the
space above, a lever toggles, a button schedules its release, a torch inverts on a scheduled tick.
One bit is the whole test, which is why repeaters, comparators, weighted plates and dust are not
carried. Floor switches have no facing, since facing is cosmetic on a floor and would multiply the
states by four. A candle uses the same bit for lit; its count does not fit, and its flicker particle
is a client-side animation tied to the vanilla candle block, so a candle on a slab holds still.

### MixedSlabsApi

A mixed slab is its own block, so every "what block is this?" sees a mixed slab and not the two
slabs inside it. `MixedSlabsApi` is a small static surface over vanilla types, reached by reflection
so nothing has to depend on this mod:

- `isMixedSlab(state)`
- `halves(state)` / `topHalf(state)` / `bottomHalf(state)`
- `withTopHalf(state, block)`: resurfacing, for grass creeping on or a shovel making a path. May hand
  back a state of a different one of the four blocks, since which one holds a combination depends on
  its top.

Callers so far: conductive-copper (`integration/MixedSlabIntegration.java`, one copper half is
enough to conduct) and dirt-slab (`integration/MixedSlabIntegration.java`, grass spread, path, hoe).
