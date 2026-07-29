package net.hulan.ksd.data;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import mtr.data.EnumHelper;
import mtr.data.RouteType;import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class Utils {

    public static final RouteType KCR_CLASSICAL = EnumHelper.valueOf(RouteType.NORMAL, "KCR_CLASSICAL");
    public static final RouteType KCR_MODERN = EnumHelper.valueOf(RouteType.NORMAL, "KCR_MODERN");
    public static final RouteType KCR_LIGHT_RAIL = EnumHelper.valueOf(RouteType.NORMAL, "KCR_LIGHT_RAIL");
    private static Utils instance;
    
    public static Utils getInstance() {
        if (instance == null) {
            String version = getMinecraftVersion();
            String className = "Utils_" + version;
            Utils tempInstance = new NullUtils();
            try {
                Class<?> clazz = Class.forName("net.hulan.ksd.data." + className);
                tempInstance = (Utils) clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException |
                     NoSuchMethodException e) {
                e.printStackTrace();
            }
            instance = tempInstance;
        }
        return instance;
    }

    static String getMinecraftVersion() {
        String originVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(ModContainer::getMetadata)
                .map(ModMetadata::getVersion)
                .map(Version::getFriendlyString)
                .orElse("1.18.2");
        if (originVersion.contains("1.16")) {
            return "1_16_5";
        } else if (originVersion.contains("1.17")) {
            return "1_17_2";
        } else if (originVersion.equals("1.19.2")
                || originVersion.equals("1.19.1")
                || originVersion.equals("1.19")) {
            return "1_19_2";
        } else if (originVersion.equals("1.19.3")
                || originVersion.equals("1.19.4")) {
            return "1_19_4";
        }
        return "1_18_2";
    }

    public void drawStationCircle(PoseStack matrices,
                                  float centerX, float centerY,
                                  float radius, int segments,
                                  float borderThickness, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = ((color >> 24) & 0xFF) / 255f;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        beginDrawingCircle(buffer);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float innerRadius = radius - borderThickness;
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float outerX = centerX + cos * radius;
            float outerY = centerY + sin * radius;
            float innerX = centerX + cos * innerRadius;
            float innerY = centerY + sin * innerRadius;
            buffer.vertex(matrices.last().pose(), outerX, outerY, 0)
                    .color(r, g, b, a)
                    .endVertex();
            buffer.vertex(matrices.last().pose(), innerX, innerY, 0)
                    .color(r, g, b, a)
                    .endVertex();
        }
        tesselator.end();
        RenderSystem.disableBlend();
    }

    public abstract void beginDrawingCircle(BufferBuilder buffer);

    public InteractionResult checkHoldingItem(Level world, Player player, Consumer<Item> callbackItem, Runnable callbackNoItem, Item... items) {
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

    public <T> T getFilteredValueFromDataSet(Set<T> dataSet, Predicate<T> filter) {
        return getFilteredValueFromDataSetWithpublicValue(dataSet, filter, null);
    }

    public <T> T getFilteredValueFromDataSetWithpublicValue(Set<T> dataSet, Predicate<T> filter, T publicValue) {
        return dataSet.stream().filter(filter).findFirst().orElse(publicValue);
    }

    public <T> void executeFromDataSet(Set<T> dataSet, Predicate<T> filter, Consumer<T> action) {
        dataSet.stream().filter(filter).findFirst().ifPresent(action);
    }

    public <T> List<T> getFilteredListFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).toList();
    }

    public <T> Set<T> getFilteredSetFromDataCollection(Collection<T> dataCollection, Predicate<T> filter) {
        return dataCollection.stream().filter(filter).collect(Collectors.toSet());
    }

    public <T, R> List<R> getSortedAndMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().sorted().map(mapper).toList();
    }
    
    public <T, R> List<R> getMappedListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).toList();
    }

    public <T, R> Set<R> getMappedSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return dataCollection.stream().map(mapper).collect(Collectors.toSet());
    }

    public <T, R> List<R> getMappedAndNonNullListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper) {
        return getMappedAndFilteredListFromDataCollection(dataCollection, mapper, Objects::nonNull);
    }

    public <T, R> List<R> getMappedAndFilteredListFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).toList();
    }

    public <T, R> Set<R> getMappedAndFilteredSetFromDataCollection(Collection<T> dataCollection, Function<T, R> mapper, Predicate<R> filter) {
        return dataCollection.stream().map(mapper).filter(filter).collect(Collectors.toSet());
    }

    static class NullUtils extends Utils {

        public void beginDrawingCircle(BufferBuilder buffer) {

        }
    }
}
