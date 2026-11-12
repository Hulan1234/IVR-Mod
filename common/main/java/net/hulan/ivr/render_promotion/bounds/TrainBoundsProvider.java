package net.hulan.ivr.render_promotion.bounds;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hulan.ivr.render_promotion.cache.VertexRecorder;
import net.hulan.ivr.render_promotion.visibility.TrainVisibility;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import mtr.data.TrainClient;
import mtr.model.ModelTrainBase;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 可选的 ANTE 模型边界适配器。只有观察到 ANTE 完整捕获结果及渲染器转向架几何后，
 * 才会发布包围盒。缺少可选类、捕获不完整或反射失败时，均允许原生列车绘制继续执行。
 */
public final class TrainBoundsProvider implements TrainVisibility.CarBoundsProvider {
    private static final TrainBoundsProvider INSTANCE = new TrainBoundsProvider();
    private static final int MAX_PENDING = 64;
    private static final int MAX_CONTEXTS = 256;
    private static final CaptureApi UNAVAILABLE_CAPTURE = new CaptureApi(null);
    private static final DoorApi UNAVAILABLE_DOOR = new DoorApi(null, null, null);
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new IdentityHashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHOD_CACHE = new IdentityHashMap<>();
    private static volatile CaptureApi captureApi;
    private static volatile DoorApi doorApi;
    private static volatile String resolvedPrefix;
    private static volatile Method trainProperties;
    private static volatile boolean trainPropertiesUnavailable;
    private final Map<TrainClient, Context> contexts = new IdentityHashMap<>();
    private final Map<TrainClient, LocalBounds> ready = new IdentityHashMap<>();
    private final Map<TrainClient, Context> failed = new IdentityHashMap<>();
    private final ArrayDeque<Context> pending = new ArrayDeque<>();
    private final Map<ModelTrainBase, Boolean> queued = new IdentityHashMap<>();
    private long generation;

    public static TrainBoundsProvider getInstance() { return INSTANCE; }

    public static void register() {
        TrainVisibility.getInstance().registerCarBoundsProvider(INSTANCE);
    }

    /** 在真实 renderCar 入口、可见性检测之前调用。 */
    public synchronized void observe(TrainClient train, ModelTrainBase model,
                                     Object renderer, String textureId) {
        if (train == null) return;
        if (model == null || model.getClass() != mtr.model.ModelKTrain.class) {
            invalidateTrain(train);
            return;
        }
        String mode = String.valueOf(train.transportMode);
        if (!mode.equals("TRAIN") && !mode.equals("LIGHT_RAIL")) {
            invalidateTrain(train);
            return;
        }
        Float bogiePosition = trainProperty(train, "bogiePosition");
        Boolean jacobs = trainBoolean(train, "isJacobsBogie");
        if (bogiePosition == null || jacobs == null || !Float.isFinite(bogiePosition)) {
            invalidateTrain(train);
            return;
        }
        Context context = contexts.get(train);
        if (context == null || context.model != model || context.renderer != renderer
                || !same(context.textureId, textureId) || context.bogiePosition != bogiePosition
                || context.jacobs != jacobs) {
            if (contexts.size() >= MAX_CONTEXTS) evictTrainEntry(train);
            context = new Context(train, model, renderer, textureId, bogiePosition, jacobs, generation);
            contexts.put(train, context);
            ready.remove(train);
            failed.remove(train);
        }
        if (!ready.containsKey(train) && failed.get(train) != context
                && !queued.containsKey(model) && pending.size() < MAX_PENDING) {
            queued.put(model, Boolean.TRUE);
            pending.addLast(context);
        }
    }

    /** 每帧完成一次 CPU 捕获；不会从工作线程或 GL 路径调用。 */
    public synchronized void beginFrame() {
        Context context = pending.pollFirst();
        if (context == null) return;
        queued.remove(context.model);
        LocalBounds bounds = capture(context);
        if (bounds != null && bounds.complete && context.generation == generation
                && contexts.get(context.train) == context) ready.put(context.train, bounds);
        else if (context.generation == generation && contexts.get(context.train) == context)
            failed.put(context.train, context);
    }

