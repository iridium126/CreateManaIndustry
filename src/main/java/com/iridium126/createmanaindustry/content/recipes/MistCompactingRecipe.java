package com.iridium126.createmanaindustry.content.recipes;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;

public class MistCompactingRecipe extends BasinRecipe {

    public MistCompactingRecipe(ProcessingRecipeParams params) {
        super(CMIRecipeTypes.MIST_COMPACTING, params);
    }

    /** Returns the mist output config, or null if this recipe has no mist byproduct. */
    public MistOutput getMistOutput() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMist();
        return null;
    }

    /** Returns the mist requirement, or null if this recipe has no mist condition. */
    public MistRequirement getMistRequirement() {
        if (getParams() instanceof MistRecipeParams mistParams)
            return mistParams.getMistRequirement();
        return null;
    }
}
