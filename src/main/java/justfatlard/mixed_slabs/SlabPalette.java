package justfatlard.mixed_slabs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * The slabs that can be mixed, in a fixed order.
 *
 * <p>Read from the jar rather than from the {@code #minecraft:slabs} tag, because the block and its
 * ten thousand states have to exist at mod initialisation and tags do not load until a world does.
 * {@code generate_assets.py} writes this list and the blockstate that draws it in the same run, so
 * the two cannot drift.
 *
 * <p>A slab is addressed by its path - {@code oak_slab} - because that is what a blockstate property
 * value can spell. The list is generated from the vanilla tag, so every entry is in the minecraft
 * namespace and no two paths collide.
 */
public final class SlabPalette {
	private SlabPalette() {}

	private static final String RESOURCE = "/mixed_slabs/slab_palette.json";

	private static final List<String> NAMES;
	private static final List<Block> BLOCKS;
	private static final Map<Block, Integer> INDEX;

	/** Palette indices by what the slab is: dirt surface, thin floor board, or neither. */
	private static final List<Integer> TERRAIN;
	private static final List<Integer> ORDINARY;
	private static final List<Integer> TOPPERS;
	private static final java.util.Set<Integer> DROWNS;
	private static final List<Integer> SIGNALS;
	private static final java.util.Set<Integer> PLAYER_ONLY;
	private static final List<Integer> BOTTOMS;
	private static final List<Integer> ALL;

	static {
		List<String> names = new ArrayList<>();
		java.util.Set<String> terrainIds = new java.util.HashSet<>();
		java.util.Set<String> topperIds = new java.util.HashSet<>();
		java.util.Set<String> drownIds = new java.util.HashSet<>();
		java.util.Set<String> signalIds = new java.util.HashSet<>();
		java.util.Set<String> playerOnlyIds = new java.util.HashSet<>();
		try (InputStream in = SlabPalette.class.getResourceAsStream(RESOURCE)) {
			if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");

			JsonObject json = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			for (var element : json.getAsJsonArray("slabs")) {
				names.add(element.getAsString());
			}
			if (json.has("terrain")) {
				for (var element : json.getAsJsonArray("terrain")) {
					terrainIds.add(element.getAsString());
				}
			}
			if (json.has("toppers")) {
				for (var element : json.getAsJsonArray("toppers")) {
					topperIds.add(element.getAsString());
				}
			}
			if (json.has("signals")) {
				for (var element : json.getAsJsonArray("signals")) {
					signalIds.add(element.getAsString());
				}
			}
			if (json.has("player_only")) {
				for (var element : json.getAsJsonArray("player_only")) {
					playerOnlyIds.add(element.getAsString());
				}
			}
			if (json.has("drowns")) {
				for (var element : json.getAsJsonArray("drowns")) {
					drownIds.add(element.getAsString());
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException("Could not read the slab palette", e);
		}

		List<Block> blocks = new ArrayList<>(names.size());
		List<String> paths = new ArrayList<>(names.size());
		Map<Block, Integer> index = new HashMap<>();

		for (int i = 0; i < names.size(); i++) {
			Identifier id = Identifier.parse(names.get(i));
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			blocks.add(block);
			paths.add(id.getPath());
			index.put(block, i);
		}

		NAMES = List.copyOf(paths);
		BLOCKS = List.copyOf(blocks);
		INDEX = Map.copyOf(index);

		List<Integer> terrain = new ArrayList<>();
		List<Integer> ordinary = new ArrayList<>();
		List<Integer> toppers = new ArrayList<>();
		java.util.Set<Integer> drowns = new java.util.HashSet<>();
		List<Integer> signals = new ArrayList<>();
		java.util.Set<Integer> playerOnly = new java.util.HashSet<>();
		List<Integer> bottoms = new ArrayList<>();
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			all.add(i);
			String id = names.get(i);
			if (signalIds.contains(id)) {
				signals.add(i);
				if (playerOnlyIds.contains(id)) playerOnly.add(i);
				continue;
			}
			if (topperIds.contains(id)) {
				toppers.add(i);
				if (drownIds.contains(id)) drowns.add(i);
				continue;
			}
			bottoms.add(i);
			if (terrainIds.contains(id)) terrain.add(i);
			else ordinary.add(i);
		}

		TERRAIN = List.copyOf(terrain);
		ORDINARY = List.copyOf(ordinary);
		TOPPERS = List.copyOf(toppers);
		DROWNS = java.util.Set.copyOf(drowns);
		SIGNALS = List.copyOf(signals);
		PLAYER_ONLY = java.util.Set.copyOf(playerOnly);
		BOTTOMS = List.copyOf(bottoms);
		ALL = List.copyOf(all);
	}

	/** Indices that may fill a lower half. A floor board may not: nothing would rest on it. */
	public static List<Integer> bottomIndices() { return BOTTOMS; }

	/** Indices that rest on the lower half rather than filling the upper one. */
	public static List<Integer> topperIndices() { return TOPPERS; }

	/** Indices carrying a redstone component: a plate, a switch, a torch. */
	public static List<Integer> signalIndices() { return SIGNALS; }

	/** Whether this signal component notices only players, the way a player detector does. */
	public static boolean noticesPlayersOnly(int index) { return PLAYER_ONLY.contains(index); }

	/** Whether water destroys this topper, so a mixed slab holding one is never waterlogged. */
	public static boolean drowns(int index) { return DROWNS.contains(index); }

	/** Every palette index; what either block's bottom half may hold. */
	public static List<Integer> allIndices() { return ALL; }

	/** Indices whose slab makes a dirt surface - the top halves of the dirt variant. */
	public static List<Integer> terrainIndices() { return TERRAIN; }

	/** Everything else - the top halves of the ordinary variant. */
	public static List<Integer> ordinaryIndices() { return ORDINARY; }

	public static boolean isTerrain(int index) { return TERRAIN.contains(index); }

	/** Property value names, in palette order. */
	public static List<String> names() { return NAMES; }

	public static int size() { return NAMES.size(); }

	public static Block block(int index) { return BLOCKS.get(index); }

	/** The palette index for a block, or -1 if it is not a slab we mix. */
	public static int indexOf(Block block) { return INDEX.getOrDefault(block, -1); }

	public static boolean contains(Block block) { return INDEX.containsKey(block); }
}
