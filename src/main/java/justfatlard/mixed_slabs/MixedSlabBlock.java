package justfatlard.mixed_slabs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * One block holding two different slabs.
 *
 * <p>Vanilla stacks two slabs of the same kind into a double slab and has nowhere to put a second
 * material, because a position holds one block state. So this is a block of its own whose state
 * says which slab is in each half, and whose model is assembled from the two vanilla half-models
 * that were going to be drawn anyway. Vanilla does the drawing, so the lighting, ambient occlusion,
 * face culling and break particles are the real ones rather than an imitation.
 *
 * <p>There are three instances of this class, split by what is in the <em>top</em> half: dirt, a
 * thin floor board, or anything else. That is not a stylistic division. A tag belongs to a block and
 * not to a state, so "this surface is dirt and plants may be placed on it" cannot be said about some
 * states of one block; and neither can a property, so only the topper variant carries
 * {@code waterlogged}, the others having no room left in the block to hold any. Splitting on the
 * surface says each of those things about all the states of one block. They share every possible
 * bottom half and divide the top halves between them.
 *
 * <p>Everything the block does defers to whichever half is being asked about. The governing idea is
 * that <b>a mixed slab is a full-height block whose surface material is its top half</b>: questions
 * about material are answered from a half, and it is never treated as a half slab - a plant standing
 * on one stands at full height. Its shape is whatever the two halves add up to, which is a full cube
 * for two ordinary slabs and less than one when a half is something narrower, like a fence post.
 */
public class MixedSlabBlock extends Block implements net.minecraft.world.level.block.SimpleWaterloggedBlock {

	/**
	 * The property pair the constructor is about to adopt.
	 *
	 * <p>{@code createBlockStateDefinition} runs inside {@code super(...)}, before any field of this
	 * class has been assigned, so the properties cannot be reached through the instance yet. Passing
	 * them alongside is the same trick Pandorical's own dynamic slab uses for the same reason.
	 */
	private static final ThreadLocal<SlabHalfProperty[]> PENDING = new ThreadLocal<>();

	public static final net.minecraft.world.level.block.state.properties.BooleanProperty WATERLOGGED =
		net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

	private static final ThreadLocal<Boolean> PENDING_WATERLOGGED = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> PENDING_SIGNAL = new ThreadLocal<>();

	/** The one bit a carried redstone component gets to keep. */
	public static final net.minecraft.world.level.block.state.properties.BooleanProperty POWERED =
		net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;

	private final SlabHalfProperty bottom;
	private final SlabHalfProperty top;

	/**
	 * Whether this pair leaves anywhere for water to sit.
	 *
	 * <p>True unless the two halves fill the block between them. A fence post or a floor board fills
	 * its half only in the sense of occupying it - most of the space is still there - and that space
	 * should hold water like any other gap. Two ordinary slabs leave nothing, and a cube full of
	 * water it cannot show is worth refusing rather than storing.
	 */
	public boolean leavesRoomForWater(BlockState state) {
		return !Block.isShapeFullBlock(shapeOf(state));
	}

	/**
	 * Whether the upper half rests on the lower rather than sitting at the ceiling.
	 *
	 * <p>True for the topper variant. A board is two pixels, not eight, so drawing it where a top
	 * half goes would leave it hanging with nothing underneath; it comes down onto whatever fills
	 * the lower half instead, and the gap it would have left below ends up above it.
	 */
	private final boolean snapTop;

	private final boolean waterloggable;

	/** Whether the top half is a redstone component rather than a material. */
	private final boolean signal;

	public static MixedSlabBlock create(Properties properties, List<Integer> topHalves,
			boolean snapTop, boolean waterloggable, boolean signal) {
		SlabHalfProperty[] pair = {
			SlabHalfProperty.create("bottom_slab", SlabPalette.bottomIndices()),
			SlabHalfProperty.create("top_slab", topHalves),
		};

		PENDING.set(pair);
		PENDING_WATERLOGGED.set(waterloggable);
		PENDING_SIGNAL.set(signal);
		try {
			return new MixedSlabBlock(properties, pair, snapTop, waterloggable, signal);
		} finally {
			PENDING.remove();
			PENDING_WATERLOGGED.remove();
			PENDING_SIGNAL.remove();
		}
	}

