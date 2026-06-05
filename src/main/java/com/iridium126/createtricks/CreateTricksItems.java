package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import com.tterrag.registrate.util.entry.ItemEntry;

public final class CreateTricksItems {
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_KNOT = REGISTRATE
			.item("incomplete_knot", SequencedAssemblyItem::new)
			.register();

	private CreateTricksItems() {
	}

	public static void register() {
	}
}
