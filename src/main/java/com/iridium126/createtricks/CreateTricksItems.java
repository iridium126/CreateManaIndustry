package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import com.tterrag.registrate.util.entry.ItemEntry;

public final class CreateTricksItems {
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_AMETHYST_KNOT =
			incompleteKnot("incomplete_amethyst_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_QUARTZ_KNOT =
			incompleteKnot("incomplete_quartz_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_EMERALD_KNOT =
			incompleteKnot("incomplete_emerald_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_DIAMOND_KNOT =
			incompleteKnot("incomplete_diamond_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_ECHO_KNOT =
			incompleteKnot("incomplete_echo_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_ASTRAL_KNOT =
			incompleteKnot("incomplete_astral_knot");

	private CreateTricksItems() {
	}

	public static void register() {
	}

	private static ItemEntry<SequencedAssemblyItem> incompleteKnot(String name) {
		return REGISTRATE.item(name, SequencedAssemblyItem::new)
				.register();
	}
}
