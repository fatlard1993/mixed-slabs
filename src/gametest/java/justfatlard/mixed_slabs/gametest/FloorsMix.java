package justfatlard.mixed_slabs.gametest;

import justfatlard.mixed_slabs.Main;
import justfatlard.mixed_slabs.SlabPalette;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * A wood floor pairs with a slab both ways up.
 *
 * <p>A floor board is in the palette as a topper, beside the carpets and the torches, and a topper
 * is refused the lower half on the grounds that nothing would be resting on it. That is true of a
 * carpet and false of a floor: lying on the ground is the whole of what a floor is, and its own
 * block defaults to the bottom half. So the pairing worked one way up and not the other, which is
 * the shape of bug that gets reported as "I can't place it".
 */
public final class FloorsMix implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		// No world needed: the palette and the pairing table are built when the mod loads, so the
		// question can be asked of them directly.
		context.runOnClient(client -> {
			Block floor = block("wood-floor-justfatlard:oak_floor");
			Block slab = block("minecraft:oak_slab");
			Block carpet = block("minecraft:red_carpet");

			int floorAt = SlabPalette.indexOf(floor);
			int slabAt = SlabPalette.indexOf(slab);
			int carpetAt = SlabPalette.indexOf(carpet);
			if (floorAt < 0 || slabAt < 0) {
				throw new AssertionError("the palette is missing a floor or a slab");
			}

			// A slab underneath and a board on top: the pairing that always worked.
			if (Main.stateFor(slabAt, floorAt) == null) {
				throw new AssertionError("a board will not lie on top of a slab");
			}

			// A board underneath and a slab on top: the one being fixed.
			if (Main.stateFor(floorAt, slabAt) == null) {
				throw new AssertionError("a board will not lie under a slab");
			}

			// And a carpet is still refused the floor, because the reason for that rule is real
			// everywhere except the boards.
			if (carpetAt >= 0 && Main.stateFor(carpetAt, slabAt) != null) {
				throw new AssertionError("a carpet was allowed to be the lower half");
			}
		});
	}

	private static Block block(String id) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
	}
}
