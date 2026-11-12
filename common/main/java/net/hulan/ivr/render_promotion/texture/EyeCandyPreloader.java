package net.hulan.ivr.render_promotion.texture;

import net.hulan.ksd.utils.ClientUtilities;
import net.hulan.ivr.render_promotion.cache.RenderDataRevision;
import net.hulan.ivr.render_promotion.cache.VertexMeshKey;
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

public final class EyeCandyPreloader {

    private static final int MAX_PER_FRAME = 2;
    private static final int MAX_TRACKED_RESOURCES = 4096;
    private static final int MAX_PENDING_REQUESTS = MAX_TRACKED_RESOURCES;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_NANOS = 1_000_000_000L;
    private static final Map<Object, Boolean> SEEN_ENTITIES = new WeakHashMap<>();
    private static final ArrayDeque<ResourceLocation> RESOURCE_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<ResourceLocation> TEXTURE_QUEUE = new ArrayDeque<>();
    private static final Set<ResourceLocation> REQUESTED_RESOURCES = new LinkedHashSet<>();
    private static final Set<ResourceLocation> REQUESTED_TEXTURES = new LinkedHashSet<>();
    private static final Set<ResourceLocation> FAILED_RESOURCES = new LinkedHashSet<>();
    private static final Set<ResourceLocation> FAILED_TEXTURES = new LinkedHashSet<>();
    private static final Map<ResourceLocation, RequestToken> PENDING_RESOURCES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, RequestToken> PENDING_TEXTURES = new LinkedHashMap<>();
    private static final Set<RequestToken> IN_FLIGHT_REQUESTS = new HashSet<>();
    private static final Map<ResourceLocation, Integer> RESOURCE_ATTEMPTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Integer> TEXTURE_ATTEMPTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Long> RESOURCE_RETRY_AT = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Long> TEXTURE_RETRY_AT = new LinkedHashMap<>();
    private static long generation;
    private static long requestId;

    private EyeCandyPreloader() { }

    public static synchronized void request(BlockEntity entity) {
        if (entity == null || SEEN_ENTITIES.containsKey(entity)) {
            return;
        }
        trimSeenEntities();
        SEEN_ENTITIES.put(entity, Boolean.TRUE);
        Set<String> identifiers = new LinkedHashSet<>();
        collectIdentifiers(entity, identifiers, 0);
        for (String identifier : identifiers) enqueue(identifier);
    }

    /** Returns a conservative per-entity geometry key for the two-frame stability probe. */
    public static synchronized Optional<VertexMeshKey> stableMeshKey(BlockEntity entity) {
        if (entity == null || entity.getBlockPos() == null) {
            return Optional.empty();
        }
        if (hasDynamicGeometrySignals(entity)) {
            return Optional.empty();
        }
        Set<String> identifiers = new TreeSet<>();
        collectIdentifiers(entity, identifiers, 0);
        if (identifiers.isEmpty()) {
            return Optional.empty();
        }
        Object world = entity.getLevel();
        if (world == null) {
            world = Minecraft.getInstance().level;
        }
        int worldIdentity = world == null ? 0 : System.identityHashCode(world);
        String model = "EyeCandy:" + worldIdentity + ':' + entity.getClass().getName() + ':'
                + entity.getBlockPos().asLong() + ':' + identifiers;
        if (model.length() > 16 * 1024) {
            return Optional.empty();
        }
        long geometryRevision = RenderDataRevision.instance(entity);
        ResourceLocation texture = textureHint(entity).orElse(null);
        if (texture == null) {
            return Optional.empty();
        }
        return Optional.of(new VertexMeshKey(model, model.hashCode(), RenderDataRevision.current(),
                geometryRevision, texture.toString(), "entity_cutout", 0));
    }

