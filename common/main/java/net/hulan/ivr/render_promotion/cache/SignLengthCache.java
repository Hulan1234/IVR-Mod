package net.hulan.ivr.render_promotion.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import mtr.block.IBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Client render scans; periodic expiry also handles chunk packets bypassing setBlock. */
public final class SignLengthCache {

    private static final Map<Key, Integer> LENGTHS = new HashMap<>();
    private static BlockGetter cachedWorld;
    private static long expiresAt;

    public static int get(BlockGetter world, BlockPos pos, Direction facing, boolean right,
                          boolean route) {
        long now = System.nanoTime();
        if (cachedWorld != world || now >= expiresAt) {
            LENGTHS.clear();
            cachedWorld = world;
            expiresAt = now + 1_000_000_000L;
        }
        Key key = new Key(pos.asLong(), facing, right, route);
        Integer cached = LENGTHS.get(key);
        if (cached != null) return cached;
        Direction direction = right ? facing.getClockWise() : facing.getCounterClockWise();
        Block block = world.getBlockState(pos).getBlock();
        List<Key> visited = new ArrayList<>();
        int tail = -1;
        for (int offset = 0; ; offset++) {
            BlockPos check = pos.relative(direction, offset);
            BlockState state = world.getBlockState(check);
            if (state.getBlock() != block) break;
            IBlock.EnumSide side = route ? IBlock.getStatePropertySafe(state, IBlock.SIDE_EXTENDED) : null;
            if (route && offset != 0 && side == (right ? IBlock.EnumSide.LEFT : IBlock.EnumSide.RIGHT)) break;
            Key checkKey = new Key(check.asLong(), facing, right, route);
            Integer known = LENGTHS.get(checkKey);
            if (known != null) {
                tail = route ? known : known - 1;
                break;
            }
            visited.add(checkKey);
            if (route && side == (right ? IBlock.EnumSide.RIGHT : IBlock.EnumSide.LEFT)) break;
        }
        // Share the scanned suffix with every block visited, avoiding quadratic cold scans.
        for (int i = visited.size() - 1; i >= 0; i--) {
            tail++;
            LENGTHS.put(visited.get(i), route ? tail : tail + 1);
        }
        return LENGTHS.getOrDefault(key, route ? -1 : 1);
    }

    public static void invalidate() {
        LENGTHS.clear();
    }

    private record Key(long pos, Direction facing, boolean right, boolean route) {
    }
}
