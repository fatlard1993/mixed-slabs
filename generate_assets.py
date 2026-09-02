#!/usr/bin/env python3
"""
Regenerate everything the mixed slab blocks are described by, from the game jar and the
sibling mods sitting beside this one.

Four files come out, and they have to agree, so they come out of one run:

  src/main/resources/mixed_slabs/slab_palette.json
      Every mixable thing, split into the categories the blocks are split on. Read at mod
      init, before data packs exist, so it is a plain classpath resource.

  .../assets/mixed-slabs-justfatlard/blockstates/mixed_slab.json
  .../assets/mixed-slabs-justfatlard/blockstates/mixed_dirt_slab.json
  .../assets/mixed-slabs-justfatlard/blockstates/mixed_topper_slab.json
      One per block. Multipart, so each half's model is chosen by its own property and the
      file needs one case per thing per half rather than one per combination.

  .../assets/mixed-slabs-justfatlard/models/block/<name>_snapped.json
      For vanilla toppers only: the block's own model lifted onto the halfway line. A mod's
      toppers ship their own snapped model; vanilla does not know it is one.

Three kinds of thing can be half of a mixed slab:

  slabs    fill a half of the block. Either half.
  dirt     slabs that make a plantable surface. Top half only, in their own block, because a
           tag belongs to a block and not to a state.
  toppers  rest ON the lower half rather than filling the upper: a floor board, a torch, a
           carpet. Top half only, drawn with a snapped model, in their own block.

Usage: python3 generate_assets.py [path/to/minecraft-merged-deobf-<version>.jar]
"""

import copy
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent
NAMESPACE = "mixed-slabs-justfatlard"
ASSETS = HERE / "src/main/resources/assets" / NAMESPACE

DEFAULT_JAR_GLOB = (
    ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
    "minecraft-merged-deobf/*/minecraft-merged-deobf-*.jar"
)

# Where a topper is drawn: resting on whatever fills the lower half. Every slab-shaped lower
# half reaches the same eight pixels, so one lifted model serves for all of them.
SNAP_HEIGHT = 8

DYES = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")

# Vanilla blocks that may sit on a slab, sharing its block instead of spending the one above.
#
# The test each of these passes: its whole function is a constant property of the block, not a
# changing one. A torch emits light 14 and does nothing else, and light can be decided per
# state; a carpet does nothing at all. That is why a lever is not here - its function IS the
# powered state - nor a flower, whose function is the things you can do to it, nor redstone
# dust, which is nothing but changing state.
VANILLA_TOPPERS = ["torch", "soul_torch"] + [f"{dye}_carpet" for dye in DYES]

WOODS = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
         "pale_oak", "bamboo", "poplar", "crimson", "warped")

# Redstone components that sit on a surface and carry exactly one bit of their own.
#
# One bit is the whole test. A plate, a lever, a button and a redstone torch each store a single
# boolean and nothing else that matters, so a mixed slab can carry it; a repeater's delay, a
# comparator's mode, a weighted plate's sixteen power levels and redstone dust's connection shape
# cannot be carried and are not here.
#
# Their facing is not carried either, deliberately. A floor lever powers the same whichever way it
# points, so facing is cosmetic - and paying four times the states for it would cost more than the
# orientation is worth.
SIGNAL_TOPPERS = (
    ["stone_pressure_plate", "polished_blackstone_pressure_plate"]
    + [f"{wood}_pressure_plate" for wood in WOODS]
    + ["stone_button", "polished_blackstone_button"]
    + [f"{wood}_button" for wood in WOODS]
    + ["lever", "redstone_torch"]
)

# Toppers that must never be waterlogged, because water destroys them.
DROWNS = {"minecraft:torch", "minecraft:soul_torch"}

# Mods whose slabs are toppers rather than half-blocks. They ship their own snapped model.
TOPPER_NAMESPACES = {"wood-floor-justfatlard"}

# Mods whose blocks are redstone toppers. A lever torch is a lever in every way that matters -
# it inherits the block whole - so it flips, powers and delegates exactly as vanilla's does.
SIGNAL_NAMESPACES = {"lever-torch-justfatlard", "player-detector-justfatlard"}

# Signal toppers that only notice players. Vanilla plates answer to anything with feet; a player
# detector is built not to, and a mixed slab carrying one has to keep that or it stops being the
# block somebody chose it for.
PLAYER_ONLY = {"player-detector-justfatlard:player_detector"}

