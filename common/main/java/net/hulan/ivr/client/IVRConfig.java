package net.hulan.ivr.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hulan.ivr.IVR;
import net.hulan.ivr.render_promotion.utils.TrainRenderPolicy;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class IVRConfig {

    public static final int DISTANCE_STEP = 25;
    public static final int DEFAULT_BE_RD = 50;
    public static final int DEFAULT_TRAIN_RD = 200;
    public static final int MIN_DISTANCE = 25;
    public static final int MAX_DISTANCE = 250;
    private static final int DEFAULT_MSD_CATENARY_RD = 250;
    private static final int DEFAULT_ANTE_RAIL_BOX_RD = 250;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    //RE means render distance, be means block entity
    public static volatile int blockEntityRD = DEFAULT_BE_RD;
    public static volatile int trainRD = DEFAULT_TRAIN_RD;
    public static volatile int msdCatenaryRD = DEFAULT_MSD_CATENARY_RD;
    public static volatile int nteRailBoxRD = DEFAULT_ANTE_RAIL_BOX_RD;
    private static volatile long revision;

    //每次加载、保存使其递增，以此标记缓存失效
    public static long revision() {
        return revision;
    }

    public static void reset() {
        blockEntityRD = DEFAULT_BE_RD;
        trainRD = DEFAULT_TRAIN_RD;
        msdCatenaryRD = DEFAULT_MSD_CATENARY_RD;
        nteRailBoxRD = DEFAULT_ANTE_RAIL_BOX_RD;
        revision++;
        RenderDataRevision.bump();
        TrainRenderPolicy.refreshConfiguredDistances();
    }

    public static void load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            reset();
            return;
        }
        try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            blockEntityRD = read(json, "blockEntity", DEFAULT_BE_RD);
            trainRD = read(json, "train", DEFAULT_TRAIN_RD);
            msdCatenaryRD = read(json, "catenary", DEFAULT_MSD_CATENARY_RD);
            nteRailBoxRD = read(json, "nteRailBox", DEFAULT_ANTE_RAIL_BOX_RD);
            revision++;
            RenderDataRevision.bump();
            TrainRenderPolicy.refreshConfiguredDistances();
        } catch (RuntimeException | IOException exception) {
            reset();
            IVR.LOGGER.warn("Could not load IVR render config from {}", path, exception);
        }
    }

    public static void save(int newBlockEntityRD,
                            int newTrainRD,
                            int newMSDCatenaryRD,
                            int newAnteRailBoxRD) throws IOException {
        Path path = path();
        Files.createDirectories(path.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("blockEntity", normalize(newBlockEntityRD));
        json.addProperty("train", normalize(newTrainRD));
        json.addProperty("catenary", normalize(newMSDCatenaryRD));
        json.addProperty("nteRailBox", normalize(newAnteRailBoxRD));
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        blockEntityRD = normalize(newBlockEntityRD);
        trainRD = normalize(newTrainRD);
        msdCatenaryRD = normalize(newMSDCatenaryRD);
        nteRailBoxRD = normalize(newAnteRailBoxRD);
        revision++;
        RenderDataRevision.bump();
        TrainRenderPolicy.refreshConfiguredDistances();
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("ivr.json");
    }

    private static int read(JsonObject json, String key, int fallback) {
        try {
            String raw = json.get(key).getAsString();
            if (!raw.matches("[0-9]{1,4}")) return fallback;
            int value = Integer.parseInt(raw);
            return normalize(value);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int normalize(int value) {
        int rounded = Math.round((float) Mth.clamp(value, MIN_DISTANCE, MAX_DISTANCE) / DISTANCE_STEP) * DISTANCE_STEP;
        return Mth.clamp(rounded, MIN_DISTANCE, MAX_DISTANCE);
    }
}
