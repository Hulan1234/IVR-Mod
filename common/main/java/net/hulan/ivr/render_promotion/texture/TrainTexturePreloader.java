package net.hulan.ivr.render_promotion.texture;

import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import net.hulan.ivr.client.IVRConfig;
import net.hulan.ivr.render_promotion.cache.FrustumCache;
import net.hulan.ivr.render_promotion.itf.TrainTextureSource;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Iterator;

/** Bounded texture-only warmup for trains approaching the configured render range. */
public final class TrainTexturePreloader {

    private static final double PREPARE_BAND = 48.0D;
    private static final long FRAME_BUDGET_NANOS = 1_000_000L;
    private static final int MAX_PER_FRAME = 2;
    private static final Set<ResourceLocation> REQUESTED = new HashSet<>();
    private static final Map<TrainRendererBase, Boolean> RESOLVED = new WeakHashMap<>();
    private static final Map<TrainRendererBase, Integer> ATTEMPTS = new WeakHashMap<>();
    private static final Map<ResourceLocation, TrainRendererBase> PENDING = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Integer> TEXTURE_ATTEMPTS = new java.util.HashMap<>();
    private static final Map<ResourceLocation, Long> RETRY_AT = new java.util.HashMap<>();
    private static final long RETRY_DELAY_NANOS = 1_000_000_000L;
    private static boolean reloading;
    private static long generation;
    private static Level world;
    private static long lastFrame = Long.MIN_VALUE;
    private static int submitted;
    private static long frameNanos;

    public static synchronized void consider(Level currentWorld, TrainClient train, Vec3[] positions) {
        if (currentWorld == null || train == null || positions == null
                || Minecraft.getInstance().level != currentWorld
                || Minecraft.getInstance().player != null
                && train.isPlayerRiding(Minecraft.getInstance().player)) return;
        if (world != currentWorld) {
            RESOLVED.clear();
            ATTEMPTS.clear();
            world = currentWorld;
        }
        long frame = FrustumCache.frameId();
        if (frame != lastFrame) {
            lastFrame = frame;
            submitted = 0;
            frameNanos = 0L;
        }
        if (submitted >= MAX_PER_FRAME || frameNanos >= FRAME_BUDGET_NANOS) return;
        double distance = distanceToTrain(positions, FrustumCache.capture(currentWorld).cameraPosition(),
                Math.max(32.0D, train.spacing));
        double reach = IVRConfig.trainRD;
        if (!Double.isFinite(distance) || distance <= reach || distance > reach + PREPARE_BAND) return;
        TrainRendererBase renderer = train.trainRenderer;
        if (!(renderer instanceof TrainTextureSource source) || RESOLVED.containsKey(renderer)
                || ATTEMPTS.getOrDefault(renderer, 0) >= 3) return;
        long started = System.nanoTime();
        submitted++;
        ATTEMPTS.put(renderer, ATTEMPTS.getOrDefault(renderer, 0) + 1);
        try {
            ResourceLocation texture = source.ivr$getTrainTexture();
            if (texture != null && requestTexture(texture, renderer)) {
                RESOLVED.put(renderer, Boolean.TRUE);
            }
        } finally {
            frameNanos += System.nanoTime() - started;
        }
    }

    /** Called by the optional ANTE resolver hook with ANTE's already-resolved texture. */
    public static synchronized void requestAnteTexture(ResourceLocation texture) {
        if (texture != null) {
            long requestedGeneration = generation;
            Minecraft.getInstance().execute(() -> acceptAnteTexture(requestedGeneration, texture));
        }
    }

    private static synchronized void acceptAnteTexture(long requestedGeneration, ResourceLocation texture) {
        if (generation != requestedGeneration) return;
        enqueue(texture, null);
        drain(requestedGeneration);
    }

    private static boolean requestTexture(ResourceLocation texture, TrainRendererBase renderer) {
        enqueue(texture, renderer);
        return true;
    }

    private static void enqueue(ResourceLocation texture, TrainRendererBase renderer) {
        if (TEXTURE_ATTEMPTS.getOrDefault(texture, 0) < 3
                && !REQUESTED.contains(texture) && !PENDING.containsKey(texture)) PENDING.put(texture, renderer);
    }

