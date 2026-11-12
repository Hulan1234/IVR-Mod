package net.hulan.ivr.mixin;

import net.hulan.ivr.DependenciesChecker;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Selects version-specific optional mixins from the classes available at runtime. */
public final class IVRMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("ivr");
    private static final String FABRIC_ANTE = "fabric/cn/zbx1425/mtrsteamloco/render/rail/";
    private static final String FORGE_ANTE = "forge/cn/zbx1425/mtrsteamloco/render/rail/";
    private static final Set<String> DIAGNOSTICS = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onLoad(String mixinPackage) {
        // This is the earliest registry probe available to IVR.  It may run
        // before all containers are visible, so IVR.refreshPresence() is also
        // called from the normal mod initializer and never caches a failure.
        DependenciesChecker.refreshPresence();
        LOGGER.info("IVR optional Mixin compatibility (static check only): MTR Fabric loop={}, MTR Forge loop={}, MSD={}, ANTE={}",
                renderTrainsStatus("(Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V"),
                renderTrainsStatus("(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"),
                msdStatus(),
                nteStatus());
        LOGGER.info("IVR MSD display bounds compatibility: RenderPIDS={}, RenderCustomTextSign={}",
                msdDisplayStatus("top.mcmtr.render.RenderPIDS"),
                msdDisplayStatus("top.mcmtr.render.RenderCustomTextSign"));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".RenderTrainsLoopOptimizeMixin")) {
            return hasRenderTrainsOverload("(Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V");
        }
        if (mixinClassName.endsWith(".RenderTrainsLoopOptimizeForgeMixin")) {
            return hasRenderTrainsOverload("(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V");
        }
        if (mixinClassName.endsWith(".RigidCatenaryMixin")) {
            return msdMixinAllowed() && hasSupportedRigidCatenary();
        }
        if (mixinClassName.endsWith(".CatenaryMixin")) {
            return msdMixinAllowed()
                    && hasSupportedSimpleCatenary("Catenary", "RenderCatenary", "renderCatenary");
        }
        if (mixinClassName.endsWith(".TransCatenaryMixin")) {
            return msdMixinAllowed()
                    && hasSupportedSimpleCatenary("TransCatenary", "RenderTransCatenary", "renderTransCatenary");
        }
        if (mixinClassName.endsWith(".RailChunkBaseVisibilityMixin")
                || mixinClassName.endsWith(".RailChunkBaseLifecycleManagerMixin")
                || mixinClassName.endsWith(".RailChunkBaseRendererMixin")
                || mixinClassName.endsWith(".ANTERailChunkRenderMixin")
                || mixinClassName.endsWith(".ANTERailChunkEnqueueMixin")
                || mixinClassName.endsWith(".ANTERailChunkLifecycleMixin")) {
            // 1.16.5 has no ANTE dependency and its client/render contracts
            // use the pre-PoseStack API.  Never let an optional ANTE mixin be
            // selected there, even if another bundled jar happens to expose
            // a similarly named class.
            return !isLegacy1165() && nteMixinAllowed() && isAnteTargetCompatible(targetClassName);
        }
        if (mixinClassName.endsWith(".DynamicTrainModelLoaderMixin")
                || mixinClassName.endsWith(".ANTEPreloadTextureMixin")) {
            boolean fabricTarget = targetClassName.startsWith("fabric.");
            String resource = targetClassName.replace('.', '/') + ".class";
            String returnType = fabricTarget
                    ? "Lnet/minecraft/class_2960;" : "Lnet/minecraft/resources/ResourceLocation;";
            return !isLegacy1165() && nteMixinAllowed()
                    && hasAnteTextureResolver(resource, returnType);
        }
        if (mixinClassName.endsWith(".BlockEntityEyeCandyRendererMixin")
                || mixinClassName.endsWith(".ANTEEyeCandyRenderMixin")) {
            return !isLegacy1165() && nteMixinAllowed() && hasSupportedEyeCandyRenderer(
                    targetClassName.replace('.', '/') + ".class");
        }
        if (mixinClassName.endsWith(".MSDDisplayBoundsMixin")) {
            // The renderer is optional and its private layout has changed between
            // MSD releases.  Do not let a missing Shadow field abort Mixin config
            // validation; skip this optimization when the complete layout is not
            // present and leave the normal MSD renderer active.
            return msdMixinAllowed() && hasSupportedMsdDisplayTarget(targetClassName);
        }
        if (mixinClassName.endsWith(".MSDRenderDataRevisionMixin")) {
            if (!msdMixinAllowed()) return false;
            return hasClass("top/mcmtr/data/CatenaryData.class")
                    || hasClass("top/mcmtr/data/TransCatenaryData.class")
                    || hasClass("top/mcmtr/data/RigidCatenaryData.class");
        }
        if (mixinClassName.endsWith(".TrainModelRenderCacheMixin")) {
            return hasSupportedTrainModelStageTarget(targetClassName);
        }
        if (mixinClassName.endsWith(".TrainSimulateOptimizeMixin")) {
            // mtrsteamloco rewrites simulateCar at the same priority and its
            // transformed method no longer contains IVR's renderCar call.
            // Keep MTR's native simulation when that optional mod is present.
            return DependenciesChecker.isExistenceKnown() && !DependenciesChecker.isNteExist()
                    && hasClass("mtr/data/TrainClient.class");
        }
        if (mixinClassName.endsWith(".RenderTrainsCallbackCacheMixin")) {
            boolean eligible = !isLegacy1165()
                    && targetClassName.equals("mtr.render.RenderTrains")
                    && hasRenderCallbackDispatch();
            if (!eligible) {
                disableRenderCallbackCache("RenderTrains callback contract did not match " + targetClassName);
            }
            return eligible;
        }
        return true;
    }

    private static boolean hasClass(String resource) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            return bytes != null;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 1.16.5 is deliberately excluded from the ANTE and callback adapters.
     * Prefer the game version reported by SharedConstants because Fabric's
     * named 1.16.5 development jar may expose the later PoseStack name even
     * though the runtime MTR contract is still the legacy one.
     */
    private static boolean isLegacy1165() {
        try {
            Class<?> sharedConstants = Class.forName("net.minecraft.SharedConstants", false,
                    IVRMixinPlugin.class.getClassLoader());
            Object gameVersion = sharedConstants.getMethod("getCurrentVersion").invoke(null);
            Object name = gameVersion.getClass().getMethod("getName").invoke(gameVersion);
            if (name != null && name.toString().startsWith("1.16")) return true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Resource checks below cover Forge's legacy class names when the
            // version singleton is not available during Mixin bootstrap.
        }
        return hasClass("com/mojang/blaze3d/matrix/MatrixStack.class")
                || hasClass("net/minecraft/client/renderer/IRenderTypeBuffer.class");
    }

    private static String renderTrainsStatus(String descriptor) {
        return !hasClass("mtr/render/RenderTrains.class") ? "mod absent"
                : hasRenderTrainsOverload(descriptor) ? "eligible" : "incompatible signature";
    }

    private static String nteStatus() {
        boolean present = DependenciesChecker.isNteExist();
        if (!present && DependenciesChecker.isExistenceKnown()) return "mod absent";
        if (!hasClass(FABRIC_ANTE + "RailChunkBase.class")
                && !hasClass(FORGE_ANTE + "RailChunkBase.class")) {
            return "mod absent";
        }
        return isAnteCompatible(FABRIC_ANTE) || isAnteCompatible(FORGE_ANTE)
                ? "eligible" : "incompatible signature";
    }

    private static String msdStatus() {
        boolean present = DependenciesChecker.isMsdExist();
        if (!present && DependenciesChecker.isExistenceKnown()) return "mod absent";
        return hasSupportedRigidCatenary() ? "eligible" : "incompatible signature";
    }

    private static String msdDisplayStatus(String targetClassName) {
        if (!DependenciesChecker.isMsdExist() && DependenciesChecker.isExistenceKnown()) return "mod absent";
        return hasSupportedMsdDisplayTarget(targetClassName) ? "eligible" : "incompatible signature";
    }

    private static boolean hasSupportedTrainModelStageTarget(String targetClassName) {
        if (!targetClassName.equals("mtr.model.ModelTrainBase")) return false;
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream("mtr/model/ModelTrainBase.class")) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String renderDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lmtr/data/NameColorDataBase;Lnet/minecraft/resources/ResourceLocation;IFFZIIZZZZZ)V";
            String stageDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lmtr/model/ModelTrainBase$RenderStage;IFFFFIIZZ)V";
            boolean renderMethod = target.methods.stream().anyMatch(method -> method.name.equals("render")
                    && method.desc.equals(renderDescriptor));
            if (!renderMethod) return false;
            for (org.objectweb.asm.tree.MethodNode method : target.methods) {
                if (!method.name.equals("render") || !method.desc.equals(renderDescriptor)) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("mtr/model/ModelTrainBase")
                            && call.name.equals("render")
                            && call.desc.equals(stageDescriptor)) return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean nteMixinAllowed() {
        // An unavailable registry is an unknown state during very early
        // bootstrap; class-shape checks remain the fallback in that case.
        return !DependenciesChecker.isExistenceKnown() || DependenciesChecker.isNteExist();
    }

    private static boolean msdMixinAllowed() {
        return !DependenciesChecker.isExistenceKnown() || DependenciesChecker.isMsdExist();
    }

    private static boolean isAnteCompatible(String prefix) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream(prefix + "RailChunkBase.class")) {
            if (bytes == null || !hasClass(prefix + "MeshBuildingRailChunk.class")
                    || !hasClass(prefix + "InstancedRailChunk.class")) {
                return false;
            }
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (FieldNode field : target.fields) {
                if (field.name.equals("boundingBox")
                        && (field.desc.equals("Lnet/minecraft/world/phys/AABB;")
                        || field.desc.equals("Lnet/minecraft/class_238;"))) {
                    return hasEnqueue(prefix + "MeshBuildingRailChunk.class")
                            && hasEnqueue(prefix + "InstancedRailChunk.class")
                            // The lifecycle callback intentionally omits the
                            // target method arguments.  ANTE 1.17+ passes a
                            // World while later releases may change that
                            // parameter, so validate the void return contract
                            // instead of requiring a zero-argument method.
                            && (hasVoidMethod(prefix + "RailChunkBase.class", "rebuildBuffer")
                            || hasVoidMethod(prefix + "MeshBuildingRailChunk.class", "rebuildBuffer"))
                            && (hasVoidMethod(prefix + "RailChunkBase.class", "rebuildBuffer")
                            || hasVoidMethod(prefix + "InstancedRailChunk.class", "rebuildBuffer"));
                }
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
        return false;
    }

    private static boolean isAnteTargetCompatible(String targetClassName) {
        if (targetClassName.startsWith("fabric.")) return isAnteCompatible(FABRIC_ANTE);
        if (targetClassName.startsWith("forge.")) return isAnteCompatible(FORGE_ANTE);
        return false;
    }

    private static boolean hasEnqueue(String resource) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytes == null) {
                return false;
            }
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            // ANTE's queue method receives the version-specific BatchManager
            // and ShaderProp.  Earlier checks looked for a no-arg method and
            // disabled this optimization on every current ANTE release.
            return target.methods.stream().anyMatch(method -> method.name.equals("enqueue")
                    && method.desc.endsWith(")V")
                    && method.desc.contains("sowcer/batch/BatchManager;")
                    && method.desc.contains("sowcer/batch/ShaderProp;"));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasVoidMethod(String resource, String name) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return target.methods.stream().anyMatch(method -> method.name.equals(name)
                    && method.desc.endsWith(")V"));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasAnteTextureResolver(String resource, String returnType) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String descriptor = "(Ljava/lang/String;Ljava/util/function/Function;)" + returnType;
            return target.methods.stream().anyMatch(method -> method.name.equals("resolveTexture")
                    && method.desc.equals(descriptor) && (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasSupportedEyeCandyRenderer(String resource) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (org.objectweb.asm.tree.MethodNode method : target.methods) {
                if (!method.name.equals("render")) continue;
                org.objectweb.asm.Type methodType = org.objectweb.asm.Type.getMethodType(method.desc);
                org.objectweb.asm.Type[] arguments = methodType.getArgumentTypes();
                if (methodType.getReturnType().getSort() != org.objectweb.asm.Type.VOID
                        || arguments.length != 6
                        || !arguments[0].getDescriptor().endsWith("/BlockEyeCandy$BlockEntityEyeCandy;")
                        || arguments[1].getSort() != org.objectweb.asm.Type.FLOAT
                        || !isPoseStack(arguments[2].getDescriptor())
                        || !isBufferSource(arguments[3].getDescriptor())
                        || arguments[4].getSort() != org.objectweb.asm.Type.INT
                        || arguments[5].getSort() != org.objectweb.asm.Type.INT) continue;
                return true;
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isPoseStack(String descriptor) {
        return descriptor.equals("Lcom/mojang/blaze3d/vertex/PoseStack;")
                || descriptor.equals("Lnet/minecraft/class_4587;");
    }

    private static boolean isBufferSource(String descriptor) {
        return descriptor.equals("Lnet/minecraft/client/renderer/MultiBufferSource;")
                || descriptor.equals("Lnet/minecraft/class_4597;");
    }

    private static boolean hasSupportedMsdDisplayTarget(String targetClassName) {
        if (!targetClassName.equals("top.mcmtr.render.RenderCustomTextSign")
                && !targetClassName.equals("top.mcmtr.render.RenderPIDS")) {
            return false;
        }
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream(targetClassName.replace('.', '/') + ".class")) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String[] required = {"startX", "startY", "startZ", "scale", "totalScaledWidth", "maxHeight"};
            for (String fieldName : required) {
                boolean present = target.fields.stream().anyMatch(field -> field.name.equals(fieldName)
                        && field.desc.equals("F"));
                if (!present) return false;
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasRenderTrainsOverload(String descriptor) {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream("mtr/render/RenderTrains.class")) {
            if (bytes == null) {
                return false;
            }
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return target.methods.stream().anyMatch(method -> method.name.equals("render") && method.desc.equals(descriptor));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasRenderCallbackDispatch() {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream("mtr/render/RenderTrains.class")) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            // Read method bodies as well as descriptors. A name-only check can
            // select this optional mixin after a compiler reorders lambda
            // indices, leaving every injection silently unmatched.
            new ClassReader(bytes).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean dispatch = false;
            boolean layer = false;
            for (org.objectweb.asm.tree.MethodNode method : target.methods) {
                if (method.name.equals("lambda$render$10")
                        && isRenderCallbackLambdaDescriptor(method.desc)) {
                    dispatch = hasInvoke(method, "java/util/function/BiConsumer", "accept",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V");
                }
                if (method.name.equals("lambda$render$11")
                        && isRenderLayerLambdaDescriptor(method.desc)) {
                    layer = hasRenderBufferInvoke(method);
                }
            }
            return dispatch && layer;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isRenderCallbackLambdaDescriptor(String descriptor) {
        return descriptor.equals("(Lnet/minecraft/class_4587;Lnet/minecraft/class_4588;Ljava/util/function/BiConsumer;)V")
                || descriptor.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/function/BiConsumer;)V");
    }

    private static boolean isRenderLayerLambdaDescriptor(String descriptor) {
        return descriptor.equals("(Lmtr/render/RenderTrains$QueuedRenderLayer;Lnet/minecraft/class_4597;Lnet/minecraft/class_4587;Lnet/minecraft/class_2960;Ljava/util/Set;)V")
                || descriptor.equals("(Lmtr/render/RenderTrains$QueuedRenderLayer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;Ljava/util/Set;)V");
    }

    private static boolean hasInvoke(org.objectweb.asm.tree.MethodNode method,
                                     String owner, String name, String descriptor) {
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRenderBufferInvoke(org.objectweb.asm.tree.MethodNode method) {
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("getBuffer")) continue;
            if (call.owner.equals("net/minecraft/class_4597")
                    && call.desc.equals("(Lnet/minecraft/class_1921;)Lnet/minecraft/class_4588;")) {
                return true;
            }
            if (call.owner.equals("net/minecraft/client/renderer/MultiBufferSource")
                    && call.desc.equals("(Lnet/minecraft/client/renderer/RenderType;)Lnet/minecraft/client/renderer/VertexConsumer;")) {
                return true;
            }
        }
        return false;
    }

    /** Disable optional callback caching when its runtime contract is unknown. */
    private static void disableRenderCallbackCache(String reason) {
        if (!DIAGNOSTICS.add("render-callback:" + reason)) return;
        LOGGER.warn("IVR RenderTrains callback cache disabled: {}", reason);
        invokeOptional("net.hulan.ivr.render_promotion.cache.RenderCallbackGeometryCache",
                "disable", new Class<?>[]{String.class}, new Object[]{reason});
        invokeOptional("net.hulan.ivr.render_promotion.mesh.GpuInstanceBatch",
                "disableAndClear", new Class<?>[0], new Object[0]);
    }

    private static void invokeOptional(String className, String methodName,
                                       Class<?>[] parameterTypes, Object[] arguments) {
        try {
            Class<?> type = Class.forName(className, false, IVRMixinPlugin.class.getClassLoader());
            type.getMethod(methodName, parameterTypes).invoke(null, arguments);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Optional client classes may not be visible during bootstrap.
        }
    }

    private static boolean hasSupportedRigidCatenary() {
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream("top/mcmtr/data/RigidCatenary.class")) {
            if (bytes == null) {
                return false;
            }
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return target.methods.stream().anyMatch(method -> {
                if (!method.name.equals("renderSegment")
                        || !method.desc.equals("(Ljava/util/List;Ltop/mcmtr/data/RigidCatenary$RenderRigidCatenary;)V")) {
                    return false;
                }
                for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("top/mcmtr/data/RigidCatenary$RenderRigidCatenary")
                            && call.name.equals("renderRigidCatenary")
                            && call.desc.equals("(DDDDDDDDDDDDDDDDDD)V")) {
                        return true;
                    }
                }
                return false;
            });
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /** 仅在普通或过渡接触网的回调签名匹配时启用对应 Mixin。 */
    private static boolean hasSupportedSimpleCatenary(String type, String callback, String methodName) {
        String owner = "top/mcmtr/data/" + type;
        String callbackOwner = owner + "$" + callback;
        try (InputStream bytes = IVRMixinPlugin.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            if (bytes == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(bytes).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return target.methods.stream().anyMatch(method -> {
                if (!method.name.equals("renderSegment")
                        || !method.desc.equals("(L" + callbackOwner + ";)V")) return false;
                for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(callbackOwner) && call.name.equals(methodName)
                            && call.desc.equals("(DDDDDDDDDDDD)V")) return true;
                }
                return false;
            });
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
