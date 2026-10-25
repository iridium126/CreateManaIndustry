package com.iridium126.createmanaindustry.client.render.shaderpack;

/** Pure curve-encoded source transformer. Candidate validation and ID maps belong to ShaderPatchPlan. */
final class CurveEncodedSourcePatch {
 private final int blockId;
 private final float materialId;
 private final int mistBuffer,glowBuffer;
 CurveEncodedSourcePatch(int blockId,float materialId,int mistBuffer,int glowBuffer){this.blockId=blockId;this.materialId=materialId;this.mistBuffer=mistBuffer;this.glowBuffer=glowBuffer;}
	/** Fresh block.properties id claimed by the tank's lit states. */
	public static final int TANK_BLOCK_ID = 7110;
	/** Fresh MATID the block id converts to (Settings.glsl table slot). */
	public static final float TANK_MATID = 29.0F;
	/** Warm rose pink, matched across this mod's other pack adapters. */
	public static final String PINK_COLOR_LITERAL = "vec3(1.00, 0.35, 0.55)";
	/** Between the torch (0.03) and glowstone (0.016) emission weight tiers. */
	public static final float TANK_EMISSION_WEIGHT = 0.02F;

	/** Global-scope anchor for the mist sampler declaration in composite.fsh. */
	private static final String OUTPUT_DECL_ANCHOR =
		"layout(location = 0) out vec4 compositeOutput1;";

	/**
	 * The tail of composite.fsh's main(): the lit-scene assignment at the end of
	 * the non-sky branch plus that branch's closing brace. Anchoring on the pair
	 * and inserting right after it places the mist merge inside main(), after
	 * BOTH branch exits — so mist composites over solid geometry and over sky
	 * alike — while staying clear of main's own closing brace (a bare block at
	 * translation-unit level would not parse).
	 */
	private static final String COMPOSITE_TAIL_ANCHOR =
		"\t\tcompositeOutput1 = vec4(finalComposite, 0.0);\n\t}";

	/**
	 * Radiance scale converting this mod's calibrated mist units into the
	 * pack's raw linear light space. colortex1 stores
	 * {@code LinearToCurve(litRadiance / MAIN_OUTPUT_FACTOR)} (2048), and the
	 * pack's own auto-exposure weighting operates on curve-linear luminances in
	 * the 6.25e-8 .. 1.55e-6 band — i.e. typical scene luminance sits around
	 * 1e-6 there. A scale of 1e-5 puts a fully-emissive dense mist near
	 * sunlit-surface brightness while thin wisps stay subtle, matching how the
	 * same fluids read under this mod's other pack adapters.
	 */
	public static final float MIST_RADIANCE_SCALE = 1.0e-5F;

	/**
	 * Radiance scale for the fuel rod ring layer (colortex10). Same units and
	 * reasoning as {@link #MIST_RADIANCE_SCALE}; set slightly hotter so the
	 * reactor ring reads as the most intense emitter in its scene, matching
	 * how it reads under this mod's other pack adapters.
	 */
	public static final float RING_RADIANCE_SCALE = 2.0e-5F;

	private static final String MIST_MERGE_BLOCK =
		"\n"
			+ "\t// [CMI] layer merge — begin (injected by Create: Mana Industry)\n"
			+ "\t{\n"
			+ "\t\tvec4 cmiMist = texelFetch(colortex9, texelCoord, 0);\n"
			+ "\t\tvec3 cmiRing = texelFetch(colortex10, texelCoord, 0).rgb;\n"
			+ "\t\tif (cmiMist.a > 0.0001 || cmiRing != vec3(0.0)){\n"
			+ "\t\t\tvec3 cmiScene = CurveToLinear(compositeOutput1.rgb);\n"
			+ "\t\t\tif (cmiMist.a > 0.0001)\n"
			+ "\t\t\t\tcmiScene = cmiScene * (1.0 - cmiMist.a) + cmiMist.rgb * "
			+ scaleLiteral(MIST_RADIANCE_SCALE) + ";\n"
			+ "\t\t\tcmiScene += cmiRing * " + scaleLiteral(RING_RADIANCE_SCALE) + ";\n"
			+ "\t\t\tcompositeOutput1 = vec4(LinearToCurve(cmiScene), compositeOutput1.a);\n"
			+ "\t\t}\n"
			+ "\t}\n"
			+ "\t// [CMI] layer merge — end";

