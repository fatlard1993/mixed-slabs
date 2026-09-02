package justfatlard.mixed_slabs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Making a mixed slab by placing one slab onto another.
 *
 * <p>The gesture is the one that already exists: a half slab with the empty half facing you takes
 * the slab in your hand. Vanilla does this only when both are the same block and calls the result a
 * double slab; when they differ it gives up and puts the new slab in the next space along. This
 * catches that second case.
 *
 * <p>Which half the new slab lands in is decided exactly the way vanilla decides it, from the face
 * clicked and how high up it was clicked, so the two behave identically and the answer never
 * depends on which of them happened to handle the click.
 */
public final class MixedSlabPlacement {
	private MixedSlabPlacement() {}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
			BlockHitResult hit) {
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

		// Sneaking is how you tell the game to ignore a block and use what is in your hand. A slab
		// against a slab is no exception.
		if (player.isSecondaryUseActive()) return InteractionResult.PASS;

		BlockPos pos = hit.getBlockPos();
		BlockState existing = level.getBlockState(pos);
		// Tested by the property rather than by the class: a fence post slab is not a SlabBlock but
		// fills a half of the block exactly as one does, and says so with the very same property
		// instance. Asking what it is made of instead of what it extends is what lets it join in.
		if (!existing.hasProperty(SlabBlock.TYPE)) return InteractionResult.PASS;
		if (!SlabPalette.contains(existing.getBlock())) return InteractionResult.PASS;

		SlabType type = existing.getValue(SlabBlock.TYPE);
		if (type == SlabType.DOUBLE) return InteractionResult.PASS;

		ItemStack held = player.getItemInHand(hand);
		if (!(held.getItem() instanceof BlockItem blockItem)) return InteractionResult.PASS;

		Block placing = blockItem.getBlock();
		if (!SlabPalette.contains(placing)) return InteractionResult.PASS;

		// Two of the same is vanilla's own double slab. Leaving it alone keeps one block for one
		// material rather than two ways of spelling it, and keeps the ten-thousand-state block out
		// of worlds that never needed it.
		if (placing == existing.getBlock()) return InteractionResult.PASS;

		if (!fillsEmptyHalf(type, pos, hit)) return InteractionResult.PASS;

		Block bottom = type == SlabType.BOTTOM ? existing.getBlock() : placing;
		Block top = type == SlabType.BOTTOM ? placing : existing.getBlock();

		BlockState mixed = Main.stateFor(SlabPalette.indexOf(bottom), SlabPalette.indexOf(top));
		if (mixed == null) return InteractionResult.PASS;

		// Water the pair leaves room for comes back around it - around a fence post, above a board.
		// Asked of the state rather than of the block, because whether there is room at all depends
		// on the two halves and not on which of the three blocks happens to hold them.
		// Not for a torch: water destroys one, so a waterlogged torch is a state that should never
		// exist rather than one nobody happens to make.
		boolean drowns = SlabPalette.drowns(SlabPalette.indexOf(top));

		if (!drowns && mixed.getBlock() instanceof MixedSlabBlock block
				&& block.leavesRoomForWater(mixed)) {
			boolean inWater = level.getFluidState(pos).getType() == net.minecraft.world.level.material.Fluids.WATER
				|| existing.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;
			mixed = mixed.setValue(MixedSlabBlock.WATERLOGGED, inWater);
		}

		serverLevel.setBlockAndUpdate(pos, mixed);

		var sound = mixed.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
			(sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

		if (!player.isCreative()) held.shrink(1);

		return InteractionResult.SUCCESS;
	}

	/**
	 * Whether this click means "into the empty half", by vanilla's own rule.
	 *
	 * <p>Mirrors {@code SlabBlock.canBeReplaced}: the top face always fills the top of a bottom
	 * slab, the bottom face always fills the bottom of a top slab, and a side click goes by whether
	 * it landed above or below the halfway line.
	 */
	private static boolean fillsEmptyHalf(SlabType type, BlockPos pos, BlockHitResult hit) {
		boolean aboveMiddle = hit.getLocation().y - pos.getY() > 0.5;
		Direction face = hit.getDirection();

		if (type == SlabType.BOTTOM) {
			return face == Direction.UP || (aboveMiddle && face.getAxis().isHorizontal());
		}
		return face == Direction.DOWN || (!aboveMiddle && face.getAxis().isHorizontal());
	}
}
