package justfatlard.mixed_slabs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/**
 * Redstone components riding on a slab.
 *
 * <p>The components are not reimplemented. Everything a signal is <em>read</em> through - whether
 * this block is a source, what it puts out in a direction, how strongly - is answered by building
 * the real component's own state and asking it, exactly as hardness and sound already are. That is
 * where fidelity comes from: vanilla's own answer, not an approximation of it that is right until
 * somebody builds something clever.
 *
 * <p>Only the <em>writing</em> is ours: when the bit flips. That is small and different per
 * component - a plate reads what is stood on it, a lever is flipped, a button springs back, a torch
 * inverts what it is sitting on - and each is a few lines rather than a system.
 *
 * <p>One bit is all a mixed slab carries, which is the whole reason these four are here and a
 * repeater, a comparator, a weighted plate and redstone dust are not: their behaviour lives in
 * state there is no room for.
 */
public final class MixedSignal {
	private MixedSignal() {}

	/** How long a button stays pressed, by the wood it is made of. Vanilla's own numbers. */
	private static final int WOODEN_BUTTON_TICKS = 30;
	private static final int STONE_BUTTON_TICKS = 20;

	/** The component this mixed slab is carrying, in the state it is actually in. */
	public static BlockState componentOf(BlockState mixed) {
		if (!(mixed.getBlock() instanceof MixedSlabBlock block)) return null;
		if (!mixed.hasProperty(MixedSlabBlock.POWERED)) return null;

		BlockState component = SlabPalette.block(mixed.getValue(block.topProperty()))
			.defaultBlockState();
		boolean on = mixed.getValue(MixedSlabBlock.POWERED);

		// The one bit, spelled however this component spells it.
		if (component.hasProperty(BlockStateProperties.POWERED)) {
			component = component.setValue(BlockStateProperties.POWERED, on);
		}
		if (component.hasProperty(BlockStateProperties.LIT)) {
			component = component.setValue(BlockStateProperties.LIT, on);
		}
		// A lever or button on a slab is standing on it, whatever it was told when placed.
		if (component.hasProperty(BlockStateProperties.ATTACH_FACE)) {
			component = component.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
		}
		return component;
	}

	public static boolean isSource(BlockState mixed) {
		BlockState component = componentOf(mixed);
		return component != null && component.isSignalSource();
	}

	public static int signal(BlockState mixed, BlockGetter level, BlockPos pos, Direction direction) {
		BlockState component = componentOf(mixed);
		return component == null ? 0 : component.getSignal(level, pos, direction);
	}

	public static int directSignal(BlockState mixed, BlockGetter level, BlockPos pos, Direction direction) {
		BlockState component = componentOf(mixed);
		return component == null ? 0 : component.getDirectSignal(level, pos, direction);
	}

	/** Whether the carried component is one you flip or press. */
	public static boolean isSwitch(BlockState mixed) {
		BlockState component = componentOf(mixed);
		if (component == null) return false;

		return component.getBlock() instanceof LeverBlock || component.getBlock() instanceof ButtonBlock;
	}

	/**
	 * A right-click on a mixed slab carrying a switch.
	 *
	 * <p>A lever holds its new position; a button springs back on its own, so it schedules the tick
	 * that releases it. Both announce the change to their neighbours, which is what makes anything
	 * downstream notice.
	 */
	public static boolean flip(BlockState mixed, Level level, BlockPos pos, Player player) {
		BlockState component = componentOf(mixed);
		if (component == null) return false;

		Block carried = component.getBlock();
		boolean lever = carried instanceof LeverBlock;
		if (!lever && !(carried instanceof ButtonBlock)) return false;

		boolean on = mixed.getValue(MixedSlabBlock.POWERED);
		if (!lever && on) return true;   // a pressed button ignores being pressed again

		boolean now = lever != on;       // a lever toggles; a button only ever goes on
		level.setBlock(pos, mixed.setValue(MixedSlabBlock.POWERED, now), Block.UPDATE_ALL);
		announce(level, pos);

		level.playSound(player, pos,
			lever ? net.minecraft.sounds.SoundEvents.LEVER_CLICK
				: net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_ON,
			net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, now ? 0.6F : 0.5F);

		if (!lever && level instanceof ServerLevel server) {
			server.scheduleTick(pos, mixed.getBlock(), pressDuration(carried));
		}
		return true;
	}

	private static int pressDuration(Block button) {
		return button.defaultBlockState().is(net.minecraft.tags.BlockTags.WOODEN_BUTTONS)
			? WOODEN_BUTTON_TICKS : STONE_BUTTON_TICKS;
	}

	/**
	 * The scheduled tick: a button letting go, or a torch deciding whether to burn.
	 *
	 * <p>A redstone torch turns off when what it is attached to carries a signal. Here that is the
	 * mixed slab itself - the torch is standing on its lower half - so it reads the block's own
	 * neighbours, which is the same rule vanilla applies, asked of a different block.
	 */
	public static void tick(BlockState mixed, ServerLevel level, BlockPos pos) {
		BlockState component = componentOf(mixed);
		if (component == null) return;

		boolean on = mixed.getValue(MixedSlabBlock.POWERED);

		if (component.getBlock() instanceof RedstoneTorchBlock) {
			boolean shouldBurn = !level.hasNeighborSignal(pos);
			if (shouldBurn != on) {
				level.setBlock(pos, mixed.setValue(MixedSlabBlock.POWERED, shouldBurn), Block.UPDATE_ALL);
				announce(level, pos);
			}
			return;
		}

		if (on && component.getBlock() instanceof ButtonBlock) {
			level.setBlock(pos, mixed.setValue(MixedSlabBlock.POWERED, false), Block.UPDATE_ALL);
			announce(level, pos);
			level.playSound(null, pos, net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_OFF,
				net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, 0.5F);
		}
	}

	/**
	 * Something stood on a plate, or stepped off one.
	 *
	 * <p>What counts as "something" is the component's own business: a vanilla plate answers to
	 * anything with feet, and a player detector is built not to. Carrying one into a mixed slab
	 * must not quietly turn it into the other.
	 */
	public static void pressureChanged(BlockState mixed, ServerLevel level, BlockPos pos) {
		BlockState component = componentOf(mixed);
		if (component == null) return;

		boolean plate = component.getBlock() instanceof BasePressurePlateBlock;
		boolean playersOnly = mixed.getBlock() instanceof MixedSlabBlock block
			&& SlabPalette.noticesPlayersOnly(mixed.getValue(block.topProperty()));
		if (!plate && !playersOnly) return;

		// The plate is the top half, so what counts as standing on it is the space just above.
		AABB above = new AABB(pos.getX(), pos.getY() + 0.5, pos.getZ(),
			pos.getX() + 1, pos.getY() + 1.25, pos.getZ() + 1);
		boolean pressed = !level.getEntities((Entity) null, above, entity ->
			!entity.isIgnoringBlockTriggers()
				&& (!playersOnly || entity instanceof Player)).isEmpty();

		if (pressed != mixed.getValue(MixedSlabBlock.POWERED)) {
			level.setBlock(pos, mixed.setValue(MixedSlabBlock.POWERED, pressed), Block.UPDATE_ALL);
			announce(level, pos);
		}
		if (pressed) level.scheduleTick(pos, mixed.getBlock(), 10);
	}

	/** Tell the neighbours, and the block below, that something changed. */
	private static void announce(Level level, BlockPos pos) {
		level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
		level.updateNeighborsAt(pos.below(), level.getBlockState(pos).getBlock());
	}
}
