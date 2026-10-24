package net.hulan.ivr.render_promotion.cache;

import net.minecraft.world.level.block.entity.BlockEntity;

public final class RenderDataRevision {

    private static long value;
    private static final java.util.Map<BlockEntity, Long> INSTANCES =
            new java.util.WeakHashMap<>();

    public static synchronized long current() {
        return value;
    }

    public static synchronized long bump() {
        return ++value;
    }

    public static synchronized long instance(BlockEntity entity) {
        return INSTANCES.getOrDefault(entity, 0L);
    }

    public static synchronized void markDirty(BlockEntity entity) {
        INSTANCES.put(entity, INSTANCES.getOrDefault(entity, 0L) + 1);
    }
}
