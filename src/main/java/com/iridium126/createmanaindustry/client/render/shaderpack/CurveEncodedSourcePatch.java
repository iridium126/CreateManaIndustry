package com.iridium126.createmanaindustry.client.render.shaderpack;

/** Pure curve-encoded source transformer. Candidate validation and ID maps belong to ShaderPatchPlan. */
final class CurveEncodedSourcePatch {
 private final int mistBuffer,glowBuffer;
 CurveEncodedSourcePatch(int mistBuffer,int glowBuffer){this.mistBuffer=mistBuffer;this.glowBuffer=glowBuffer;}

	/** Global-scope anchor for the layer sampler declarations in composite.fsh. */
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

	private static final String MIST_MERGE_BLOCK =
		"\n"
			+ "\t// [CMI] layer merge — begin (injected by Create: Mana Industry)\n"
			+ "\t{\n"
			+ "\t\tvec4 cmiMist = texelFetch(colortex9, texelCoord, 0);\n"
			+ "\t\tif (cmiMist.a > 0.0001){\n"
			+ "\t\t\tvec3 cmiScene = CurveToLinear(compositeOutput1.rgb);\n"
			+ "\t\t\tcmiScene = cmiScene * (1.0 - cmiMist.a) + cmiMist.rgb * "
			+ scaleLiteral(MIST_RADIANCE_SCALE) + ";\n"
			+ "\t\t\tcompositeOutput1 = vec4(LinearToCurve(cmiScene), compositeOutput1.a);\n"
			+ "\t\t}\n"
			+ "\t}\n"
			+ "\t// [CMI] layer merge — end";

	public String transformShader(String fileName, String source) {
		return fileName.equals("composite.fsh") ? patchComposite(source) : null;
	}

	/**
	 * Applies the composite.fsh edits in one pass: the global-scope sampler
	 * declarations for the CMI layers (mist and the probe/debug attachment)
	 * beside the output declaration, and the mist merge block between the
	 * lighting branch's close and main's own — after both branch exits, so
	 * sky and solid pixels composite alike. Any anchor surprise leaves the
	 * whole file untouched.
	 */
	private String patchComposite(String source) {
		String declared = insertAfter(source, OUTPUT_DECL_ANCHOR,
			"\nuniform sampler2D colortex"+mistBuffer+"; // [CMI] mist layer\n"
				+ "uniform sampler2D colortex"+glowBuffer+"; // [CMI] probe layer\n"
                + layerDirectives(mistBuffer)+layerDirectives(glowBuffer),
			"layer sampler declarations");
		if (declared == null)
			return null;
		String merge=MIST_MERGE_BLOCK.replace("colortex9","colortex"+mistBuffer);
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