    /** Invoked on the client render thread even while no world is loaded. */
    public static synchronized void drain() {
        drain(generation);
    }

    private static synchronized void drain(long requestedGeneration) {
        if (generation != requestedGeneration || reloading || !Minecraft.getInstance().isSameThread()) return;
        long frame = FrustumCache.frameId();
        if (frame != lastFrame) {
            lastFrame = frame;
            submitted = 0;
            frameNanos = 0L;
        }
        Iterator<Map.Entry<ResourceLocation, TrainRendererBase>> entries = PENDING.entrySet().iterator();
        while (entries.hasNext() && submitted < MAX_PER_FRAME && frameNanos < FRAME_BUDGET_NANOS) {
            Map.Entry<ResourceLocation, TrainRendererBase> entry = entries.next();
            ResourceLocation texture = entry.getKey();
            if (System.nanoTime() < RETRY_AT.getOrDefault(texture, Long.MIN_VALUE)) continue;
            TrainRendererBase renderer = entry.getValue();
            entries.remove();
            long started = System.nanoTime();
            submitted++;
            submit(texture, renderer);
            // A synchronous failure can requeue the resource, invalidating the iterator.
            entries = PENDING.entrySet().iterator();
            frameNanos += System.nanoTime() - started;
        }
    }

    private static void submit(ResourceLocation texture, TrainRendererBase renderer) {
        if (!REQUESTED.add(texture)) return;
        TEXTURE_ATTEMPTS.put(texture, TEXTURE_ATTEMPTS.getOrDefault(texture, 0) + 1);
        long requestedGeneration = generation;
        try {
            TextureManager manager = Minecraft.getInstance().getTextureManager();
            java.util.concurrent.CompletableFuture<Void> future = manager.preload(texture, Util.backgroundExecutor());
            future.whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
                if (failure != null) {
                    failed(requestedGeneration, texture, renderer);
                }
            }));
        } catch (RuntimeException exception) {
            failed(requestedGeneration, texture, renderer);
        }
    }

    public static synchronized void invalidate() {
        REQUESTED.clear();
        PENDING.clear();
        TEXTURE_ATTEMPTS.clear();
        RETRY_AT.clear();
        RESOLVED.clear();
        ATTEMPTS.clear();
        generation++;
        lastFrame = Long.MIN_VALUE;
        submitted = 0;
        frameNanos = 0L;
    }

    public static synchronized long beginReload() {
        invalidate();
        reloading = true;
        return generation;
    }

    public static synchronized void finishReload(long reloadGeneration) {
        if (generation != reloadGeneration) return;
        reloading = false;
        drain(reloadGeneration);
    }

    private static synchronized void failed(long requestedGeneration, ResourceLocation texture,
                                             TrainRendererBase renderer) {
        if (generation != requestedGeneration) return;
        REQUESTED.remove(texture);
        if (renderer != null) RESOLVED.remove(renderer);
        if (TEXTURE_ATTEMPTS.getOrDefault(texture, 0) < 3) {
            RETRY_AT.put(texture, System.nanoTime() + RETRY_DELAY_NANOS);
            enqueue(texture, renderer);
        }
    }

    private static double distanceToTrain(Vec3[] positions, Vec3 camera, double margin) {
        if (positions.length < 2) return Double.NaN;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 position : positions) {
            if (position == null || !Double.isFinite(position.x)
                    || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return Double.NaN;
            minX = Math.min(minX, position.x); maxX = Math.max(maxX, position.x);
            minY = Math.min(minY, position.y); maxY = Math.max(maxY, position.y);
            minZ = Math.min(minZ, position.z); maxZ = Math.max(maxZ, position.z);
        }
        double dx = Math.max(0.0D, Math.max(minX - margin - camera.x, camera.x - maxX - margin));
        double dy = Math.max(0.0D, Math.max(minY - margin - camera.y, camera.y - maxY - margin));
        double dz = Math.max(0.0D, Math.max(minZ - margin - camera.z, camera.z - maxZ - margin));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
