package justfatlard.mixed_slabs;

import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slabs of any two kinds sharing one block.
 *
 * <p>Server-side; Pandorical carries the client's half. There is no item: you never hold a mixed
 * slab, you make one by placing a slab onto another, and breaking it gives back the two slabs it
 * was made of.
 *
 * <p>Three blocks, split by what is in the top half. A tag belongs to a block rather than a state,
 * so a mixed slab with dirt on top has to <em>be</em> a different block for the game to accept a
 * flower on it. They divide the top halves between them and share every bottom half.
 *
 * <p>All three carry {@code waterlogged}, because a fence post can be either half and a post leaves
 * most of its half empty. Which combinations actually take water is decided per state when one is
 * placed, not by which block it is: the property has to exist on all of them for any of them to be
 * able to hold water, and the ones that fill their block simply never get it set.
 */
public class Main implements ModInitializer {

	public static final String MOD_ID = "mixed-slabs-justfatlard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String ORDINARY_NAME = "mixed_slab";
	private static final String DIRT_NAME = "mixed_dirt_slab";
	private static final String TOPPER_NAME = "mixed_topper_slab";
	private static final String SIGNAL_NAME = "mixed_signal_slab";

	/**
	 * Built up rather than copied from a real block, because of one flag.
	 *
	 * <p>Nothing here says {@code requiresCorrectToolForDrops}. That flag is decided for the block
	 * as a whole and the game checks it before the block is ever asked what it drops - so copying
	 * stone's properties would have put every drop behind a pickaxe and quietly skipped the
	 * per-half tool check, costing somebody the oak half for want of the wrong tool. The question is
	 * asked once per half, in {@link MixedSlabBlock#getDrops}, where the answer differs.
	 */
	/**
	 * Light from whichever half is giving it off.
	 *
	 * <p>A torch does exactly one thing and this is it, so a mixed slab holding one has to do it
	 * too or the torch is an ornament. Decided per state, which is the only reason a torch could
	 * be a topper at all: light is a constant property of a block rather than something that
	 * changes, so it survives being absorbed where a lever's or a redstone dust's function
	 * could not.
	 */
	private static int lightOf(BlockState state) {
		return Math.max(halfLight(state, "bottom_slab"), halfLight(state, "top_slab"));
	}

	/**
	 * The light from one half, found by property name rather than through the block.
	 *
	 * <p>Every state caches its light as it is created, which happens inside the block's own
	 * constructor - so at the moment this is called the block's fields have not been assigned and
	 * asking it for its properties gets null back. The state's definition is already complete by
	 * then, so the property is looked up there instead.
	 */
	private static int halfLight(BlockState state, String name) {
		for (var property : state.getProperties()) {
			if (property instanceof SlabHalfProperty half && half.getName().equals(name)) {
				return SlabPalette.block(state.getValue(half)).defaultBlockState().getLightEmission();
			}
		}
		return 0;
	}

	/** Shared by the real block and the client's stand-in, so the two cannot drift apart. */
	private static final float HARDNESS = 2.0F;