# Left out, with the reason. A mixed slab has room for two materials and no third state
# property, so a block whose behaviour lives in one cannot survive being embedded. Farmland's
# whole purpose is its moisture and the crops that read it; one in here would look like
# farmland and refuse to be farmed, which is worse than not being mixable.
EXCLUDED = {"dirt-slab-justfatlard:farmland_slab"}


def find_jar(argv):
    if len(argv) > 1:
        return pathlib.Path(argv[1])

    matches = [m for m in sorted(pathlib.Path.home().glob(DEFAULT_JAR_GLOB))
               if "sources" not in m.name]
    if not matches:
        sys.exit(f"No game jar found. Pass one explicitly.\nLooked in: ~/{DEFAULT_JAR_GLOB}")
    return matches[-1]


def read_json(jar, path):
    result = subprocess.run(["unzip", "-p", str(jar), path], capture_output=True)
    return json.loads(result.stdout) if result.returncode == 0 else None


def expand_tag(jar, path, seen=None):
    """Tag values, with nested tag references followed. #minecraft:slabs holds one."""
    seen = seen if seen is not None else set()
    values = read_json(jar, f"data/minecraft/tags/block/{path}.json")
    if values is None:
        return []

    out = []
    for value in values["values"]:
        value = value["id"] if isinstance(value, dict) else value
        if value.startswith("#"):
            nested = value[1:].split(":", 1)[1]
            if nested in seen:
                continue
            seen.add(nested)
            out += expand_tag(jar, nested, seen)
        else:
            out.append(value)
    return out


def pick_variant(variants, half):
    """The model for one half, ignoring extra state properties a mixed slab cannot carry.

    A slab with no extras keys its variants on "type=bottom" alone. One with an extra
    property spells it out - "snowy=false,type=bottom" - so an exact lookup silently drops
    it. Matching on the type alone is right for exactly the properties we are giving up.
    """
    exact = variants.get(f"type={half}")
    if exact is not None:
        return exact[0] if isinstance(exact, list) else exact

    for key, value in variants.items():
        if f"type={half}" in key.split(","):
            return value[0] if isinstance(value, list) else value
    return None


def half_models(blockstate):
    """A slab-shaped blockstate's two half-models, or None if it is not one."""
    variants = (blockstate or {}).get("variants")
    if not variants:
        return None

    bottom = pick_variant(variants, "bottom")
    top = pick_variant(variants, "top")
    if bottom is None or top is None or "model" not in bottom or "model" not in top:
        return None
    return bottom["model"], top["model"]


def namespace_roots():
    """Where each mod namespace's assets live, so a model id can be followed off the jar."""
    roots = {}
    for resources in sibling_resources():
        for assets in sorted(resources.glob("assets/*")):
            roots[assets.name] = assets
    return roots


def read_model(jar, model_id, roots):
    """One model file, from the game jar or from whichever mod owns its namespace."""
    namespace, path = model_id.split(":", 1) if ":" in model_id else ("minecraft", model_id)
    if namespace == "minecraft":
        return read_json(jar, f"assets/minecraft/models/{path}.json")

    root = roots.get(namespace)
    if root is None:
        return None
    file = root / "models" / f"{path}.json"
    return json.loads(file.read_text()) if file.exists() else None


def floor_models(variants):
    """A switch's two floor models: off, then on.

    Every one of these changes model with its bit - a lever leans, a button sinks, a plate
    presses, a torch goes dark - and that change is the only thing telling you what the circuit
    is doing. Picking one model and lifting it, which is what this used to do, produced a lever
    that looked identical whichever way it was thrown.
    """
    def pick(on):
        for key, value in variants.items():
            parts = dict(p.split("=", 1) for p in key.split(",") if "=" in p)
            # Floor-mounted only: a mixed slab holds what stands on it, not what clings to a wall.
            if parts.get("face", "floor") != "floor":
                continue
            bit = parts.get("powered", parts.get("lit"))
            if bit is None or (bit == "true") != on:
                continue
            chosen = value[0] if isinstance(value, list) else value
            return chosen.get("model")
        return None

    off, on = pick(False), pick(True)
    if off is not None and on is not None:
        return off, on

    # Some components draw the same whichever way they are: a player detector is two pixels of
    # plate that never visibly moves. One model for both states is the honest result, not a miss.
    plain = next(iter(variants.values()), None)
    if isinstance(plain, list):
        plain = plain[0]
    model = (plain or {}).get("model")
    return (model, model) if model else (None, None)