	public String transformShader(String fileName, String source) {
		switch (fileName) {
			case "Settings.glsl":
				return insertBefore(source, "#define MATID_OXIDIZED_BULB",
					"\t#define MATID_TANK\t\t\t\t" + matIdLiteral(materialId)
						+ " // Molten Salt Fuel Tank [CMI]\n",
					"MATID_TANK define");
			case "Terrain_VS.glsl":
				return insertBefore(source, "case 7102:",
					"\t\tcase " + blockId + ":\n"
						+ "\t\t\tv_materialIDs = MATID_TANK;\n"
						+ "\t\t\tbreak;\n\n",
					"block id material mapping");
			case "GbufferData.glsl":
				String structPatched = insertBefore(source, "float oxidizedBulb;",
					"\tfloat tank; // Molten Salt Fuel Tank [CMI]\n",
					"mask struct field");
				if (structPatched == null)
					return null;
				return insertBefore(structPatched, "mask.oxidizedBulb",
					"\tmask.tank\t\t\t\t= float(materialIDs == MATID_TANK); // Molten Salt Fuel Tank [CMI]\n",
					"mask assignment");
			case "Blocklight.glsl":
				return patchBlocklight(source);
			case "composite.fsh":
				return patchComposite(source);
			default:
				return null;
		}
	}

	/**
	 * Applies the three Blocklight.glsl edits in one pass: the light-source
	 * exemption (same effect as adding mask.tank to the saturate()d sum above,
	 * expressed as one self-contained statement anchored before its consumer —
	 * anchoring inside the multi-line saturate() argument list would splice a
	 * statement into an expression), the texture-emission weight beside its
	 * sibling terms, and the pink override right after the statement that
	 * assigns every other emissive the global torch colour. Any anchor surprise
	 * leaves the whole file untouched.
	 */
	private static String patchBlocklight(String source) {
		String patched = insertBefore(source,
			"lightmap = min(lightmap, 1.0 - 0.13 * lightSourceMask);",
			"\tlightSourceMask = max(lightSourceMask, mask.tank); // Molten Salt Fuel Tank [CMI]\n",
			"light source exemption");
		if (patched == null)
			return null;
		patched = insertBefore(patched, "* 4.0;",
			"\t\t\t  blockLightingMask += \tmask.tank \t\t\t\t*" + TANK_EMISSION_WEIGHT
				+ "; // Molten Salt Fuel Tank [CMI]\n",
			"emission weight");
		if (patched == null)
			return null;
		return insertAfter(patched, "if (blockLightingMask > 0.0) blockLighting = colorTorchlight;",
			"\t\tif (mask.tank > 0.5) blockLighting = " + PINK_COLOR_LITERAL
				+ "; // Molten Salt Fuel Tank [CMI]\n",
			"pink emission colour");
	}