	private MixedSlabBlock(Properties properties, SlabHalfProperty[] pair, boolean snapTop,
			boolean waterloggable, boolean signal) {
		super(properties);
		this.bottom = pair[0];
		this.top = pair[1];
		this.snapTop = snapTop;
		this.waterloggable = waterloggable;
		this.signal = signal;

		BlockState base = getStateDefinition().any()
			.setValue(pair[0], pair[0].getPossibleValues().getFirst())
			.setValue(pair[1], pair[1].getPossibleValues().getFirst());
		if (waterloggable) base = base.setValue(WATERLOGGED, false);
		if (signal) base = base.setValue(POWERED, false);
		registerDefaultState(base);
	}

	public boolean isWaterloggable() { return waterloggable; }

	public SlabHalfProperty bottomProperty() { return bottom; }

	public SlabHalfProperty topProperty() { return top; }

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		SlabHalfProperty[] pair = PENDING.get();
		if (pair == null) return;

		builder.add(pair[0], pair[1]);
		if (Boolean.TRUE.equals(PENDING_WATERLOGGED.get())) builder.add(WATERLOGGED);
		if (Boolean.TRUE.equals(PENDING_SIGNAL.get())) builder.add(POWERED);
	}


	// --- shape ---
	//
	// Two ordinary slabs fill a cube between them, and for a long time that was the only case, so
	// the block simply was one. A fence post slab is the case that breaks it: it fills a half of
	// the block the way a slab does but occupies a narrow post inside that half, and a full cube of
	// collision around it would have people standing on air beside the post. So the shape is the
	// union of whatever the two halves actually are, and a mixed slab is only a full cube when both
	// its halves are.

	/** Each palette entry's shape as a lower half and as an upper half, filled on first use. */
	private static final VoxelShape[] LOWER = new VoxelShape[SlabPalette.size()];
	private static final VoxelShape[] UPPER = new VoxelShape[SlabPalette.size()];

	/**
	 * Unions, kept because a shape is asked for far more often than a new pair turns up.
	 *
	 * <p>Grown on demand rather than precomputed: there are more than twenty thousand pairs and a
	 * world uses a handful of them, so building the table up front would be work done for
	 * combinations nobody will ever place.
	 */
	private static final Map<Integer, VoxelShape> UNIONS = new ConcurrentHashMap<>();

	private static VoxelShape halfShape(int index, SlabType half) {
		VoxelShape[] cache = half == SlabType.BOTTOM ? LOWER : UPPER;
		VoxelShape cached = cache[index];
		if (cached != null) return cached;

		BlockState state = SlabPalette.block(index).defaultBlockState();
		// Vanilla slabs and fence post slabs share one property instance, so both answer this.
		if (state.hasProperty(SlabBlock.TYPE)) state = state.setValue(SlabBlock.TYPE, half);

		// Every slab shape in the palette is a constant box; none of them consults the world, so
		// there is nothing to hand them and no position to be wrong about.
		VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		cache[index] = shape;
		return shape;
	}

	private VoxelShape shapeOf(BlockState state) {
		int lower = state.getValue(bottom);
		int upper = state.getValue(top);

		// Snapped tops get their own cache line: the same pair means a different shape depending
		// on whether the upper half rests on the lower or sits at the ceiling.
		int key = (lower << 16) | upper | (snapTop ? 1 << 30 : 0);
		return UNIONS.computeIfAbsent(key,
			k -> Shapes.or(halfShape(lower, SlabType.BOTTOM), upperShape(upper)));
	}

	/**
	 * The upper half's shape where it is actually drawn.
	 *
	 * <p>A snapped board is its own lower-half shape lifted half a block, because every slab-shaped
	 * lower half reaches the same halfway line - that is the surface it is resting on.
	 */
	private VoxelShape upperShape(int index) {
		if (!snapTop) return halfShape(index, SlabType.TOP);

		return halfShape(index, SlabType.BOTTOM).move(0.0, 0.5, 0.0);
	}

	@Override
	public net.minecraft.world.level.material.FluidState getFluidState(BlockState state) {
		if (waterloggable && state.getValue(WATERLOGGED)) {
			return net.minecraft.world.level.material.Fluids.WATER.getSource(false);
		}
		return super.getFluidState(state);
	}

	@Override
	public BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level,
			net.minecraft.world.level.ScheduledTickAccess tickView, BlockPos pos,
			net.minecraft.core.Direction direction, BlockPos neighbourPos, BlockState neighbourState,
			net.minecraft.util.RandomSource random) {
		if (waterloggable && state.getValue(WATERLOGGED)) {
			tickView.scheduleTick(pos, net.minecraft.world.level.material.Fluids.WATER,
				net.minecraft.world.level.material.Fluids.WATER.getTickDelay(level));
		}
		return super.updateShape(state, level, tickView, pos, direction, neighbourPos, neighbourState, random);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeOf(state);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeOf(state);
	}

	/**
	 * What this block hides behind it, so neighbours cull their faces against the real shape.
	 *
	 * <p>Without this the block would keep claiming to be a solid cube, and a neighbour beside a
	 * post half would drop the face nobody is covering - a hole you can see through.
	 */
	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return shapeOf(state);
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	// --- redstone, for the variant carrying a component ---

	@Override
	protected boolean isSignalSource(BlockState state) {
		return signal && MixedSignal.isSource(state);
	}

	@Override
	protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level,
			BlockPos pos, net.minecraft.core.Direction direction) {
		return signal ? MixedSignal.signal(state, level, pos, direction) : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, net.minecraft.world.level.BlockGetter level,
			BlockPos pos, net.minecraft.core.Direction direction) {
		return signal ? MixedSignal.directSignal(state, level, pos, direction) : 0;
	}

	@Override
	protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,
			net.minecraft.world.level.Level level, BlockPos pos, Player player,
			net.minecraft.world.phys.BlockHitResult hit) {
		if (!signal) return net.minecraft.world.InteractionResult.PASS;
		if (MixedSignal.flip(state, level, pos, player) || MixedSignal.snuff(state, level, pos, player)) {
			return net.minecraft.world.InteractionResult.SUCCESS;
		}
		return net.minecraft.world.InteractionResult.PASS;
	}

	/**
	 * Lighting a candle: vanilla's flint and steel only lights blocks in the candle tag, and this
	 * block is not in it, so the block has to accept the flame itself.
	 */
	@Override
	protected net.minecraft.world.InteractionResult useItemOn(ItemStack held, BlockState state,
			net.minecraft.world.level.Level level, BlockPos pos, Player player,
			net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		if (!signal || !MixedSignal.light(state, level, pos, player, held, hand)) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	@Override
	protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos,
			net.minecraft.util.RandomSource random) {
		if (signal) MixedSignal.tick(state, level, pos);
	}

	@Override
	protected void entityInside(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
			net.minecraft.world.entity.Entity entity,
			net.minecraft.world.entity.InsideBlockEffectApplier applier, boolean flag) {
		if (signal && level instanceof net.minecraft.server.level.ServerLevel server) {
			MixedSignal.pressureChanged(state, server, pos);
		}
	}

	/**
	 * A torch inverts what it is standing on, so it has to hear about the neighbours changing.
	 *
	 * <p>Scheduled rather than answered on the spot, the way vanilla does it: settling a torch
	 * immediately is how you build a circuit that oscillates within a single tick.
	 */
	@Override
	protected void neighborChanged(BlockState state, net.minecraft.world.level.Level level,
			BlockPos pos, Block source, net.minecraft.world.level.redstone.Orientation orientation,
			boolean moving) {
		if (signal && level instanceof net.minecraft.server.level.ServerLevel server) {
			server.scheduleTick(pos, this, 2);
		}
	}

	/** The block sitting in one half, as its own default state. */
	private static BlockState halfState(BlockState mixed, SlabHalfProperty which) {
		return SlabPalette.block(mixed.getValue(which)).defaultBlockState();
	}

	/**
	 * As hard as the harder half.
	 *
	 * <p>A single hardness is fixed at construction for every state, so the number has to be worked
	 * out per state here instead. Taking the harder of the two is the answer that cannot be gamed:
	 * capping a stone slab's hardness by pairing it with oak would make this block a way of mining
	 * things cheaply rather than a way of decorating with them.
	 */
	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		// A torch or a lever on top comes off at its own speed when it is what you are hitting: a
		// punch, as it would anywhere else. See popTopper.
		if (hasTopper(state) && aimsAtTop(player, pos)) {
			return halfState(state, top).getDestroyProgress(player, level, pos);
		}
		return Math.min(
			halfState(state, bottom).getDestroyProgress(player, level, pos),
			halfState(state, top).getDestroyProgress(player, level, pos));
	}

	/** Footsteps and breaking sound come from the half you are standing on. */
	@Override
	protected SoundType getSoundType(BlockState state) {
		return halfState(state, top).getSoundType();
	}

	/**
	 * Both slabs back, each only if the tool could have got it on its own, and each as its own loot
	 * table would give it.
	 *
	 * <p>Judged per half rather than for the block as a whole. Mixing a stone slab into an oak one
	 * must not become a way of collecting stone by hand, and it must not cost you the oak for want
	 * of a pickaxe either - so each half is asked the question vanilla would have asked it. The
	 * loot table is the rest of that question: a grass slab gives a dirt slab back unless the tool
	 * has silk touch, and handing over the grass slab itself made a mixed slab the way round it.
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		ItemStack tool = params.getOptionalParameter(LootContextParams.TOOL) instanceof ItemStack held
			? held
			: ItemStack.EMPTY;

		List<ItemStack> drops = new ArrayList<>(2);
		addHalf(drops, halfState(state, bottom), tool, params);
		addHalf(drops, halfState(state, top), tool, params);
		return drops;
	}

	private static void addHalf(List<ItemStack> drops, BlockState half, ItemStack tool, LootParams.Builder params) {
		if (half.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(half)) return;

		// The same parameters, with this half as the block being broken.
		drops.addAll(half.getDrops(params));
	}

	/** Whether the top half is something standing on the slab - a torch, a lever - rather than a slab. */
	public boolean hasTopper(BlockState state) {
		if (!state.is(this)) return false;
		int index = state.getValue(top);
		return SlabPalette.topperIndices().contains(index) || SlabPalette.signalIndices().contains(index);
	}

	/** Whether the player is looking at the top half of this block. */
	public static boolean aimsAtTop(Player player, BlockPos pos) {
		return player.pick(player.blockInteractionRange(), 1.0F, false) instanceof net.minecraft.world.phys.BlockHitResult hit
			&& hit.getBlockPos().equals(pos)
			&& hit.getLocation().y - pos.getY() > 0.5;
	}

	/**
	 * Take the torch, lever or whatever is on top off, and leave the slab it stood on.
	 *
	 * <p>A mixed slab breaks as one block, as hard as its harder half, which is right for two slabs
	 * and wrong for a torch on one: knocking a torch off took as long as mining the slab, and took
	 * the slab with it. What is on top drops as it would anywhere, by its own loot table and the
	 * tool in hand, and the slab stands as a plain slab again.
	 */
	public void popTopper(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state, Player player) {
		BlockState topper = halfState(state, top);
		BlockState slab = halfState(state, bottom);
		if (slab.hasProperty(SlabBlock.WATERLOGGED) && state.hasProperty(SlabBlock.WATERLOGGED)) {
			slab = slab.setValue(SlabBlock.WATERLOGGED, state.getValue(SlabBlock.WATERLOGGED));
		}
		if (!player.isCreative()) {
			for (ItemStack drop : Block.getDrops(topper, level, pos, null, player, player.getMainHandItem())) {
				Block.popResource(level, pos, drop);
			}
		}
		level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(topper));
		level.setBlock(pos, slab, Block.UPDATE_ALL);
		level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.BLOCK_DESTROY, pos);
	}
}
