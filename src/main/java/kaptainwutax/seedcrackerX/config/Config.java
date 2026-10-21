package kaptainwutax.seedcrackerX.config;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seedfinding.mccore.version.MCVersion;
import kaptainwutax.seedcrackerX.Features;
import kaptainwutax.seedcrackerX.util.FeatureToggle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class Config {
    private static final Logger logger = LoggerFactory.getLogger("config");

    private static final File file = new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "seedcracker.json");
    private static Config INSTANCE = new Config();
    public FeatureToggle buriedTreasure = new FeatureToggle(true);
    public FeatureToggle desertTemple = new FeatureToggle(true);
    public FeatureToggle endCity = new FeatureToggle(true);
    public FeatureToggle jungleTemple = new FeatureToggle(true);
    public FeatureToggle monument = new FeatureToggle(true);
    public FeatureToggle swampHut = new FeatureToggle(true);
    public FeatureToggle shipwreck = new FeatureToggle(true);
    public FeatureToggle outpost = new FeatureToggle(true);
    public FeatureToggle igloo = new FeatureToggle(true);
    public FeatureToggle trialChambers = new FeatureToggle(true);
    public FeatureToggle endPillars = new FeatureToggle(true);
    public FeatureToggle endGateway = new FeatureToggle(false);
    public FeatureToggle dungeon = new FeatureToggle(true);
    public FeatureToggle emeraldOre = new FeatureToggle(false);
    public FeatureToggle desertWell = new FeatureToggle(false);
    public FeatureToggle warpedFungus = new FeatureToggle(false);
    public FeatureToggle biome = new FeatureToggle(false);
    public RenderType render = RenderType.XRAY;
    public boolean active = true;
    public boolean debug = false;
    public boolean antiXrayBypass = true;
    private MCVersion version = MCVersion.latest();
    public boolean databaseSubmits = false;
    public boolean anonymusSubmits = false;

    public static void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        // make sure that the config directory exists
        file.getParentFile().mkdirs();

        // Write a temporary file and swap it in, so a crash mid-write can't leave a truncated config.
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp)) {
                gson.toJson(INSTANCE, writer);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("seedcracker couldn't save config", e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    public static void load() {
        Gson gson = new Gson();

        if (!file.exists()) return;

        try (Reader reader = new FileReader(file)) {
            Config loaded = gson.fromJson(reader, Config.class);
            if (loaded == null) throw new IOException("config file is empty");
            INSTANCE = loaded;
        } catch (Exception e) {
            logger.error("seedcracker couldn't load config, using the defaults", e);
            backUpUnreadableConfig();
        }
    }

    /** Moves an unreadable config aside instead of deleting it, so the next save doesn't overwrite it. */
    private static void backUpUnreadableConfig() {
        Path backup = file.toPath().resolveSibling(file.getName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), backup);
            logger.warn("moved the unreadable config to {}", backup);
        } catch (IOException e) {
            logger.error("seedcracker couldn't move the unreadable config aside", e);
        }
    }

    public static Config get() {
        return INSTANCE;
    }

    public MCVersion getVersion() {
        return version;
    }

    public void setVersion(MCVersion version) {
        if (this.version == version) return;
        this.version = version;
        Features.init(version);
    }

    public enum RenderType {
        OFF, ON, XRAY
    }
}
