# Mixed Slabs - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Regenerating

`generate_assets.py` writes the palette and the blockstate from the game jar in one run, so the two
cannot drift. Run it after a Minecraft version bump:

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