	/**
	 * Applies the two composite.fsh edits in one pass: the global-scope sampler
	 * declarations for both CMI layers (colortex9 mist, colortex10 fuel rod
	 * ring) beside the output declaration, and the layer-merge block between
	 * the lighting branch's close and main's own — after both branch exits, so
	 * sky and solid pixels composite alike. Any anchor surprise leaves the
	 * whole file untouched.
	 */
	private String patchComposite(String source) {
		String declared = insertAfter(source, OUTPUT_DECL_ANCHOR,
			"\nuniform sampler2D colortex"+mistBuffer+"; // [CMI] mist layer\n"
				+ "uniform sampler2D colortex"+glowBuffer+"; // [CMI] fuel rod ring layer\n"
                + layerDirectives(mistBuffer)+layerDirectives(glowBuffer),
			"layer sampler declarations");
		if (declared == null)
			return null;
		String merge=MIST_MERGE_BLOCK.replace("colortex10","cmiGlowLayer").replace("colortex9","colortex"+mistBuffer).replace("cmiGlowLayer","colortex"+glowBuffer);
		return insertAfterMatch(declared, COMPOSITE_TAIL_ANCHOR, merge, "layer merge");
	}
    private static String layerDirectives(int buffer){
        return "/*\nconst int colortex"+buffer+"Format = RGBA16F;\n"
                +"const bool colortex"+buffer+"Clear = true;\n"
                +"const vec4 colortex"+buffer+"ClearColor = vec4(0.0, 0.0, 0.0, 0.0);\n*/\n";
    }

	/**
	 * Inserts {@code insertion} directly after the END of the (exactly once
	 * occurring) multi-line {@code anchor} text — unlike {@link #insertAfter},
	 * which anchors on a single line and inserts after its newline. Used for the
	 * composite.fsh tail, whose insertion point sits between the anchor's last
	 * two lines. Returns {@code null} if the anchor is missing or ambiguous.
	 */
	private static String insertAfterMatch(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int insertAt = endOfAnchor(source, anchor);
		String newline = source.contains("\r\n") ? "\r\n" : "\n";
		String anchoredInsertion = insertion.replace("\n", newline);

		return source.substring(0, insertAt) + anchoredInsertion + source.substring(insertAt);
	}

	/** Renders {@code 1.0e-5} from {@code 1.0e-5F}, matching GLSL literal style. */
	private static String scaleLiteral(float value) {
		return String.valueOf(value).replace("E", "e");
	}

	/** Renders {@code 29.0} from {@code 29}, matching the pack's define style. */
	private static String matIdLiteral(float value) {
		return value == Math.floor(value) ? (int) value + ".0" : String.valueOf(value);
	}

	/**
	 * Inserts the insertion on its own line directly before the line containing
	 * the (exactly once occurring) anchor text. Returns null if the anchor is
	 * missing or ambiguous.
	 */
	private static String insertBefore(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int lineStart = source.lastIndexOf("\n", at) + 1;

		return source.substring(0, lineStart) + insertion + source.substring(lineStart);
	}

	/**
	 * Inserts the insertion directly after the line containing the (exactly once
	 * occurring) anchor text. Returns null if the anchor is missing or ambiguous.
	 */
	private static String insertAfter(String source, String anchor, String insertion, String label) {
		int at = uniqueIndexOf(source, anchor, label);
		if (at < 0)
			return null;

		int lineEnd = source.indexOf("\n", at);
		int insertAt = lineEnd < 0 ? source.length() : lineEnd + 1;

		return source.substring(0, insertAt) + insertion + source.substring(insertAt);
	}

	/** Index of the anchor when it occurs exactly once, else -1 with a warning. */
	private static java.util.regex.Matcher matchAnchor(String source,String anchor) {
        var tokens=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z_0-9]*|[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|[^\\s]").matcher(ShaderPackInventory.code(anchor));
        var regex=new StringBuilder();while(tokens.find())regex.append(java.util.regex.Pattern.quote(tokens.group())).append("\\s*");
        // Do not consume whitespace after the last token: the following newline
        // must survive insertion when the injected block ends in a line comment.
        if(regex.length()>=3)regex.setLength(regex.length()-3);
        return java.util.regex.Pattern.compile(regex.toString()).matcher(ShaderPackInventory.code(source));
    }
    private static int uniqueIndexOf(String source,String anchor,String label) {
        var matcher=matchAnchor(source,anchor);if(!matcher.find())return -1;int start=matcher.start();return matcher.find()?-1:start;
    }
    private static int endOfAnchor(String source,String anchor) {var m=matchAnchor(source,anchor);return m.find()?m.end():-1;}
}
