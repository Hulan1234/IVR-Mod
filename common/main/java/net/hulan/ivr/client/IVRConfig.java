package net.hulan.ivr.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hulan.ivr.IVR;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.hulan.ivr.render_promotion.mesh.RenderCacheLifecycle;
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
    /** Optional render optimisations are enabled by default and remain fail-open. */
    public static final boolean DEFAULT_CULLING_ENABLED = true;
    public static final boolean DEFAULT_CPU_OPTIMIZATION = true;
    public static final boolean DEFAULT_GPU_OPTIMIZATION = true;
    /** Zero keeps the automatic process-aware memory budget; non-zero is a hard total for IVR pools. */
    public static final int DEFAULT_MEMORY_BUDGET_MIB = 0;
    public static final int MIN_MEMORY_BUDGET_MIB = 0;
    public static final int MAX_MEMORY_BUDGET_MIB = 64;
    public static final int MEMORY_BUDGET_STEP_MIB = 4;
    /** The scheduler may grow below this cap according to backlog and frame time. */
    public static final int DEFAULT_MAX_OCCLUSION_RAYS = 160;
    public static final int MIN_MAX_OCCLUSION_RAYS = 36;
    public static final int MAX_MAX_OCCLUSION_RAYS = 160;
    public static final int OCCLUSION_RAY_STEP = 4;
    /** Kept for source compatibility with integrations using the old name. */
    @Deprecated
    public static final boolean DEFAULT_GPU_INSTANCES = DEFAULT_GPU_OPTIMIZATION;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    //RE means render distance, be means block entity
    public static volatile int blockEntityRD = DEFAULT_BE_RD;
    public static volatile int trainRD = DEFAULT_TRAIN_RD;
    public static volatile int msdCatenaryRD = DEFAULT_MSD_CATENARY_RD;
    public static volatile int nteRailBoxRD = DEFAULT_ANTE_RAIL_BOX_RD;
    public static volatile boolean cullingEnabled = DEFAULT_CULLING_ENABLED;
    public static volatile boolean cpuOptimization = DEFAULT_CPU_OPTIMIZATION;
    public static volatile boolean gpuOptimization = DEFAULT_GPU_OPTIMIZATION;
    /** @deprecated use {@link #gpuOptimization}. */
    @Deprecated
    public static volatile boolean gpuInstances = DEFAULT_GPU_OPTIMIZATION;
    public static volatile int memoryBudgetMiB = DEFAULT_MEMORY_BUDGET_MIB;
    public static volatile int maxOcclusionRays = DEFAULT_MAX_OCCLUSION_RAYS;
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
        cullingEnabled = DEFAULT_CULLING_ENABLED;
        cpuOptimization = DEFAULT_CPU_OPTIMIZATION;
        gpuOptimization = DEFAULT_GPU_OPTIMIZATION;
        gpuInstances = gpuOptimization;
        memoryBudgetMiB = DEFAULT_MEMORY_BUDGET_MIB;
        maxOcclusionRays = DEFAULT_MAX_OCCLUSION_RAYS;
        synchronized (IVRConfig.class) {
            revision++;
        }
        RenderDataRevision.bump();
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
            cullingEnabled = readBoolean(json, "cullingEnabled", DEFAULT_CULLING_ENABLED);
            cpuOptimization = readBoolean(json, "cpuOptimization", DEFAULT_CPU_OPTIMIZATION);
            boolean gpu = readBoolean(json, "gpuOptimization",
                    readBoolean(json, "gpuInstances", DEFAULT_GPU_OPTIMIZATION));
            gpuOptimization = gpu;
            gpuInstances = gpu;
            memoryBudgetMiB = normalizeMemoryBudget(readInt(json, "memoryBudgetMiB",
                    DEFAULT_MEMORY_BUDGET_MIB));
            maxOcclusionRays = normalizeMaxOcclusionRays(readInt(json, "maxOcclusionRays",
                    DEFAULT_MAX_OCCLUSION_RAYS));
            synchronized (IVRConfig.class) {
                revision++;
            }
            RenderDataRevision.bump();
        } catch (RuntimeException | IOException exception) {
            reset();
            IVR.LOGGER.warn("Could not load IVR render config from {}", path, exception);
        }
    }

    public static void save(int newBlockEntityRD,
                            int newTrainRD,
                            int newMSDCatenaryRD,
                            int newAnteRailBoxRD) throws IOException {
        save(newBlockEntityRD, newTrainRD, newMSDCatenaryRD, newAnteRailBoxRD,
                gpuOptimization, cullingEnabled, cpuOptimization, memoryBudgetMiB, maxOcclusionRays);
    }

    public static void save(int newBlockEntityRD,
                            int newTrainRD,
                            int newMSDCatenaryRD,
                            int newAnteRailBoxRD,
                            boolean newGpuInstances) throws IOException {
        save(newBlockEntityRD, newTrainRD, newMSDCatenaryRD, newAnteRailBoxRD,
                newGpuInstances, cullingEnabled, cpuOptimization, memoryBudgetMiB, maxOcclusionRays);
    }

    public static void save(int newBlockEntityRD,
                            int newTrainRD,
                            int newMSDCatenaryRD,
                            int newAnteRailBoxRD,
                            boolean newGpuOptimization,
                            boolean newCullingEnabled,
                            boolean newCpuOptimization,
                            int newMemoryBudgetMiB,
                            int newMaxOcclusionRays) throws IOException {
        boolean gpuSettingChanged = gpuOptimization != newGpuOptimization;
        boolean cpuSettingChanged = cpuOptimization != newCpuOptimization;
        boolean memorySettingChanged = memoryBudgetMiB != normalizeMemoryBudget(newMemoryBudgetMiB);
        Path path = path();
        Files.createDirectories(path.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("blockEntity", normalize(newBlockEntityRD));
        json.addProperty("train", normalize(newTrainRD));
        json.addProperty("catenary", normalize(newMSDCatenaryRD));
        json.addProperty("nteRailBox", normalize(newAnteRailBoxRD));
        json.addProperty("cullingEnabled", newCullingEnabled);
        json.addProperty("cpuOptimization", newCpuOptimization);
        json.addProperty("gpuOptimization", newGpuOptimization);
        json.addProperty("memoryBudgetMiB", normalizeMemoryBudget(newMemoryBudgetMiB));
        json.addProperty("maxOcclusionRays", normalizeMaxOcclusionRays(newMaxOcclusionRays));
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
        cullingEnabled = newCullingEnabled;
        cpuOptimization = newCpuOptimization;
        gpuOptimization = newGpuOptimization;
        gpuInstances = newGpuOptimization;
        memoryBudgetMiB = normalizeMemoryBudget(newMemoryBudgetMiB);
        maxOcclusionRays = normalizeMaxOcclusionRays(newMaxOcclusionRays);
        synchronized (IVRConfig.class) {
            revision++;
        }
        RenderDataRevision.bump();
        if (gpuSettingChanged || cpuSettingChanged || memorySettingChanged) RenderCacheLifecycle.invalidate();
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

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) ? json.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        try {
            if (!json.has(key)) return fallback;
            return json.get(key).getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int normalize(int value) {
        int rounded = Math.round((float) Mth.clamp(value, MIN_DISTANCE, MAX_DISTANCE) / DISTANCE_STEP) * DISTANCE_STEP;
        return Mth.clamp(rounded, MIN_DISTANCE, MAX_DISTANCE);
    }

    private static int normalizeMemoryBudget(int value) {
        if (value <= MIN_MEMORY_BUDGET_MIB) return MIN_MEMORY_BUDGET_MIB;
        int rounded = Math.round((float) value / MEMORY_BUDGET_STEP_MIB) * MEMORY_BUDGET_STEP_MIB;
        return Mth.clamp(rounded, MIN_MEMORY_BUDGET_MIB, MAX_MEMORY_BUDGET_MIB);
    }

    private static int normalizeMaxOcclusionRays(int value) {
        int rounded = Math.round((float) value / OCCLUSION_RAY_STEP) * OCCLUSION_RAY_STEP;
        return Mth.clamp(rounded, MIN_MAX_OCCLUSION_RAYS, MAX_MAX_OCCLUSION_RAYS);
    }
}
