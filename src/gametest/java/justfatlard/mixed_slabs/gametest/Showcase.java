package justfatlard.mixed_slabs.gametest;

import justfatlard.mixed_slabs.Main;
import justfatlard.mixed_slabs.SlabPalette;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The picture for the readme and the mod page: pairs of different slabs sharing one block, which
 * is the whole mod and impossible to show in words.
 *
 * <p>The states go in through {@code Main.stateFor}, the same call the placement uses once it has
 * worked out which half a click meant. Run it under xvfb-run; the frames land in
 * build/run/clientGameTest/screenshots.
 */
public final class Showcase implements FabricClientGameTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	/** Pairs worth looking at: each is two slabs nobody could put in one block before. */
	private static final Block[][] PAIRS = {
		{Blocks.OAK_SLAB, Blocks.STONE_SLAB},
		{Blocks.DEEPSLATE_BRICK_SLAB, Blocks.QUARTZ_SLAB},
		{Blocks.SPRUCE_SLAB, Blocks.PRISMARINE_SLAB},
		{Blocks.SANDSTONE_SLAB, Blocks.DARK_OAK_SLAB},
		{Blocks.PURPUR_SLAB, Blocks.WARPED_SLAB},
		{Blocks.MUD_BRICK_SLAB, Blocks.BAMBOO_SLAB},
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			context.getInput().pressKey(options -> options.keyToggleGui);
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();

			server.runCommand("fill %d %d %d %d %d %d minecraft:smooth_stone"
				.formatted(x - 8, y - 1, z - 10, x + 8, y - 1, z + 2));

			// Each pair on its own pedestal, at eye height, in a line.
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				for (int i = 0; i < PAIRS.length; i++) {
					BlockPos at = new BlockPos(x - (PAIRS.length - 1) + i * 2, y + 1, z - 6);
					for (int below = 0; below <= 1; below++) {
						level.setBlockAndUpdate(at.below(below + 1), Blocks.SMOOTH_STONE.defaultBlockState());
					}
					BlockState mixed = Main.stateFor(SlabPalette.indexOf(PAIRS[i][0]),
						SlabPalette.indexOf(PAIRS[i][1]));
					if (mixed != null) level.setBlockAndUpdate(at, mixed);
				}
			});

			look(server, x + 0.5, y + 1.0, z + 1.0, x, y + 1.6, z - 6.0);
			context.waitTicks(40);
			shoot(context, "mixed-slabs");
		}
	}

	/**
	 * Stand the camera at one place and point it at another. The camera's y is the feet, so it
	 * looks from 1.62 above where it stands.
	 */
	private void look(TestServerContext server, double x, double y, double z,
			double atX, double atY, double atZ) {
		double dx = atX - x;
		double dy = atY - (y + 1.62);
		double dz = atZ - z;
		double yaw = -Math.toDegrees(Math.atan2(dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		server.runCommand("tp @a %.2f %.2f %.2f %.1f %.1f".formatted(x, y, z, yaw, pitch));
	}

	private void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
			.withSize(WIDTH, HEIGHT)
			.disableCounterPrefix());
	}
}
