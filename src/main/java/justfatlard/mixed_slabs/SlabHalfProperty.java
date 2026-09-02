package justfatlard.mixed_slabs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Which slab fills one half of a mixed slab.
 *
 * <p>A palette index underneath, wearing the slab's name. The name is the part that matters: it is
 * what the blockstate file matches on, so the models are bound to {@code top_slab=oak_slab} rather
 * than to {@code top_slab=37}, and reordering the palette cannot silently repaint a world. It is
 * also what turns up in F3.
 *
 * <p>The possible values are palette indices and may be a <em>subset</em> of the palette, because
 * the two mixed slab blocks split the top half between them. A value therefore means the same thing
 * whichever block it came from - there is one numbering, not one per block - which is what lets a
 * half be read, compared and moved between them without a translation step to get wrong.
 *
 * <p>Deliberately not an {@code EnumProperty}: the values come from a generated list, and a Java
 * enum cannot be built from one at run time.
 */
public final class SlabHalfProperty extends Property<Integer> {

	/** Palette indices this half may hold, in order. */
	private final List<Integer> values;

	/** Dense position within {@link #values}, which is what the state machinery indexes by. */
	private final Map<Integer, Integer> internalIndices;

	private final Map<String, Integer> byName;

	private SlabHalfProperty(String name, List<Integer> paletteIndices) {
		super(name, Integer.class);

		Map<Integer, Integer> internal = new HashMap<>(paletteIndices.size());
		Map<String, Integer> lookup = new HashMap<>(paletteIndices.size());
		for (int position = 0; position < paletteIndices.size(); position++) {
			int paletteIndex = paletteIndices.get(position);
			internal.put(paletteIndex, position);
			lookup.put(SlabPalette.names().get(paletteIndex), paletteIndex);
		}

		this.values = List.copyOf(paletteIndices);
		this.internalIndices = Map.copyOf(internal);
		this.byName = Map.copyOf(lookup);
	}

	public static SlabHalfProperty create(String name, List<Integer> paletteIndices) {
		return new SlabHalfProperty(name, paletteIndices);
	}

	@Override
	public List<Integer> getPossibleValues() { return values; }

	@Override
	public String getName(Integer value) {
		List<String> names = SlabPalette.names();
		return value >= 0 && value < names.size() ? names.get(value) : String.valueOf(value);
	}

	@Override
	public Optional<Integer> getValue(String name) {
		return Optional.ofNullable(byName.get(name));
	}

	/**
	 * Dense index for state storage, which is not the palette index once this property holds a
	 * subset of the palette. Returning the palette index here would have the state machinery
	 * indexing an array by a number far past its end.
	 */
	@Override
	public int getInternalIndex(Integer value) {
		Integer position = internalIndices.get(value);
		return position == null ? 0 : position;
	}
}