    @Override
    public synchronized AABB bounds(TrainClient train, int carIndex, Vec3 center,
                                    float yaw, float pitch) {
        Context context = contexts.get(train);
        LocalBounds local = context == null ? null : ready.get(train);
        if (local == null || !local.complete || center == null) return null;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        for (double[] p : local.points) {
            double y1 = p[1] * cp - p[2] * sp;
            double z1 = p[1] * sp + p[2] * cp;
            double x = p[0] * cy - z1 * sy + center.x;
            double z = p[0] * sy + z1 * cy + center.z;
            double y = y1 + center.y;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        return Double.isFinite(minX) ? new AABB(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    public synchronized void clear() {
        generation++;
        contexts.clear(); ready.clear(); failed.clear(); pending.clear(); queued.clear();
        captureApi = null;
        doorApi = null;
        resolvedPrefix = null;
        trainProperties = null;
        trainPropertiesUnavailable = false;
        synchronized (FIELD_CACHE) { FIELD_CACHE.clear(); }
        synchronized (METHOD_CACHE) { METHOD_CACHE.clear(); }
    }

    private void invalidateTrain(TrainClient train) {
        Context previous = contexts.remove(train);
        ready.remove(train);
        failed.remove(train);
        if (previous != null) {
            pending.removeIf(context -> context.train == train);
            queued.remove(previous.model);
        }
    }

    private void evictTrainEntry(TrainClient except) {
        for (TrainClient train : contexts.keySet()) {
            if (train != except) {
                invalidateTrain(train);
                return;
            }
        }
    }

    private LocalBounds capture(Context context) {
        try {
            CaptureApi api = getCaptureApi();
            if (api == null) return null;
            ResourceLocation texture = ResourceLocation.tryParse(context.textureId == null ? "minecraft:missingno" : context.textureId);
            if (texture == null) return null;
            Object result = invoke(api.captureModels, null, context.model, texture);
            if (result == null) return null;
            Object namedValue = invoke(findMethod(result.getClass(), "getNamedModels"), result);
            if (!(namedValue instanceof Map<?, ?> named)) return null;
            BoundsAccumulator out = new BoundsAccumulator();
            for (String key : new String[]{"body", "head", "end"}) {
                Object raw = named.get(key);
                if (!addRawModel(out, raw)) return null;
            }
            for (String key : new String[]{"headlight", "taillight"}) {
                Object raw = named.get(key);
                if (raw != null && !isEmptyRawModel(raw) && !addRawModel(out, raw)) return null;
            }
            if (!addBogie(out, context)) return null;
            if (!addDoorSweep(out, context.model)) return null;
            return out.finish();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private boolean addDoorSweep(BoundsAccumulator out, ModelTrainBase model) {
        try {
            DoorApi api = getDoorApi();
            if (api == null) return false;
            Object target = api.owner.cast(model);
            Object positionsValue = invoke(api.positions, target);
            if (!(positionsValue instanceof int[] positions)) return false;
            if (positions == null) return false;
            if (positions.length == 0) return true;
            PoseStack pose = new PoseStack();
            VertexRecorder recorder = new VertexRecorder();
            Float[] offsetExtents = doorOffsetExtents(model);
            if (offsetExtents == null) return false;
            float[] xOffsets = {offsetExtents[0], offsetExtents[1]};
            float[] zOffsets = {offsetExtents[2], offsetExtents[3]};
            for (ModelTrainBase.RenderStage stage : ModelTrainBase.RenderStage.values()) {
                for (int position : positions) {
                    for (float x : xOffsets) for (float z : zOffsets) {
                        if (!invokeVoid(api.render, target, pose, recorder, stage, 15728880, position,
                                false, x, -x, z, -z, false, false)) return false;
                    }
                }
            }
            if (!out.addRecorder(recorder)) return false;
            if (model.renderDoorOverlay) {
                Method details = findMethod(model.getClass(), "renderExtraDetails", PoseStack.class,
                        MultiBufferSource.class, int.class, int.class, boolean.class,
                        float.class, float.class, float.class, float.class);
                if (details == null) return false;
                MultiBufferSource buffers = (MultiBufferSource) Proxy.newProxyInstance(
                        MultiBufferSource.class.getClassLoader(), new Class[]{MultiBufferSource.class},
                        (proxy, method, args) -> method.getName().equals("getBuffer") ? recorder : null);
                for (float x : xOffsets) for (float z : zOffsets) {
                    if (!invokeVoid(details, model, pose, buffers, 15728880, 0, false, x, -x, z, -z)) return false;
                }
                if (!out.addRecorder(recorder)) return false;
            }
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private Float[] doorOffsetExtents(ModelTrainBase model) {
        try {
            Object doorMaxValue = invokeModelMethod(model, "getDoorMax");
            Object durationValue = invokeModelMethod(model, "getDoorDuration");
            if (!(doorMaxValue instanceof Number doorMaxNumber)
                    || !(durationValue instanceof Number durationNumber)) return null;
            float doorMax = doorMaxNumber.floatValue();
            float duration = durationNumber.floatValue();
            if (!Float.isFinite(doorMax) || !Float.isFinite(duration) || duration < 0.5F) return null;
            // MTR 1.18.2 的 DoorAnimationType 使用 smoothEnds，其余弦插值不会超出定义端点。
            // 枚举字节码中 X 端点位于 +/-1.01 范围内，Z 端点位于 +/-doorMax/duration
            // 范围内，另加最大固定偏移 3.5。KTrain 渲染器只平移车门 ModelMapper 部件。
            float zBound = Math.abs(doorMax / duration) + 4.0F;
            return new Float[]{-1.06F, 0.05F, -zBound, zBound};
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeModelMethod(Object target, String name) {
        return invoke(findMethod(target == null ? null : target.getClass(), name), target);
    }

    private boolean addBogie(BoundsAccumulator out, Context context) {
        if (context.renderer == null) return false;
        Object bogie = field(context.renderer, "MODEL_BOGIE");
        if (bogie == null) return false;
        Method render = findMethod(bogie.getClass(), "render", PoseStack.class, MultiBufferSource.class,
                int.class, int.class);
        if (render == null) return false;
        Float position = context.bogiePosition;
        Boolean jacobs = context.jacobs;
        if (position == null || jacobs == null || !Float.isFinite(position)) return false;
        for (float offset : jacobs ? new float[]{-position * 16, position * 16} : new float[]{-position * 16, 0, position * 16}) {
            VertexRecorder recorder = new VertexRecorder();
            MultiBufferSource buffers = (MultiBufferSource) Proxy.newProxyInstance(
                    MultiBufferSource.class.getClassLoader(), new Class[]{MultiBufferSource.class},
                    (proxy, method, args) -> method.getName().equals("getBuffer") ? recorder : null);
            double pivot = offset / 16.0D;
            PoseStack pose = new PoseStack(); pose.translate(0, pivot, 0);
            if (!invokeVoid(render, bogie, pose, buffers, 15728880, 0)) return false;
            java.util.List<net.hulan.ivr.render_promotion.cache.VertexMesh.Vertex> vertices = recorder.snapshot();
            if (vertices.isEmpty()) return false;
            double radiusSqr = 0;
            for (var vertex : vertices) {
                double dx = vertex.x(), dy = vertex.y() - pivot, dz = vertex.z();
                radiusSqr = Math.max(radiusSqr, dx * dx + dy * dy + dz * dz);
            }
            double radius = Math.sqrt(radiusSqr);
            // 同时纳入枢轴轴线方向和独立的转向架偏航角。
            double envelope = radius + Math.abs(pivot);
            out.add(-envelope, -envelope, -envelope);
            out.add(envelope, envelope, envelope);
        }
        return true;
    }

    private boolean isEmptyRawModel(Object raw) {
        Object meshes = field(raw, "meshList");
        return meshes instanceof Map<?, ?> map && map.isEmpty();
    }

    private boolean addRawModel(BoundsAccumulator out, Object raw) {
        if (raw == null) return false;
        Object meshes = field(raw, "meshList");
        if (!(meshes instanceof Map<?, ?> map) || map.isEmpty()) return false;
        boolean added = false;
        for (Object mesh : map.values()) {
            Object vertices = field(mesh, "vertices");
            if (!(vertices instanceof Iterable<?> values)) return false;
            for (Object vertex : values) {
                Object position = field(vertex, "position");
                double x = number(position, "x"), y = number(position, "y"), z = number(position, "z");
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
                out.add(x, y, z); added = true;
            }
        }
        return added;
    }

    private boolean same(String a, String b) {
        return Objects.equals(a, b);
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        Field f = findField(object.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(object);
        } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static double number(Object object, String method) {
        Object value = invoke(findMethod(object == null ? null : object.getClass(), method), object);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static Float trainProperty(TrainClient train, String name) {
        Object value = property(train, name);
        return value instanceof Number n ? n.floatValue() : null;
    }

    private static Boolean trainBoolean(TrainClient train, String name) {
        Object value = property(train, name);
        return value instanceof Boolean b ? b : null;
    }

    private static Object property(TrainClient train, String name) {
        if (train == null) return null;
        Method method = trainPropertiesMethod();
        Object props = invoke(method, null, train.trainId);
        return field(props, name);
    }

    private static String findPrefix() {
        String prefix = resolvedPrefix;
        if (prefix != null) return prefix.isEmpty() ? null : prefix;
        synchronized (TrainBoundsProvider.class) {
            prefix = resolvedPrefix;
            if (prefix != null) return prefix.isEmpty() ? null : prefix;
            for (String candidate : new String[]{"fabric.cn.zbx1425.mtrsteamloco.render.integration", "forge.cn.zbx1425.mtrsteamloco.render.integration"}) {
                try {
                    Class.forName(candidate + ".TrainModelCapture", false,
                            TrainBoundsProvider.class.getClassLoader());
                    resolvedPrefix = candidate;
                    return candidate;
                } catch (ClassNotFoundException | SecurityException | LinkageError ignored) {
                    // 再尝试一次另一种加载器/布局，然后缓存已停用标记。
                }
            }
            resolvedPrefix = "";
            return null;
        }
    }

    private static CaptureApi getCaptureApi() {
        CaptureApi current = captureApi;
        if (current != null) return current == UNAVAILABLE_CAPTURE ? null : current;
        synchronized (TrainBoundsProvider.class) {
            current = captureApi;
            if (current != null) return current == UNAVAILABLE_CAPTURE ? null : current;
            String prefix = findPrefix();
            if (prefix == null) {
                captureApi = UNAVAILABLE_CAPTURE;
                return null;
            }
            try {
                Class<?> capture = Class.forName(prefix + ".TrainModelCapture", false,
                        TrainBoundsProvider.class.getClassLoader());
                Method method = findMethod(capture, "captureModels", ModelTrainBase.class, ResourceLocation.class);
                current = method == null ? UNAVAILABLE_CAPTURE : new CaptureApi(method);
            } catch (RuntimeException | LinkageError | ClassNotFoundException unavailable) {
                current = UNAVAILABLE_CAPTURE;
            }
            captureApi = current;
            return current == UNAVAILABLE_CAPTURE ? null : current;
        }
    }

    private static DoorApi getDoorApi() {
        DoorApi current = doorApi;
        if (current != null) return current == UNAVAILABLE_DOOR ? null : current;
        synchronized (TrainBoundsProvider.class) {
            current = doorApi;
            if (current != null) return current == UNAVAILABLE_DOOR ? null : current;
            String prefix = findPrefix();
            if (prefix == null) {
                doorApi = UNAVAILABLE_DOOR;
                return null;
            }
            try {
                Class<?> owner = Class.forName(prefix + ".ModelSimpleTrainBaseAccessor", false,
                        TrainBoundsProvider.class.getClassLoader());
                Method positions = findMethod(owner, "invokeGetDoorPositions");
                Method render = findMethod(owner, "invokeRenderDoorPositions", PoseStack.class,
                        VertexConsumer.class, ModelTrainBase.RenderStage.class,
                        int.class, int.class, boolean.class, float.class, float.class,
                        float.class, float.class, boolean.class, boolean.class);
                current = positions == null || render == null ? UNAVAILABLE_DOOR : new DoorApi(owner, positions, render);
            } catch (RuntimeException | LinkageError | ClassNotFoundException unavailable) {
                current = UNAVAILABLE_DOOR;
            }
            doorApi = current;
            return current == UNAVAILABLE_DOOR ? null : current;
        }
    }

    private static Method trainPropertiesMethod() {
        Method current = trainProperties;
        if (current != null || trainPropertiesUnavailable) return current;
        synchronized (TrainBoundsProvider.class) {
            current = trainProperties;
            if (current != null || trainPropertiesUnavailable) return current;
            try {
                Class<?> registry = Class.forName("mtr.client.TrainClientRegistry", false,
                        TrainBoundsProvider.class.getClassLoader());
                current = findMethod(registry, "getTrainProperties", String.class);
            } catch (RuntimeException | LinkageError | ClassNotFoundException unavailable) {
                current = null;
            }
            trainProperties = current;
            trainPropertiesUnavailable = current == null;
            return current;
        }
    }

    private static Field findField(Class<?> type, String name) {
        if (type == null || name == null) return null;
        synchronized (FIELD_CACHE) {
            Map<String, Field> fields = FIELD_CACHE.computeIfAbsent(type, ignored -> new java.util.HashMap<>());
            if (fields.containsKey(name)) return fields.get(name);
            Field found = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    found = current.getDeclaredField(name);
                    found.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                    // 继续查找父类。
                } catch (RuntimeException | LinkageError inaccessible) {
                    break;
                }
            }
            fields.put(name, found);
            return found;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        if (type == null || name == null) return null;
        String signature = name + java.util.Arrays.toString(parameters);
        synchronized (METHOD_CACHE) {
            Map<String, Method> methods = METHOD_CACHE.computeIfAbsent(type, ignored -> new java.util.HashMap<>());
            if (methods.containsKey(signature)) return methods.get(signature);
            Method found = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    found = current.getDeclaredMethod(name, parameters);
                    found.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // 继续查找父类。
                } catch (RuntimeException | LinkageError inaccessible) {
                    break;
                }
            }
            if (found == null) {
                try {
                    found = type.getMethod(name, parameters);
                    found.setAccessible(true);
                } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                    // 公有方法和接口方法也不可用。
                }
            }
            methods.put(signature, found);
            return found;
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) return null;
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            rethrowFatal(failure.getCause());
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static boolean invokeVoid(Method method, Object target, Object... arguments) {
        if (method == null) return false;
        try {
            method.invoke(target, arguments);
            return true;
        } catch (InvocationTargetException failure) {
            rethrowFatal(failure.getCause());
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    private record Context(TrainClient train, ModelTrainBase model, Object renderer, String textureId,
                           float bogiePosition, boolean jacobs, long generation) {}
    private record CaptureApi(Method captureModels) {}
    private record DoorApi(Class<?> owner, Method positions, Method render) {}
    private record LocalBounds(double[][] points, boolean complete) {}
    private static final class BoundsAccumulator {
        private final java.util.List<double[]> points = new java.util.ArrayList<>();
        void add(double x, double y, double z) { points.add(new double[]{x, y, z}); }
        boolean addRecorder(VertexRecorder recorder) { for (var v : recorder.snapshot()) add(v.x(), v.y(), v.z()); return !recorder.snapshot().isEmpty(); }
        LocalBounds finish() { return points.isEmpty() ? null : new LocalBounds(points.toArray(new double[0][]), true); }
    }
}
