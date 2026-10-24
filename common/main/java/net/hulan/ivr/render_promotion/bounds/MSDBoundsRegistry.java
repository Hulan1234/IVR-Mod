package net.hulan.ivr.render_promotion.bounds;

import net.hulan.ivr.render_promotion.visibility.BlockEntityVisibility;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/** Optional registration never links MSD classes into a required client entrypoint. */
public final class MSDBoundsRegistry {

    private static final java.util.Map<BlockEntity, AABB> CAPTURED = new java.util.WeakHashMap<>();

    @SuppressWarnings("unchecked")
    public static void register() {
        for (String type : new String[]{
                "BlockYuuniPIDS$TileEntityPIDS", "BlockYuuniPIDS_2$TileEntityPIDS",
                "BlockYamanote4PIDS$TileEntityPIDS", "BlockYamanote5PIDS$TileEntityPIDS",
                "BlockYamanote6PIDS$TileEntityPIDS", "BlockYamanote7PIDS$TileEntityPIDS",
                "BlockYamanoteRailwaySign$TileEntityRailwaySign",
                "BlockCustomTextSignBase$TileEntityBlockCustomTextSignBase"}) {
            try {
                Class<?> target = Class.forName("top.mcmtr.blocks." + type, false, MSDBoundsRegistry.class.getClassLoader());
                if (BlockEntity.class.isAssignableFrom(target))
                    BlockEntityVisibility.getInstance().registerBoundsProvider((Class<BlockEntity>) target,
                            MSDBoundsRegistry::bounds);
            } catch (ReflectiveOperationException | LinkageError ignored) { }
        }
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
            } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        return CAPTURED.get(entity);
    }
    public static void capture(BlockEntity entity, double radius) {
        if (entity != null && Double.isFinite(radius) && radius > 0 && radius < 64)
            CAPTURED.put(entity, new AABB(entity.getBlockPos()).inflate(radius));
    }
}