def resolve_model(jar, model_id, roots=None):
    """Follow a model's parents until the geometry turns up, merging textures on the way down.

    Vanilla's torch and carpet models carry nothing but a texture and a parent; the shape
    lives in a shared template. Lifting one means finding that template, so the chain is
    walked rather than assumed to be one deep.
    """
    textures = {}
    seen = set()

    while model_id and model_id not in seen:
        seen.add(model_id)
        model = read_model(jar, model_id, roots or {})
        if model is None:
            return None

        # A child's textures win over the parent's, so the parent's are only filled in behind.
        merged = dict(model.get("textures", {}))
        merged.update(textures)
        textures = merged

        if "elements" in model:
            return {"textures": textures, "elements": copy.deepcopy(model["elements"])}
        model_id = model.get("parent")
    return None


def lifted(model):
    """The same model, raised onto the halfway line and stripped of its culling.

    Culling is dropped because it is no longer true: a torch's underside was hidden by the
    block below it and is now in the open air above a slab, and a face culled against nothing
    is a hole.
    """
    raised = copy.deepcopy(model)
    for element in raised["elements"]:
        element["from"][1] += SNAP_HEIGHT
        element["to"][1] += SNAP_HEIGHT

        # The pivot moves with the thing it pivots. A lever's throw and a lever torch's swing are
        # rotations about a point at their base; lifting the geometry and leaving the origin behind
        # would swing them around a spot eight pixels underneath where they now stand.
        rotation = element.get("rotation")
        if rotation and "origin" in rotation:
            rotation["origin"][1] += SNAP_HEIGHT

        for face in element.get("faces", {}).values():
            face.pop("cullface", None)
    return raised


def sibling_resources():
    for resources in sorted(HERE.parent.glob("*/src/main/resources")):
        if resources.parts[-4] != HERE.name:
            yield resources


def mod_blocks(resources):
    """Slab-shaped blocks a sibling mod defines, found by their blockstate rather than a tag.

    A fence post slab is a slab in every way that matters here - it fills a half of a block
    and says so with the very property vanilla slabs use - but it is not in #minecraft:slabs
    and should not be, because that tag also means things about recipes and tools that a post
    is not. Offering both halves is the honest test.
    """
    found = {}
    for blockstate in sorted(resources.glob("assets/*/blockstates/*.json")):
        block_id = f"{blockstate.parts[-3]}:{blockstate.stem}"
        if block_id in EXCLUDED:
            continue
        try:
            models = half_models(json.loads(blockstate.read_text()))
        except json.JSONDecodeError:
            continue
        if models:
            found[block_id] = models
    return found


def dirt_blocks(resources):
    """What a sibling mod calls dirt, so we know which slabs make a plantable surface."""
    tag = resources / "data/minecraft/tags/block/dirt.json"
    if not tag.exists():
        return set()
    return {v["id"] if isinstance(v, dict) else v
            for v in json.loads(tag.read_text())["values"] if not str(v).startswith("#")}