	private static BlockBehaviour.Properties properties(String name) {
		return BlockBehaviour.Properties.of()
			.strength(HARDNESS, 6.0F)
			.sound(SoundType.STONE)
			.lightLevel(Main::lightOf)
			.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name)));
	}

	public static final MixedSlabBlock MIXED_SLAB =
		MixedSlabBlock.create(properties(ORDINARY_NAME), SlabPalette.ordinaryIndices(), false, true, false);

	public static final MixedSlabBlock MIXED_DIRT_SLAB =
		MixedSlabBlock.create(properties(DIRT_NAME), SlabPalette.terrainIndices(), false, true, false);

	/**
	 * Something resting on a slab rather than filling the half above it: a floor board, a torch,
	 * a carpet.
	 *
	 * <p>Its own block because a topper is drawn where the lower half ends rather than where the
	 * upper half begins, which is a different model for the same top half and so a different
	 * blockstate. It is the waterloggable one too - a board or a torch fills a sliver of its half
	 * and leaves the rest of the block to hold water.
	 */
	public static final MixedSlabBlock MIXED_TOPPER_SLAB =
		MixedSlabBlock.create(properties(TOPPER_NAME), SlabPalette.topperIndices(), true, true, false);

	/**
	 * A redstone component riding on a slab.
	 *
	 * <p>Its own block because it carries a bit none of the others need - whether the component is
	 * on - and carries no {@code waterlogged}, because nothing here survives being submerged.
	 * Signals are read straight off the real component rather than reimplemented; see
	 * {@link MixedSignal}.
	 */
	public static final MixedSlabBlock MIXED_SIGNAL_SLAB =
		MixedSlabBlock.create(properties(SIGNAL_NAME), SlabPalette.signalIndices(), true, false, true);

	/**
	 * The state for a pair of palette indices, from whichever of the three blocks holds it.
	 *
	 * <p>The single place that knows the split, so nothing else has to remember which block a
	 * combination lives in - including a caller moving a surface from one to the other.
	 */
	public static BlockState stateFor(int bottom, int top) {
		if (bottom < 0 || top < 0) return null;
		// A topper is never a lower half; nothing would be resting on it.
		if (SlabPalette.topperIndices().contains(bottom)) return null;
		if (SlabPalette.signalIndices().contains(bottom)) return null;

		MixedSlabBlock block = SlabPalette.signalIndices().contains(top) ? MIXED_SIGNAL_SLAB
			: SlabPalette.topperIndices().contains(top) ? MIXED_TOPPER_SLAB
			: SlabPalette.isTerrain(top) ? MIXED_DIRT_SLAB
			: MIXED_SLAB;

		return block.defaultBlockState()
			.setValue(block.bottomProperty(), bottom)
			.setValue(block.topProperty(), top);
	}

	@Override
	public void onInitialize() {
		register(ORDINARY_NAME, MIXED_SLAB);
		register(DIRT_NAME, MIXED_DIRT_SLAB);
		register(TOPPER_NAME, MIXED_TOPPER_SLAB);
		register(SIGNAL_NAME, MIXED_SIGNAL_SLAB);

		PandoricalApi.content().registerModAssets(MOD_ID);

		// Grass is the only mixable slab whose model carries a tint index, and no vanilla slab
		// carries one at all - so colouring these blocks' tinted quads with the biome grass colour
		// paints exactly the grass halves and leaves every other combination alone. Registered
		// unconditionally: with no grass half there is nothing tinted to colour.
		PandoricalApi.blockTints().grass(MOD_ID + ":" + ORDINARY_NAME, MOD_ID + ":" + DIRT_NAME,
			MOD_ID + ":" + TOPPER_NAME, MOD_ID + ":" + SIGNAL_NAME);

		UseBlockCallback.EVENT.register(MixedSlabPlacement::onUseBlock);

		// One block id covers every pairing in the palette, so the card that names a block cannot
		// say which pairing this one is. Worth a line when block-tip is there to carry it.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("block-tip")) {
			justfatlard.mixed_slabs.integration.SlabTips.register();
		}

		int bottoms = SlabPalette.bottomIndices().size();
		int tops = SlabPalette.ordinaryIndices().size() + SlabPalette.terrainIndices().size()
			+ SlabPalette.topperIndices().size() + SlabPalette.signalIndices().size();

		// Every block carries a second bit - waterlogged on three of them, powered on the fourth -
		// so the state count is twice the combinations across the board.
		LOGGER.info("Mixed Slabs loaded - {} slabs ({} dirt) + {} toppers + {} redstone, "
				+ "{} combinations, {} states",
			bottoms, SlabPalette.terrainIndices().size(), SlabPalette.topperIndices().size(),
			SlabPalette.signalIndices().size(), bottoms * tops, bottoms * tops * 2);
	}

	private static void register(String name, MixedSlabBlock block) {
		Registry.register(BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name), block);

		// Stone as the stand-in: the client half needs a full solid cube with the same two state
		// properties, and every visible difference comes from the models the blockstate picks.
		// Stone for its sound and feel, but not for how it breaks. Both of stone's mining
		// properties are wrong here: it is softer than these are, and it wants a pickaxe where
		// these deliberately want nothing (see properties(), which leaves the flag off so each
		// half can be asked about its own tool). Breaking is predicted on the client, so leaving
		// the stand-in to answer meant the dig took a different length of time than the server
		// thought - and no pickaxe made it five times longer again.
		PandoricalApi.content().registerBlock(MOD_ID + ":" + name,
			new BlockRegistration()
				.baseBlock("minecraft:stone")
				.strength(HARDNESS)
				.requiresCorrectTool(false)
				.property("bottom_slab")
				.property("top_slab"));
	}
}