    /** Returns a unique texture hint only when the EyeCandy data exposes exactly one texture. */
    public static synchronized Optional<ResourceLocation> textureHint(BlockEntity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Set<ResourceLocation> textures = new LinkedHashSet<>();
        for (String methodName : new String[]{"getTexture"}) {
            try {
                Method method = entity.getClass().getMethod(methodName);
                method.setAccessible(true);
                Object value = method.invoke(entity);
                collectTextures(value, textures, 0);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        for (java.lang.reflect.Field field : entity.getClass().getDeclaredFields()) {
            if (!field.getName().toLowerCase(Locale.ROOT).contains("texture")) {
                continue;
            }
            try {
                field.setAccessible(true);
                collectTextures(field.get(entity), textures, 0);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return textures.size() == 1 ? Optional.of(textures.iterator().next()) : Optional.empty();
    }

    public static synchronized void drain() {
        if (!Minecraft.getInstance().isSameThread()) return;
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        int submitted = 0;
        while (submitted++ < MAX_PER_FRAME && !TEXTURE_QUEUE.isEmpty()) {
            ResourceLocation texture = TEXTURE_QUEUE.removeFirst();
            if (isPendingTextureCurrent(texture) || REQUESTED_TEXTURES.contains(texture)
                    || FAILED_TEXTURES.contains(texture)) continue;
            if (System.nanoTime() < TEXTURE_RETRY_AT.getOrDefault(texture, Long.MIN_VALUE)) {
                TEXTURE_QUEUE.addLast(texture);
                continue;
            }
            if (IN_FLIGHT_REQUESTS.size() >= MAX_PENDING_REQUESTS) {
                TEXTURE_QUEUE.addFirst(texture);
                break;
            }
            TEXTURE_ATTEMPTS.put(texture, TEXTURE_ATTEMPTS.getOrDefault(texture, 0) + 1);
            trimTracked(TEXTURE_ATTEMPTS);
            RequestToken token = nextToken();
            PENDING_TEXTURES.put(texture, token);
            IN_FLIGHT_REQUESTS.add(token);
            REQUESTED_TEXTURES.add(texture);
            trimRequestedTextures();
            try {
                CompletableFuture<Void> future = textures.preload(texture, Util.backgroundExecutor());
                future.whenComplete((ignored, failure) -> completeTexture(token, texture, failure));
            } catch (RuntimeException failure) {
                completeTexture(token, texture, failure);
            }
        }
        submitted = 0;
        while (submitted++ < MAX_PER_FRAME && !RESOURCE_QUEUE.isEmpty()) {
            ResourceLocation resource = RESOURCE_QUEUE.removeFirst();
            if (isPendingCurrent(resource) || FAILED_RESOURCES.contains(resource)) continue;
            if (System.nanoTime() < RESOURCE_RETRY_AT.getOrDefault(resource, Long.MIN_VALUE)) {
                RESOURCE_QUEUE.addLast(resource);
                continue;
            }
            if (IN_FLIGHT_REQUESTS.size() >= MAX_PENDING_REQUESTS) {
                RESOURCE_QUEUE.addFirst(resource);
                break;
            }
            RESOURCE_ATTEMPTS.put(resource, RESOURCE_ATTEMPTS.getOrDefault(resource, 0) + 1);
            trimTracked(RESOURCE_ATTEMPTS);
            REQUESTED_RESOURCES.add(resource);
            trimTracked(REQUESTED_RESOURCES);
            RequestToken token = nextToken();
            PENDING_RESOURCES.put(resource, token);
            IN_FLIGHT_REQUESTS.add(token);
            try {
                CompletableFuture.runAsync(() -> warmResourceBytes(resource), Util.backgroundExecutor())
                        .whenComplete((ignored, failure) -> completeResource(token, resource, failure));
            } catch (RuntimeException failure) {
                completeResource(token, resource, failure);
            }
        }
    }

    public static synchronized void invalidate() {
        SEEN_ENTITIES.clear();
        RESOURCE_QUEUE.clear();
        TEXTURE_QUEUE.clear();
        REQUESTED_RESOURCES.clear();
        REQUESTED_TEXTURES.clear();
        FAILED_RESOURCES.clear();
        FAILED_TEXTURES.clear();
        PENDING_RESOURCES.clear();
        PENDING_TEXTURES.clear();
        IN_FLIGHT_REQUESTS.clear();
        RESOURCE_ATTEMPTS.clear();
        TEXTURE_ATTEMPTS.clear();
        RESOURCE_RETRY_AT.clear();
        TEXTURE_RETRY_AT.clear();
        generation++;
    }

    private static void warmResourceBytes(ResourceLocation resourceLocation) {
        try {
            Optional<Resource> resource = ClientUtilities.getResource(resourceLocation);
            if (resource.isEmpty()) {
                throw new java.io.IOException("Resource not found: " + resourceLocation);
            }
            try (InputStream input = ClientUtilities.openInputStream(resource.get())) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) >= 0) {
                }
            }
        } catch (RuntimeException | java.io.IOException failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    private static synchronized void completeResource(RequestToken token, ResourceLocation resourceLocation,
                                                       Throwable failure) {
        if (!IN_FLIGHT_REQUESTS.remove(token)) return;
        if (!isCurrent(PENDING_RESOURCES, resourceLocation, token)) return;
        PENDING_RESOURCES.remove(resourceLocation);
        if (failure != null) {
            REQUESTED_RESOURCES.remove(resourceLocation);
            int attempts = RESOURCE_ATTEMPTS.getOrDefault(resourceLocation, 0);
            if (attempts < MAX_ATTEMPTS) {
                RESOURCE_RETRY_AT.put(resourceLocation, System.nanoTime() + RETRY_DELAY_NANOS);
                queueRetry(RESOURCE_QUEUE, resourceLocation);
                trimTracked(RESOURCE_RETRY_AT);
            } else {
                RESOURCE_ATTEMPTS.remove(resourceLocation);
                RESOURCE_RETRY_AT.remove(resourceLocation);
                FAILED_RESOURCES.add(resourceLocation);
                trimTracked(FAILED_RESOURCES);
            }
        } else {
            RESOURCE_ATTEMPTS.remove(resourceLocation);
            RESOURCE_RETRY_AT.remove(resourceLocation);
        }
    }

    private static synchronized void completeTexture(RequestToken token, ResourceLocation texture,
                                                       Throwable failure) {
        if (!IN_FLIGHT_REQUESTS.remove(token)) return;
        if (!isCurrent(PENDING_TEXTURES, texture, token)) return;
        PENDING_TEXTURES.remove(texture);
        if (failure != null) {
            REQUESTED_TEXTURES.remove(texture);
            int attempts = TEXTURE_ATTEMPTS.getOrDefault(texture, 0);
            if (attempts < MAX_ATTEMPTS) {
                TEXTURE_RETRY_AT.put(texture, System.nanoTime() + RETRY_DELAY_NANOS);
                queueRetry(TEXTURE_QUEUE, texture);
                trimTracked(TEXTURE_RETRY_AT);
            } else {
                TEXTURE_ATTEMPTS.remove(texture);
                TEXTURE_RETRY_AT.remove(texture);
                FAILED_TEXTURES.add(texture);
                trimTracked(FAILED_TEXTURES);
            }
        } else {
            REQUESTED_TEXTURES.add(texture);
            trimRequestedTextures();
            TEXTURE_ATTEMPTS.remove(texture);
            TEXTURE_RETRY_AT.remove(texture);
            FAILED_TEXTURES.remove(texture);
        }
    }

    private static synchronized RequestToken nextToken() {
        return new RequestToken(generation, ++requestId);
    }

    private static synchronized boolean isCurrent(Map<ResourceLocation, RequestToken> pending,
                                                  ResourceLocation location, RequestToken token) {
        return token.generation == generation && pending.get(location) == token;
    }

    private static synchronized boolean isPendingCurrent(ResourceLocation resource) {
        RequestToken token = PENDING_RESOURCES.get(resource);
        return token != null && token.generation == generation;
    }

    private static synchronized boolean isPendingTextureCurrent(ResourceLocation texture) {
        RequestToken token = PENDING_TEXTURES.get(texture);
        return token != null && token.generation == generation;
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

    private static boolean hasDynamicGeometrySignals(BlockEntity entity) {
        try {
            return hasDynamicGeometrySignals(entity.getClass(), new HashSet<>());
        } catch (LinkageError | RuntimeException ignored) {
            return true;
        }
    }

    private static boolean hasDynamicGeometrySignals(Class<?> type, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) {
            return false;
        }
        if (type.isAnonymousClass() || type.isSynthetic()) {
            return true;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (containsDynamicGeometryToken(method.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (containsDynamicGeometryToken(field.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (hasDynamicGeometrySignals(interfaceType, visited)) {
                return true;
            }
        }
        return hasDynamicGeometrySignals(type.getSuperclass(), visited);
    }

    private static boolean containsDynamicGeometryToken(String name) {
        return name.contains("animat") || name.contains("script") || name.contains("random")
                || name.contains("camera") || name.contains("bone") || name.contains("uv")
                || name.contains("tick") || name.contains("time") || name.contains("frame")
                || name.contains("phase") || name.contains("seed") || name.contains("state")
                || name.contains("offset") || name.contains("rotation") || name.contains("noise")
                || name.contains("variant") || name.contains("world") || name.contains("level")
                || name.contains("light") || name.contains("weather") || name.contains("biome")
                || name.contains("shader") || name.contains("material") || name.contains("texture")
                || name.contains("atlas") || name.contains("sampler") || name.contains("color")
                || name.contains("tint") || name.contains("alpha") || name.contains("fog")
                || name.contains("emissive") || name.contains("rendertype")
                || name.contains("transform");
    }

    private static void collectTextures(Object value, Set<ResourceLocation> output, int depth) {
        if (value == null || depth > 2) {
            return;
        }
        if (value instanceof ResourceLocation location) {
            output.add(location);
            return;
        }
        if (value instanceof CharSequence text) {
            try {
                output.add(new ResourceLocation(text.toString()));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object entry : collection) {
                collectTextures(entry, output, depth + 1);
            }
        }
    }

    private static synchronized void enqueue(String identifier) {
        String normalized = identifier.replace('\\', '/');
        ResourceLocation location = parse(normalized);
        if (location == null) return;
        String path = location.getPath();
        if (path.endsWith(".png") || path.contains("textures/")) {
            ResourceLocation texture = path.contains("textures/")
                    ? new ResourceLocation(location.getNamespace(), path.substring(path.indexOf("textures/") + 9))
                    : location;
            if (RESOURCE_QUEUE.size() + TEXTURE_QUEUE.size() >= 1024) return;
            FAILED_TEXTURES.remove(texture);
            if (!TEXTURE_RETRY_AT.containsKey(texture)) TEXTURE_ATTEMPTS.remove(texture);
            if (!isPendingTextureCurrent(texture)
                    && !REQUESTED_TEXTURES.contains(texture)
                    && !TEXTURE_QUEUE.contains(texture)) TEXTURE_QUEUE.add(texture);
            return;
        }
        if (path.endsWith(".json") || path.endsWith(".obj") || path.endsWith(".nmb")
                || path.endsWith(".csv") || path.endsWith(".animated") || path.endsWith(".miproject")) {
            enqueueResource(location);
            return;
        }
        ResourceLocation json = new ResourceLocation(location.getNamespace(), "eyecandies/" + path + ".json");
        enqueueResource(json);
        ResourceLocation obj = new ResourceLocation(location.getNamespace(), "eyecandies/" + path + ".obj");
        enqueueResource(obj);
    }

    private static synchronized void enqueueResource(ResourceLocation resource) {
        FAILED_RESOURCES.remove(resource);
        if (isPendingCurrent(resource) || REQUESTED_RESOURCES.contains(resource)
                || RESOURCE_QUEUE.contains(resource)) return;
        if (RESOURCE_QUEUE.size() + TEXTURE_QUEUE.size() >= 1024) return;
        if (!RESOURCE_RETRY_AT.containsKey(resource)) RESOURCE_ATTEMPTS.remove(resource);
        RESOURCE_QUEUE.add(resource);
    }

    private static synchronized void queueRetry(ArrayDeque<ResourceLocation> queue,
                                                ResourceLocation resource) {
        if (RESOURCE_QUEUE.size() + TEXTURE_QUEUE.size() >= 1024 || queue.contains(resource)) return;
        queue.addLast(resource);
    }

    private static synchronized void trimTracked(Set<ResourceLocation> values) {
        while (values.size() > MAX_TRACKED_RESOURCES) values.remove(values.iterator().next());
    }

    private static synchronized void trimTracked(Map<ResourceLocation, ?> values) {
        while (values.size() > MAX_TRACKED_RESOURCES) values.remove(values.keySet().iterator().next());
    }

    private static synchronized void trimRequestedTextures() {
        while (REQUESTED_TEXTURES.size() > MAX_TRACKED_RESOURCES) {
            ResourceLocation evict = null;
            for (ResourceLocation texture : REQUESTED_TEXTURES) {
                if (!isPendingTextureCurrent(texture)) {
                    evict = texture;
                    break;
                }
            }
            if (evict == null) break;
            REQUESTED_TEXTURES.remove(evict);
        }
    }

    private static synchronized void trimSeenEntities() {
        while (SEEN_ENTITIES.size() >= MAX_TRACKED_RESOURCES) {
            Iterator<Object> iterator = SEEN_ENTITIES.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static ResourceLocation parse(String value) {
        try {
            return value.contains(":") ? ResourceLocation.tryParse(value)
                    : new ResourceLocation("mtrsteamloco", value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record RequestToken(long generation, long requestId) { }
}
