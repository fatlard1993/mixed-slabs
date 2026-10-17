package justfatlard.mixed_slabs.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets a state's light be set after it is built. Vanilla fixes it in the constructor, and a mixed
 * slab's light depends on another mod's block that may not exist yet at that point.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public interface BlockStateLight {

	@Mutable
	@Accessor("lightEmission")
	void mixedSlabs$setLightEmission(int light);
}
