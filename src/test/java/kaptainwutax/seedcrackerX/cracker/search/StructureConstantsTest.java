package kaptainwutax.seedcrackerX.cracker.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.Monument;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.SwampHut;
import kaptainwutax.seedcrackerX.structures.TrialChambers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The seedfinding libraries hard-code structure placement per version. This compares the newest version they know
 * against the placement data of the Minecraft version the mod is built for, so a changed salt or spacing fails the build.
 */
class StructureConstantsTest {

    private static final MCVersion VERSION = MCVersion.latest();

    static Stream<Arguments> structures() {
        return Stream.of(
                Arguments.of("desert_pyramids", new DesertPyramid(VERSION)),
                Arguments.of("jungle_temples", new JunglePyramid(VERSION)),
                Arguments.of("swamp_huts", new SwampHut(VERSION)),
                Arguments.of("igloos", new Igloo(VERSION)),
                Arguments.of("shipwrecks", new Shipwreck(VERSION)),
                Arguments.of("pillager_outposts", new PillagerOutpost(VERSION)),
                Arguments.of("ocean_monuments", new Monument(VERSION)),
                Arguments.of("end_cities", new EndCity(VERSION)),
                Arguments.of("trial_chambers", new TrialChambers(VERSION))
        );
    }

    @ParameterizedTest
    @MethodSource("structures")
    void placementMatchesVanillaData(String structureSet, RegionStructure<?, ?> structure) throws Exception {
        String path = "data/minecraft/worldgen/structure_set/" + structureSet + ".json";
        try (InputStream in = StructureConstantsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Minecraft jar has no " + path);
            JsonObject placement = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("placement");

            assertEquals(placement.get("spacing").getAsInt(), structure.getSpacing(), "spacing");
            assertEquals(placement.get("separation").getAsInt(), structure.getSeparation(), "separation");
            assertEquals(placement.get("salt").getAsInt(), structure.getSalt(), "salt");
        }
    }
}
