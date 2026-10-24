package net.hulan.ivr.render_promotion.texture;

import net.hulan.ksd.utils.ClientUtilities;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Warms texture uploads and resource byte caches; ANTE owns model parsing and caching. */
public final class EyeCandyPreloader {

    private static final int MAX_PER_FRAME = 2;
    private static final Map<Object, Boolean> SEEN_ENTITIES = new WeakHashMap<>();
    private static final ArrayDeque<ResourceLocation> RESOURCE_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<ResourceLocation> TEXTURE_QUEUE = new ArrayDeque<>();
    private static final Set<ResourceLocation> REQUESTED_RESOURCES = new HashSet<>();
    private static final Set<ResourceLocation> REQUESTED_TEXTURES = new HashSet<>();
    private static final Set<ResourceLocation> FAILED_RESOURCES = new HashSet<>();

    private EyeCandyPreloader() { }

    public static synchronized void request(BlockEntity entity) {
        if (entity == null || SEEN_ENTITIES.put(entity, Boolean.TRUE) != null) {
            return;
        }
        Set<String> identifiers = new LinkedHashSet<>();
        collectIdentifiers(entity, identifiers, 0);
        for (String identifier : identifiers) enqueue(identifier);
    }

    public static synchronized void drain() {
        if (!Minecraft.getInstance().isSameThread()) return;
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        int submitted = 0;
        while (submitted++ < MAX_PER_FRAME && !TEXTURE_QUEUE.isEmpty()) {
            ResourceLocation texture = TEXTURE_QUEUE.removeFirst();
            if (!REQUESTED_TEXTURES.add(texture)) continue;
            try {
                textures.preload(texture, Util.backgroundExecutor());
            } catch (RuntimeException failure) {
                REQUESTED_TEXTURES.remove(texture);
            }
        }
        submitted = 0;
        while (submitted++ < MAX_PER_FRAME && !RESOURCE_QUEUE.isEmpty()) {
            ResourceLocation resource = RESOURCE_QUEUE.removeFirst();
            if (!REQUESTED_RESOURCES.add(resource) || FAILED_RESOURCES.contains(resource)) continue;
            CompletableFuture.runAsync(() -> warmResourceBytes(resource), Util.backgroundExecutor());
        }
    }

    public static synchronized void invalidate() {
        SEEN_ENTITIES.clear();
        RESOURCE_QUEUE.clear();
        TEXTURE_QUEUE.clear();
        REQUESTED_RESOURCES.clear();
        REQUESTED_TEXTURES.clear();
        FAILED_RESOURCES.clear();
    }

    private static void warmResourceBytes(ResourceLocation resourceLocation) {
        ClientUtilities clientUtilities = ClientUtilities.getInstance();
        try {
            Optional<Resource> resource = clientUtilities.getResource(resourceLocation);
            if (resource.isEmpty()) {
                synchronized (EyeCandyPreloader.class) {
                    FAILED_RESOURCES.add(resourceLocation);
                }
                return;
            }
            try (InputStream input = clientUtilities.openInputStream(resource.get())) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) >= 0) {
                }
            }
        } catch (RuntimeException | java.io.IOException failure) {
            synchronized (EyeCandyPreloader.class) {
                FAILED_RESOURCES.add(resourceLocation);
            }
        }
    }

    private static void collectIdentifiers(Object value, Set<String> output, int depth) {
        if (value == null || depth > 2 || value instanceof Number || value instanceof Boolean) return;
        if (value instanceof CharSequence text) {
            String identifier = text.toString().trim();
            if (!identifier.isEmpty() && identifier.length() < 512) output.add(identifier);
            return;
        }
        if (value instanceof ResourceLocation location) {
            output.add(location.toString());
            return;
        }
        for (String methodName : new String[]{"getPrefabId", "getProperties", "getModel", "getTexture"}) {
            try {
                Method method = value.getClass().getMethod(methodName);
                method.setAccessible(true);
                collectIdentifiers(method.invoke(value), output, depth + 1);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional ANTE releases expose different property accessors.
            }
        }
        for (java.lang.reflect.Field field : value.getClass().getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("model") && !name.contains("texture")
                    && !name.contains("prefab") && !name.contains("resource")) continue;
            try {
                field.setAccessible(true);
                collectIdentifiers(field.get(value), output, depth + 1);
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Optional ANTE releases may keep model metadata inaccessible.
            }
        }
    }

    private static void enqueue(String identifier) {
        if (RESOURCE_QUEUE.size() + TEXTURE_QUEUE.size() >= 1024) return;
        String normalized = identifier.replace('\\', '/');
        ResourceLocation location = parse(normalized);
        if (location == null) return;
        String path = location.getPath();
        if (path.endsWith(".png") || path.contains("textures/")) {
            ResourceLocation texture = path.contains("textures/")
                    ? new ResourceLocation(location.getNamespace(), path.substring(path.indexOf("textures/") + 9))
                    : location;
            if (!REQUESTED_TEXTURES.contains(texture)) TEXTURE_QUEUE.add(texture);
            return;
        }
        if (path.endsWith(".json") || path.endsWith(".obj") || path.endsWith(".nmb")
                || path.endsWith(".csv") || path.endsWith(".animated") || path.endsWith(".miproject")) {
            RESOURCE_QUEUE.add(location);
            return;
        }
        RESOURCE_QUEUE.add(new ResourceLocation(location.getNamespace(), "eyecandies/" + path + ".json"));
        RESOURCE_QUEUE.add(new ResourceLocation(location.getNamespace(), "eyecandies/" + path + ".obj"));
    }

    private static ResourceLocation parse(String value) {
        try {
            return value.contains(":") ? ResourceLocation.tryParse(value)
                    : new ResourceLocation("mtrsteamloco", value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
