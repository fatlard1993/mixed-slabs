package justfatlard.mixed_slabs;

import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a mixed slab is made of, and how to change it, for other mods to ask.
 *
 * <p>Deliberately a small static surface over plain vanilla types, because the mods that need it
 * reach it by reflection rather than by compiling against this one - the suite's usual way of
 * making an optional neighbour optional. Nothing here mentions a type another mod would have to
 * have on its classpath.
 *
 * <p>The reason anyone needs it: a mixed slab is its own block, so every "what block is this?"
 * anywhere in the game sees a mixed slab and not the two slabs inside it. A mod that cared about
 * one of those slabs stops caring the moment it goes into one. This is how it finds out.
 *
 * <p>The rule callers should hold to is that <b>a mixed slab is a full-height block whose surface
 * material is its top half</b>. Ask it about material and it will answer; treating it as a half
 * slab - offsetting something onto it, reading a slab type off it - is the mistake to avoid,
 * because geometrically it is a full cube.
 */
public final class MixedSlabsApi {
	private MixedSlabsApi() {}

	/** Whether this state is a mixed slab at all. Safe for any state. */
	public static boolean isMixedSlab(BlockState state) {
		return state.getBlock() instanceof MixedSlabBlock;
	}

	/**
	 * The two slabs in this state, bottom first, or an empty list if it is not a mixed slab.
	 *
	 * <p>Safe to call with any state, so a caller can use it as its own test.
	 */
	public static List<Block> halves(BlockState state) {
		if (!(state.getBlock() instanceof MixedSlabBlock block)) return List.of();

		return List.of(
			SlabPalette.block(state.getValue(block.bottomProperty())),
			SlabPalette.block(state.getValue(block.topProperty())));
	}

	/** The slab filling the upper half - the surface walked on and built against - or null. */
	public static Block topHalf(BlockState state) {
		if (!(state.getBlock() instanceof MixedSlabBlock block)) return null;

		return SlabPalette.block(state.getValue(block.topProperty()));
	}

	/** The slab filling the lower half, or null. */
	public static Block bottomHalf(BlockState state) {
		if (!(state.getBlock() instanceof MixedSlabBlock block)) return null;

		return SlabPalette.block(state.getValue(block.bottomProperty()));
	}

	/**
	 * The same mixed slab with a different surface - grass creeping onto it, a shovel turning it to
	 * a path - or null if the change cannot be made.
	 *
	 * <p>May hand back a state of the <em>other</em> mixed slab block, because which block holds a
	 * combination depends on whether its top half is dirt. Callers should place what comes back and
	 * not assume it is the same block they started with.
	 */
	public static BlockState withTopHalf(BlockState state, Block surface) {
		if (!(state.getBlock() instanceof MixedSlabBlock block)) return null;

		int replacement = SlabPalette.indexOf(surface);
		if (replacement < 0) return null;

		int bottom = state.getValue(block.bottomProperty());
		return Main.stateFor(bottom, replacement);
	}
}
