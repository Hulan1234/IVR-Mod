package net.hulan.ivr.render_promotion.bounds;

import net.hulan.ivr.render_promotion.visibility.BlockEntityVisibility;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Bounds providers for MSD display BlockEntities (PIDS and signs). */
public final class MsdBlockEntityBoundsRegistry {

    private static final Logger LOGGER = LogManager.getLogger("ivr");
    private static final Map<BlockEntity, AABB> CAPTURED = new WeakHashMap<>();
    private static final Set<String> DIAGNOSTICS = ConcurrentHashMap.newKeySet();

    private MsdBlockEntityBoundsRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static void register() {
        int registered = 0;
        for (String type : new String[]{
                "BlockYuuniPIDS$TileEntityPIDS", "BlockYuuniPIDS_2$TileEntityPIDS",
                "BlockYamanote4PIDS$TileEntityPIDS", "BlockYamanote5PIDS$TileEntityPIDS",
                "BlockYamanote6PIDS$TileEntityPIDS", "BlockYamanote7PIDS$TileEntityPIDS",
                "BlockYamanoteRailwaySign$TileEntityRailwaySign",
                "BlockCustomTextSignBase$TileEntityBlockCustomTextSignBase"}) {
            try {
                Class<?> target = Class.forName("top.mcmtr.blocks." + type, false,
                        MsdBlockEntityBoundsRegistry.class.getClassLoader());
                if (BlockEntity.class.isAssignableFrom(target)) {
                    BlockEntityVisibility.getInstance().registerBoundsProvider((Class<BlockEntity>) target,
                            MsdBlockEntityBoundsRegistry::bounds);
                    registered++;
                } else {
                    diagnose("type:" + type, "MSD bounds target is not a BlockEntity: {}", type);
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // MSD is optional; absence of a particular display class is expected.
            }
        }
        diagnose("registered", "MSD BlockEntity bounds providers registered: {}", registered);
    }

    private static AABB bounds(BlockEntity entity) {
        if (entity.getClass().getName().contains("BlockYamanoteRailwaySign$")) {
            try {
                Object block = entity.getBlockState().getBlock();
                int length = block.getClass().getField("length").getInt(block);
                int xStart = ((Number) block.getClass().getMethod("getXStart").invoke(block)).intValue();
                // Sign glyphs use half-block slots; rotations cannot exceed this
                // triangle-inequality bound around the owning block center.
                return new AABB(entity.getBlockPos()).inflate(length * .5 + Math.abs(xStart / 16.0 - .5), .1, length * .5);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnose("sign-reflection:" + entity.getClass().getName(),
                        "MSD railway sign bounds reflection failed; using fail-open bounds: {}", failure.toString());
                return null;
            }
        }
        AABB captured;
        synchronized (CAPTURED) {
            captured = CAPTURED.get(entity);
        }
        if (captured == null) {
            diagnose("capture-miss:" + entity.getClass().getName(),
                    "MSD display bounds provider has no renderer capture; using fail-open visibility for {}",
                    entity.getClass().getName());
        }
        return captured;
    }

    /** Called from the optional MSD renderer hook on the client render thread. */
    public static void capture(BlockEntity entity, double radius) {
        if (entity == null) {
            diagnose("capture-null", "MSD display bounds hook received a null BlockEntity; keeping native rendering");
            return;
        }
        if (!Double.isFinite(radius) || radius <= 0 || radius >= 64) {
            synchronized (CAPTURED) {
                CAPTURED.remove(entity);
            }
            diagnose("capture-invalid", "MSD display bounds hook produced an invalid radius for {}: {}",
                    entity.getClass().getName(), radius);
            return;
        }
        synchronized (CAPTURED) {
            CAPTURED.put(entity, new AABB(entity.getBlockPos()).inflate(radius));
        }
        diagnose("capture-hook:" + entity.getClass().getName(),
                "MSD display bounds hook active for {} (BlockEntity world bounds captured)",
                entity.getClass().getName());
    }

    private static void diagnose(String key, String message, Object... arguments) {
        if (DIAGNOSTICS.add(key)) {
            LOGGER.info(message, arguments);
        }
    }
}
