package justfatlard.mixed_slabs.integration;

import justfatlard.block_tip.api.BlockTipApi;
import justfatlard.mixed_slabs.MixedSlabsApi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Saying what a mixed slab is actually made of.
 *
 * <p>Every one of these blocks is called "Mixed Slab", because one block id covers every pairing
 * the palette allows - and the card that names a block is therefore the one card in the game that
 * cannot answer the only question anyone asks of this block. Two of them side by side, one oak over
 * stone and one stone over oak, are told apart by looking at the floor rather than by reading.
 *
 * <p>So the tip names both halves, in the order you are looking at them. It has to be worked out
 * from the block in front of the player rather than registered once against the id, because the
 * pairing lives in the block state and not in which block it is.
 *
 * <p>Compiled against block-tip's API and guarded at the call site by a mod-loaded check, so a
 * server without it never loads this class.
 */
public final class SlabTips {
	private SlabTips() {}

	public static void register() {
		BlockTipApi.describe((level, pos, state, player) -> {
			if (!MixedSlabsApi.isMixedSlab(state)) return null;

			Block top = MixedSlabsApi.topHalf(state);
			Block bottom = MixedSlabsApi.bottomHalf(state);
			if (top == null || bottom == null) return null;

			// Named as the items they came from: a player recognises "Oak Planks", and the block's
			// own name is the same string anyway for everything in the palette.
			return nameOf(top) + " over " + nameOf(bottom);
		});
	}

	private static String nameOf(Block block) {
		return new ItemStack(block).getHoverName().getString();
	}
}