def main():
    jar = find_jar(sys.argv)
    print(f"reading {jar.name}")

    models = {}            # block id -> (bottom-half model, top-half model)
    dirt = set()
    toppers = []
    signals = []

    for slab in sorted(set(expand_tag(jar, "slabs"))):
        found = half_models(read_json(jar, f"assets/minecraft/blockstates/{slab.split(':')[1]}.json"))
        if found:
            models[slab] = found
    slabs = sorted(models)
    print(f"  {len(slabs)} vanilla slabs")

    for resources in sibling_resources():
        found = mod_blocks(resources)
        dirt |= dirt_blocks(resources)
        if not found:
            continue

        namespace = next(iter(found)).split(":", 1)[0]
        if namespace in TOPPER_NAMESPACES:
            # A mod's topper ships its own snapped model; only its name is needed here.
            for block in found:
                models[block] = (None, f"{namespace}:block/{block.split(':', 1)[1]}_snapped")
            toppers += sorted(found)
            print(f"  + {len(found)} toppers from {resources.parts[-4]}")
        else:
            models.update(found)
            slabs += sorted(found)
            print(f"  + {len(found)} slabs from {resources.parts[-4]}")

    # Neither vanilla nor another mod knows its block is a topper, so the lifted models are ours.
    roots = namespace_roots()
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "models/block").mkdir(parents=True, exist_ok=True)

    def lift(model_id, out_name):
        resolved = resolve_model(jar, model_id, roots)
        if resolved is None:
            return False
        (ASSETS / "models/block" / f"{out_name}.json").write_text(
            json.dumps(lifted(resolved), indent=2) + "\n")
        return True

    for name in VANILLA_TOPPERS:
        variants = (read_json(jar, f"assets/minecraft/blockstates/{name}.json") or {}).get("variants", {})
        flat = variants.get("") or next(iter(variants.values()), None)
        if isinstance(flat, list):
            flat = flat[0]
        if not flat or "model" not in flat or not lift(flat["model"], f"{name}_snapped"):
            print(f"  ! {name}: no usable model, skipped")
            continue

        block = f"minecraft:{name}"
        models[block] = (None, f"{NAMESPACE}:block/{name}_snapped")
        toppers.append(block)
    print(f"  + {len(toppers) - 24} vanilla toppers, lifted")

    # A switch is lifted twice, because its model is how you read the circuit.
    signal_sources = [("minecraft", n) for n in SIGNAL_TOPPERS]
    for namespace in sorted(SIGNAL_NAMESPACES):
        root = roots.get(namespace)
        if root is None:
            continue
        for blockstate in sorted((root / "blockstates").glob("*.json")):
            signal_sources.append((namespace, blockstate.stem))

    for namespace, name in signal_sources:
        if namespace == "minecraft":
            blockstate = read_json(jar, f"assets/minecraft/blockstates/{name}.json")
        else:
            file = roots[namespace] / "blockstates" / f"{name}.json"
            blockstate = json.loads(file.read_text()) if file.exists() else None

        off, on = floor_models((blockstate or {}).get("variants", {}))
        if off is None or on is None:
            print(f"  ! {namespace}:{name}: no floor on/off pair, skipped")
            continue
        if not lift(off, f"{name}_snapped") or not lift(on, f"{name}_on_snapped"):
            print(f"  ! {namespace}:{name}: could not resolve geometry, skipped")
            continue

        block = f"{namespace}:{name}"
        models[block] = (None, (f"{NAMESPACE}:block/{name}_snapped",
                                f"{NAMESPACE}:block/{name}_on_snapped"))
        signals.append(block)
    print(f"  + {len(signals)} redstone toppers, lifted in both states")

    palette = slabs + toppers + signals
    topper_set = set(toppers) | set(signals)
    terrain = [b for b in slabs if b in dirt]
    ordinary = [b for b in slabs if b not in dirt]

    (HERE / "src/main/resources/mixed_slabs").mkdir(parents=True, exist_ok=True)
    (HERE / "src/main/resources/mixed_slabs/slab_palette.json").write_text(json.dumps({
        "slabs": palette,
        "terrain": terrain,
        "toppers": toppers,
        "signals": signals,
        "drowns": sorted(DROWNS & topper_set),
        "player_only": sorted(PLAYER_ONLY & set(signals)),
    }, indent=2) + "\n")

    # Bottom cases are shared by all three blocks; only a slab may fill a lower half, because
    # nothing would be resting on a topper.
    bottom_cases = [{"when": {"bottom_slab": b.split(":", 1)[1]},
                     "apply": {"model": models[b][0]}} for b in slabs]

    (ASSETS / "blockstates").mkdir(parents=True, exist_ok=True)
    total = 0
    # Every block carries "waterlogged": a fence post can be either half and leaves most of its
    # half empty, so the property has to exist on all of them for any of them to hold water.
    for name, tops in (("mixed_slab", ordinary),
                       ("mixed_dirt_slab", terrain),
                       ("mixed_topper_slab", toppers),
                       ("mixed_signal_slab", signals)):
        cases = list(bottom_cases)
        for t in tops:
            path = t.split(":", 1)[1]
            top = models[t][1]
            if isinstance(top, tuple):
                # A switch shows its state, so each half of the pair is chosen by the bit.
                cases.append({"when": {"top_slab": path, "powered": "false"},
                              "apply": {"model": top[0]}})
                cases.append({"when": {"top_slab": path, "powered": "true"},
                              "apply": {"model": top[1]}})
            else:
                cases.append({"when": {"top_slab": path}, "apply": {"model": top}})
        (ASSETS / "blockstates" / f"{name}.json").write_text(
            json.dumps({"multipart": cases}, indent=2) + "\n")

        states = len(slabs) * len(tops) * 2
        total += states
        print(f"  {name}: {len(slabs)} x {len(tops)} = {states} states, {len(cases)} cases")

    print(f"  {len(slabs)} slabs ({len(terrain)} dirt) + {len(toppers)} toppers "
          f"+ {len(signals)} redstone -> {total} states in total")


if __name__ == "__main__":
    main()
