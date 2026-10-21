package com.iridium126.createmanaindustry.client.particles.shaderpack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StarfallSamplerReservationTest {
 @Test void sixUnitsIncludeDomePoseAndNestedScopesRestore(){assertFalse(PackageSamplerReservation.active());try(var outer=PackageSamplerReservation.open()){assertEquals(3,PackageSamplerReservation.units());try(var inner=PackageSamplerReservation.open(ShaderPackProgramCompiler.MERGED_SAMPLER_UNIT_COUNT)){assertEquals(6,PackageSamplerReservation.units());try(var nested=PackageSamplerReservation.open(4)){assertEquals(6,PackageSamplerReservation.units());}}assertEquals(3,PackageSamplerReservation.units());}assertFalse(PackageSamplerReservation.active());assertThrows(IllegalArgumentException.class,()->PackageSamplerReservation.open(7));}
}
