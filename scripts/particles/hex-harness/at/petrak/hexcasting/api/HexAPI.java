package at.petrak.hexcasting.api;
/** Geometry oracle: avoid unrelated mod/armor registration during resource-name initialization. */
public interface HexAPI {org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger("Hex geometry oracle");public static net.minecraft.resources.ResourceLocation modLoc(String path){return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hexcasting",path);}}
