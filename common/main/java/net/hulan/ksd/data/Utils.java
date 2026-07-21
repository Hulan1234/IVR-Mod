package net.hulan.ksd.data;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix4f;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;import java.util.stream.Collectors;

public class Utils {

    public static void renderScreenCircle(VertexConsumer consumer,
                                                          double centerX, double centerY,
                                                          float radius, int segments,
                                                          float borderThickness, int outlineColor) {
    // 创建单位矩阵（不做任何变换）
    Matrix4f identity = new Matrix4f();

    // 画外部（边框颜色）
    drawAbsoluteCircle(consumer, identity, (float) centerX, (float) centerY, radius, segments, outlineColor);

    // 画内部（白色）
    float innerRadius = radius - borderThickness;
    if (innerRadius > 0) {
        drawAbsoluteCircle(consumer, identity, (float) centerX, (float) centerY, innerRadius, segments, 0xFFFFFFFF);
    }
}

    private static void drawAbsoluteCircle(VertexConsumer consumer,
                                           Matrix4f matrix,
                                           float centerX, float centerY,
                                           float radius, int segments,
                                           int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        // 圆心
        consumer.vertex(matrix, centerX, centerY, 0f)
                .color(r, g, b, a)
                .endVertex();

        // 圆周顶点
        for (int i = 0; i <= segments; i++) {
            double angle = (i * 2.0 * Math.PI) / segments;
            float x = centerX + (float) (radius * Math.cos(angle));
            float y = centerY + (float) (radius * Math.sin(angle));
            consumer.vertex(matrix, x, y, 0f)
                    .color(r, g, b, a)
                    .endVertex();
        }
    }


    public static InteractionResult checkHoldingItem(Level world, Player player, Consumer<Item> callbackItem, Runnable callbackNoItem, Item... items) {
        Item holdingItem = null;
        for(Item item : items) {
            if (player.isHolding(item)) {
                holdingItem = item;
                break;
            }
        }
        if (holdingItem != null) {
            if (!world.isClientSide) {
                callbackItem.accept(holdingItem);
            }
            return InteractionResult.SUCCESS;
        } else if (callbackNoItem == null) {
            return InteractionResult.FAIL;
        } else if (!world.isClientSide) {
            callbackNoItem.run();
            return InteractionResult.CONSUME;
        } else {
            return InteractionResult.SUCCESS;
        }
    }

    public static <T> T getFilteredValueFromDataSet(Set<T> dataSet, Predicate<T> filter) {
        return getFilteredValueFromDataSetWithDefaultValue(dataSet, filter, null);
    }

    public static <T> T getFilteredValueFromDataSetWithDefaultValue(Set<T> dataSet, Predicate<T> filter, T defaultValue) {
        return dataSet.stream().filter(filter).findFirst().orElse(defaultValue);
    }

    public static <T> void executeFromDataSet(Set<T> dataSet, Predicate<T> filter, Consumer<T> action) {
        dataSet.stream().filter(filter).findFirst().ifPresent(action);
    }

    public static <T> List<T> getFilteredListFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).toList();
    }

    public static <T> Set<T> getFilteredSetFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).collect(Collectors.toSet());
    }

    public static <T, R> List<R> getSortedAndMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().sorted().map(mapper).toList();
    }
    
    public static <T, R> List<R> getMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).toList();
    }

    public static <T, R> Set<R> getMappedSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).collect(Collectors.toSet());
    }

    public static <T, R> List<R> getMappedAndNonNullListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return getMappedAndFilteredListFromDataCollection(dataCollection, mapper, Objects::nonNull);
    }

    public static <T, R> List<R> getMappedAndFilteredListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).toList();
    }

    public static <T, R> Set<R> getMappedAndFilteredSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).collect(Collectors.toSet());
    }
}
