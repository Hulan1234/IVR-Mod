package net.hulan.ksd.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.SelectableMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDAreaBase;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.path.KSDRoutePathGenerator;
import net.hulan.ksd.utils.RailDataUtilities;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.NotNull;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public class KSDWidgetMap implements WidgetMapper, SelectableMapper, GuiEventListener, IGui {

    public MapType mapType;
    private int x;
    private int y;
    private int width;
    private int height;
    private double scale;
    private double targetScale;
    private long lastCameraUpdateNanos;
    private boolean zoomAnchorActive;
    private double zoomAnchorScreenX;
    private double zoomAnchorScreenY;
    private double zoomAnchorWorldA;
    private double zoomAnchorWorldB;
    private final BetterScrollBar scaleScrollBar;
    private double centerX;
    private double centerY;
    private double targetCenterX;
    private double targetCenterY;
    private int[] rowColorBuffer = new int[0];
    private Tuple<Integer, Integer> drawArea1;
    private Tuple<Integer, Integer> drawArea2;
    private MapState mapState;
    // 是否显示铁轨走向（showRailMap 模式）
    private boolean showRailMap;
    private final TransportMode transportMode;
    private final OnDrawCorners onDrawCorners;
    private final Runnable onDrawCornersMouseRelease;
    private final Consumer<Long> onClickAddPlatformToRoute;
    private final Consumer<SavedRailBase> onClickEditSavedRail;
    private final BiFunction<Double, Double, Boolean> isRestrictedMouseArea;
    private final ClientLevel world;
    private final LocalPlayer player;
    private final Font textRenderer;
    // 1 个方块在 screen 上的基准像素数：scale = 1 时每个方块 BLOCK_PIXEL_SIZE 像素
    // 暂时保留该常量并把基准恢复为 1（scale=1 时地图范围与未放大时一致），后续按测试结果再调
    private static final double BLOCK_PIXEL_SIZE = 1.0F;
    // 方块达到该像素尺寸时才绘制深色描边，避免缩小时糊成一片
    private static final int BLOCK_BORDER_MIN_PIXELS = 4;
    // 描边宽度占方块边长的比例（1/32）
    private static final double BLOCK_BORDER_RATIO = 32.0F;
    // 描边亮度比例：和高度明暗一样按逐通道乘法略微压暗（0.85 即比填充暗 15%）
    private static final double BLOCK_BORDER_DARKEN = 0.85F;
    // X_Z 高度明暗：0 ~ HEIGHT_SHADE_MAX_Y 高度对应 1.0 ~ (1 - HEIGHT_SHADE_STRENGTH) 的亮度，范围外取极值
    private static final double HEIGHT_SHADE_MAX_Y = 128.0F;
    private static final double HEIGHT_SHADE_STRENGTH = 0.5F;
    // 缩放范围：中间为 1.00x，最大 16.00x，最小 1/16.00x（对数刻度下 1.00x 正好在中间）
    private static final double SCALE_UPPER_LIMIT = 16.0F;
    private static final double SCALE_LOWER_LIMIT = 1.0F / SCALE_UPPER_LIMIT;
    // 滚轮每格的缩放倍率，配合下面的平滑动画避免一次滚动就突变成两倍
    private static final double ZOOM_STEP = 1.25F;
    // 缩放动画的指数收敛速度（每秒），数值越大越快到达目标倍率
    private static final double SCALE_SMOOTHING_RATE = 20.0F;
    // 平移动画的指数收敛速度（每秒），比缩放稍快，避免拖动时明显跟手延迟
    private static final double CENTER_SMOOTHING_RATE = 30.0F;
    // 与目标倍率的对数差小于该值时直接吸附，避免无限逼近造成抖动
    private static final double SCALE_SNAPPING_THRESHOLD = 1.0E-3F;
    // 中心点与目标点的屏幕距离小于该值（像素）时吸附，避免无限逼近
    private static final double CENTER_SNAPPING_PIXELS = 0.02F;
    // 地图右侧缩放滑动条的几何
    private static final int SCALE_BAR_WIDTH = 6;
    private static final int SCALE_BAR_HEIGHT = 120;
    private static final int SCALE_BAR_MARGIN = 10;
    // 玩家头像的绘制尺寸（像素）
    private static final int PLAYER_AVATAR_SIZE = 12;
    // showRailMap 模式：每条线路 2 像素宽、未接入线路的轨道用灰色
    private static final int RAIL_STRIPE_WIDTH = 2;
    private static final int RAIL_GRAY = 0xFF808080;

    public KSDWidgetMap(MapType mapType, TransportMode transportMode, OnDrawCorners onDrawCorners, Runnable onDrawCornersMouseRelease, Consumer<Long> onClickAddPlatformToRoute, Consumer<SavedRailBase> onClickEditSavedRail, BiFunction<Double, Double, Boolean> isRestrictedMouseArea) {
        this.mapType = mapType;
        this.transportMode = transportMode;
        this.onDrawCorners = onDrawCorners;
        this.onDrawCornersMouseRelease = onDrawCornersMouseRelease;
        this.onClickAddPlatformToRoute = onClickAddPlatformToRoute;
        this.onClickEditSavedRail = onClickEditSavedRail;
        this.isRestrictedMouseArea = isRestrictedMouseArea;
        Minecraft minecraftClient = Minecraft.getInstance();
        world = minecraftClient.level;
        player = minecraftClient.player;
        textRenderer = minecraftClient.font;
        if (player == null) {
            centerX = 0.0F;
            centerY = 0.0F;
        } else {
            centerX = mapType != MapType.Z_Y ? player.getX() : player.getZ();
            centerY = mapType != MapType.X_Z ? player.getY() : player.getZ();
        }
        scale = 1.0F / BLOCK_PIXEL_SIZE;
        targetScale = 1.0F / BLOCK_PIXEL_SIZE;
        targetCenterX = centerX;
        targetCenterY = centerY;
        scaleScrollBar = new BetterScrollBar(0.0, this::onScaleScrollBarChanged);
    }

    public void render(@NotNull PoseStack matrices, int mouseX, int mouseY, float delta) {
        if (player == null) {
            return;
        }
        updateCameraAnimation();
        switch (mapType) {
            case X_Y -> renderXY(matrices, mouseX, mouseY);
            case X_Z -> renderXZ(matrices, mouseX, mouseY);
            case Z_Y -> renderYZ(matrices, mouseX, mouseY);
        }
        renderScaleScrollBar(matrices, mouseX, mouseY);
    }

    /** X-Y 平面：侧视，固定玩家所在 Z。 */
    private void renderXY(@NotNull PoseStack matrices, int mouseX, int mouseY) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        UtilitiesClient.beginDrawingRectangle(buffer);
        RenderSystem.enableBlend();
        Tuple<Integer, Integer> topLeft = cordsToWorldPos(0, 0);
        Tuple<Integer, Integer> bottomRight = cordsToWorldPos(width, height);
        int increment = getCellIncrement();
        boolean drawBlockBorder = getEffectiveScale() >= BLOCK_BORDER_MIN_PIXELS;
        if (world != null) {
            int rowCells = getCellCount(topLeft.getB(), bottomRight.getB(), increment);
            int[] colors = getRowColorBuffer(rowCells);
            for(int i = topLeft.getA(); i <= bottomRight.getA(); i += increment) {
                int cell = 0;
                for(int j = topLeft.getB(); j >= bottomRight.getB(); j -= increment) {
                    colors[cell++] = getTerrainColor(i, j, Mth.floor(player.getZ()));
                }
                drawTerrainRowDescending(buffer, i, topLeft.getB(), increment, cell, colors, drawBlockBorder);
            }
        }
        Tuple<Double, Double> mouseWorldPos = cordsToWorldPos((double) (mouseX - x), mouseY - y);
        try {
            KSDClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) ->
                    drawRectangleFromWorldCords(
                            buffer,
                            platformPos.getX(),
                            platformPos.getY(),
                            platformPos.getX() + 1,
                            platformPos.getY() - 1,
                            -1));
            for(KSDStation station : KSDClientData.STATIONS) {
                if (KSDAreaBase.nonNullCorners(station) && RailDataUtilities.containsZ(station, player.getZ())) {
                    drawStationRectangle(buffer, station, getEffectiveScale());
                }
            }
            if (isMouseOver(mouseX, mouseY)) {
                mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCords(buffer, x1, z1, x2, z2, -1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mapState == MapState.EDITING_AREA && drawArea1 != null && drawArea2 != null) {
            drawRectangleFromWorldCords(buffer, drawArea1, drawArea2, Integer.MAX_VALUE);
        }
        tesselator.end();
        RenderSystem.disableBlend();
        UtilitiesClient.finishDrawingRectangle();
        drawPlayerMarker(matrices, player.getX(), player.getY());
        drawEditingStateText(matrices);
        drawSavedRailNames(matrices);
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        for (KSDStation station : KSDClientData.STATIONS) {
            if (canDrawAreaText(station) && RailDataUtilities.containsZ(station, player.getZ())) {
                BlockPos pos = station.getCenter();
                String stationString = String.format("%s|(%s)", station.name, Text.translatable("gui.mtr.zone_number", station.zone).getString());
                drawFromWorldCords(pos.getX(), pos.getY(), (x1, y1) -> IDrawing.drawStringWithFont(matrices, this.textRenderer, immediate, stationString, RenderUtilities.snapToPixel((float) this.x + x1.floatValue()), RenderUtilities.snapToPixel((float) this.y + y1.floatValue()), 15728880));
            }
        }
        immediate.endBatch();
        drawMousePosition(matrices, "X-Y", mouseWorldPos);
    }

    /** X-Z 平面：俯视，固定玩家所在 Y，并按方块高度做明暗渐变。 */
    private void renderXZ(@NotNull PoseStack matrices, int mouseX, int mouseY) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        UtilitiesClient.beginDrawingRectangle(buffer);
        RenderSystem.enableBlend();
        Tuple<Integer, Integer> topLeft = cordsToWorldPos(0, 0);
        Tuple<Integer, Integer> bottomRight = cordsToWorldPos(width, height);
        int increment = getCellIncrement();
        boolean drawBlockBorder = getEffectiveScale() >= BLOCK_BORDER_MIN_PIXELS;
        if (world != null) {
            int rowCells = getCellCount(topLeft.getB(), bottomRight.getB(), increment);
            int[] colors = getRowColorBuffer(rowCells);
            for(int i = topLeft.getA(); i <= bottomRight.getA(); i += increment) {
                int cell = 0;
                for(int j = topLeft.getB(); j <= bottomRight.getB(); j += increment) {
                    int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING, i, j) - 1;
                    colors[cell++] = shadeByHeight(getTerrainColor(i, y, j), y);
                }
                drawTerrainRowAscending(buffer, i, topLeft.getB(), increment, cell, colors, drawBlockBorder);
            }
        }
        Tuple<Double, Double> mouseWorldPos = cordsToWorldPos((double) (mouseX - x), mouseY - y);
        try {
            if (showRailMap) {
                // 和地形共用同一个 position_color 批次，避免单独开批次导致不显示
                renderRailMap(buffer);
            } else {
                KSDClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) ->
                        drawRectangleFromWorldCords(
                                buffer,
                                platformPos.getX(),
                                platformPos.getZ(),
                                platformPos.getX() + 1,
                                platformPos.getZ() + 1,
                                -1));
                for(KSDStation station : KSDClientData.STATIONS) {
                    if (KSDAreaBase.nonNullCorners(station) && RailDataUtilities.containsY(station, player.getY())) {
                        // 普通模式也使用与 showRailMap 相同的矩形填充 + 1 像素描边
                        drawStationRectangle(buffer, station, getEffectiveScale());
                    }
                }
                if (isMouseOver(mouseX, mouseY)) {
                    mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCords(buffer, x1, z1, x2, z2, -1));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mapState == MapState.EDITING_AREA && drawArea1 != null && drawArea2 != null) {
            drawRectangleFromWorldCords(buffer, drawArea1, drawArea2, Integer.MAX_VALUE);
        }
        tesselator.end();
        RenderSystem.disableBlend();
        UtilitiesClient.finishDrawingRectangle();
        drawPlayerMarker(matrices, player.getX(), player.getZ());
        drawEditingStateText(matrices);
        if (!showRailMap) {
            drawSavedRailNames(matrices);
        }
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        for (KSDStation station : KSDClientData.STATIONS) {
            if (canDrawAreaText(station) && RailDataUtilities.containsY(station, player.getY())) {
                BlockPos pos = station.getCenter();
                String stationString = String.format("%s|(%s)", station.name, Text.translatable("gui.mtr.zone_number", station.zone).getString());
                drawFromWorldCords(pos.getX(), pos.getZ(), (x1, y1) -> IDrawing.drawStringWithFont(matrices, this.textRenderer, immediate, stationString, RenderUtilities.snapToPixel((float) this.x + x1.floatValue()), RenderUtilities.snapToPixel((float) this.y + y1.floatValue()), 15728880));
            }
        }
        immediate.endBatch();
        drawMousePosition(matrices, "X-Z", mouseWorldPos);
    }


    /** Z-Y 平面：侧视，固定玩家所在 X。 */
    private void renderYZ(@NotNull PoseStack matrices, int mouseX, int mouseY) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        UtilitiesClient.beginDrawingRectangle(buffer);
        RenderSystem.enableBlend();
        Tuple<Integer, Integer> topLeft = cordsToWorldPos(0, 0);
        Tuple<Integer, Integer> bottomRight = cordsToWorldPos(width, height);
        int increment = getCellIncrement();
        boolean drawBlockBorder = getEffectiveScale() >= BLOCK_BORDER_MIN_PIXELS;
        if (world != null) {
            int rowCells = getCellCount(topLeft.getB(), bottomRight.getB(), increment);
            int[] colors = getRowColorBuffer(rowCells);
            for(int i = topLeft.getA(); i <= bottomRight.getA(); i += increment) {
                int cell = 0;
                for(int j = topLeft.getB(); j >= bottomRight.getB(); j -= increment) {
                    colors[cell++] = getTerrainColor(Mth.floor(player.getX()), j, i);
                }
                drawTerrainRowDescending(buffer, i, topLeft.getB(), increment, cell, colors, drawBlockBorder);
            }
        }
        Tuple<Double, Double> mouseWorldPos = cordsToWorldPos((double) (mouseX - x), mouseY - y);
        try {
            KSDClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) ->
                    drawRectangleFromWorldCords(
                            buffer,
                            platformPos.getZ(),
                            platformPos.getY(),
                            platformPos.getZ() + 1,
                            platformPos.getY() - 1,
                            -1));
            for(KSDStation station : KSDClientData.STATIONS) {
                if (KSDAreaBase.nonNullCorners(station) && RailDataUtilities.containsX(station, player.getX())) {
                    drawStationRectangle(buffer, station, getEffectiveScale());
                }
            }
            if (isMouseOver(mouseX, mouseY)) {
                mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> drawRectangleFromWorldCords(buffer, x1, z1, x2, z2, -1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mapState == MapState.EDITING_AREA && drawArea1 != null && drawArea2 != null) {
            drawRectangleFromWorldCords(buffer, drawArea1, drawArea2, Integer.MAX_VALUE);
        }
        tesselator.end();
        RenderSystem.disableBlend();
        UtilitiesClient.finishDrawingRectangle();
        drawPlayerMarker(matrices, player.getZ(), player.getY());
        drawEditingStateText(matrices);
        drawSavedRailNames(matrices);
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        for (KSDStation station : KSDClientData.STATIONS) {
            if (canDrawAreaText(station) && RailDataUtilities.containsX(station, player.getX())) {
                BlockPos pos = station.getCenter();
                String stationString = String.format("%s|(%s)", station.name, Text.translatable("gui.mtr.zone_number", station.zone).getString());
                drawFromWorldCords(pos.getZ(), pos.getY(), (x1, y1) -> IDrawing.drawStringWithFont(matrices, textRenderer, immediate, stationString, RenderUtilities.snapToPixel((float) x + x1.floatValue()), RenderUtilities.snapToPixel((float) y + y1.floatValue()), 15728880));
            }
        }
        immediate.endBatch();
        drawMousePosition(matrices, "Z-Y", mouseWorldPos);
    }

    // showRailMap 模式：绘制所有轨道走向（多线路用 2 像素条纹分开）与车站矩形（填充 + 边框）
    private void renderRailMap(BufferBuilder buffer) {
        final double effectiveScale = getEffectiveScale();
        final List<KSDRoutePathGenerator.RailPolyline> railPolylines = KSDRoutePathGenerator.INSTANCE.getRailPolylines();
        for (KSDRoutePathGenerator.RailPolyline railPolyline : railPolylines) {
            final List<double[]> points = railPolyline.getPoints();
            for (int i = 0; i + 1 < points.size(); i++) {
                drawRailSegment(buffer, points.get(i), points.get(i + 1), railPolyline.getColors(), effectiveScale);
            }
        }
        for (KSDStation station : KSDClientData.STATIONS) {
            if (KSDAreaBase.nonNullCorners(station) && RailDataUtilities.containsY(station, player.getY())) {
                drawStationRectangle(buffer, station, effectiveScale);
            }
        }
    }

    // 车站改回矩形：内部半透明填充 + 1 像素不透明边框
    private void drawStationRectangle(BufferBuilder buffer, KSDStation station, double effectiveScale) {
        final Tuple<Integer, Integer> corner1 = station.corner1.toTupleWithXZ();
        final Tuple<Integer, Integer> corner2 = station.corner2.toTupleWithXZ();
        final double minX = Math.min(corner1.getA(), corner2.getA());
        final double maxX = Math.max(corner1.getA(), corner2.getA());
        final double minZ = Math.min(corner1.getB(), corner2.getB());
        final double maxZ = Math.max(corner1.getB(), corner2.getB());
        drawRectangleFromWorldCords(buffer, minX, minZ, maxX, maxZ, ARGB_BLACK_TRANSLUCENT + station.color);
        final int borderColor = 0xFF000000 | (station.color & 0xFFFFFF);
        final double border = 1.0 / Math.max(effectiveScale, 1.0E-4);
        drawRectangleFromWorldCords(buffer, minX, minZ, maxX, minZ + border, borderColor);
        drawRectangleFromWorldCords(buffer, minX, maxZ - border, maxX, maxZ, borderColor);
        drawRectangleFromWorldCords(buffer, minX, minZ + border, minX + border, maxZ - border, borderColor);
        drawRectangleFromWorldCords(buffer, maxX - border, minZ + border, maxX, maxZ - border, borderColor);
    }

    // 把一段世界坐标轨道画成若干条 2 像素宽的彩色条纹（未接入线路时为 1 条灰线）
    private void drawRailSegment(BufferBuilder buffer, double[] from, double[] to,
                                 List<Integer> colors, double effectiveScale) {
        final double x1 = (double)this.x + (from[0] - centerX) * effectiveScale + (double)this.width / 2.0;
        final double y1 = (double)this.y + (from[1] - centerY) * effectiveScale + (double)this.height / 2.0;
        final double x2 = (double)this.x + (to[0] - centerX) * effectiveScale + (double)this.width / 2.0;
        final double y2 = (double)this.y + (to[1] - centerY) * effectiveScale + (double)this.height / 2.0;
        if (Math.max(x1, x2) < (double)this.x || Math.min(x1, x2) > (double)(this.x + this.width)
                || Math.max(y1, y2) < (double)this.y || Math.min(y1, y2) > (double)(this.y + this.height)) {
            return;
        }
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double length = Math.hypot(dx, dy);
        if (length < 0.001) {
            return;
        }
        final double normalX = -dy / length;
        final double normalY = dx / length;
        final int stripes = colors.isEmpty() ? 1 : colors.size();
        for (int index = 0; index < stripes; index++) {
            final double offset = (index - (stripes - 1) / 2.0) * RAIL_STRIPE_WIDTH;
            final int color = colors.isEmpty() ? RAIL_GRAY : colors.get(index);
            final double ox = normalX * offset;
            final double oy = normalY * offset;
            final double halfWidth = RAIL_STRIPE_WIDTH / 2.0;
            final double ax = x1 + ox - normalX * halfWidth;
            final double ay = y1 + oy - normalY * halfWidth;
            final double bx = x2 + ox - normalX * halfWidth;
            final double by = y2 + oy - normalY * halfWidth;
            final double cx = x2 + ox + normalX * halfWidth;
            final double cy = y2 + oy + normalY * halfWidth;
            final double dx2 = x1 + ox + normalX * halfWidth;
            final double dy2 = y1 + oy + normalY * halfWidth;
            // 顶点顺序必须与 IDrawing.drawRectangle 一致（左上、左下、右下、右上），
            // 否则在开启背面剔除时整个四边形会被剔除掉、完全看不见
            drawColorVertex(buffer, ax, ay, color);
            drawColorVertex(buffer, dx2, dy2, color);
            drawColorVertex(buffer, cx, cy, color);
            drawColorVertex(buffer, bx, by, color);
        }
    }

    // 和 IDrawing.drawRectangle 一样使用顶点自带的绝对坐标（不加 PoseStack 变换），
    // 否则会带上 GUI 的位移矩阵、和地形错位
    private static void drawColorVertex(BufferBuilder buffer, double x, double y, int color) {
        buffer.vertex(x, y, 0.0D)
                .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF)
                .endVertex();
    }

    // 玩家位置头像：取皮肤贴图里头部正面的 8×8 区域
    private void drawPlayerMarker(PoseStack matrices, double worldA, double worldB) {
        ResourceLocation skinTexture = player.getSkinTextureLocation();
        drawFromWorldCords(worldA, worldB, (x1, y1) -> {
            // drawFromWorldCords 返回的是地图内部坐标，绘制时要补上地图左上角偏移
            int left = (int)Math.round((double)this.x + x1 - PLAYER_AVATAR_SIZE / 2.0);
            int top = (int)Math.round((double)this.y + y1 - PLAYER_AVATAR_SIZE / 2.0);
            Gui.fill(matrices, left - 1, top - 1, left + PLAYER_AVATAR_SIZE + 1, top + PLAYER_AVATAR_SIZE + 1, 0xFF000000);
            RenderUtilities.drawTexture(matrices, skinTexture, left, top, PLAYER_AVATAR_SIZE, PLAYER_AVATAR_SIZE,
                    8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F);
        });
    }

    // 编辑状态下左上角的提示文字
    private void drawEditingStateText(PoseStack matrices) {
        if (mapState == MapState.EDITING_AREA) {
            Gui.drawString(matrices, textRenderer, Text.translatable("gui.mtr.edit_area").getString(), x + TEXT_PADDING, y + TEXT_PADDING, ARGB_WHITE);
        } else if (mapState == MapState.EDITING_ROUTE) {
            Gui.drawString(matrices, textRenderer, Text.translatable("gui.mtr.edit_route").getString(), x + TEXT_PADDING, y + TEXT_PADDING, ARGB_WHITE);
        }
    }

    // 放大到能看清月台时绘制月台名称
    private void drawSavedRailNames(PoseStack matrices) {
        if (getEffectiveScale() < 8.0) {
            return;
        }
        try {
            KSDClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((platformPos, platforms) -> drawSavedRail(matrices, platformPos, platforms));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 右上角的鼠标世界坐标
    private void drawMousePosition(PoseStack matrices, String prefix, Tuple<Double, Double> mouseWorldPos) {
        String mousePosText = prefix + String.format("(%s, %s)", RailwayData.round(mouseWorldPos.getA(), 1), RailwayData.round(mouseWorldPos.getB(), 1));
        Gui.drawString(matrices, textRenderer, mousePosText, x + width - 6 - textRenderer.width(mousePosText), y + 6, -1);
    }

    // 读取方块材质色并减半，得到地图上的基础颜色
    private int getTerrainColor(int blockX, int blockY, int blockZ) {
        return divideColorRGB(world.getBlockState(RailwayData.newBlockPos(blockX, blockY, blockZ)).getBlock().defaultMaterialColor().col);
    }

    // 按亮度系数逐通道压暗颜色，高度明暗与方块描边都走这套乘法，保证加深方式一致
    private static int multiplyColor(int color, double factor) {
        int r = (int) Math.round(((color >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((color >> 8) & 0xFF) * factor);
        int b = (int) Math.round((color & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    // 0~128 高度范围内，方块越高越深；范围外取极值
    private static int shadeByHeight(int color, int blockY) {
        double factor = 1.0 - Mth.clamp((double) blockY / HEIGHT_SHADE_MAX_Y, 0.0, 1.0) * HEIGHT_SHADE_STRENGTH;
        return multiplyColor(color, factor);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        updateScaleScrollBarBounds();
        if (scaleScrollBar.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        if (mapState == MapState.EDITING_AREA) {
            drawArea2 = cordsToWorldPos((int)Math.round(mouseX - (double)x), (int)Math.round(mouseY - (double)y));
            if (drawArea1.getA().equals(drawArea2.getA())) {
                drawArea2 = new Tuple<>(drawArea2.getA() + 1, drawArea2.getB());
            }
            if (drawArea1.getB().equals(drawArea2.getB())) {
                drawArea2 = new Tuple<>(drawArea2.getA(), drawArea2.getB() + 1);
            }
            onDrawCorners.onDrawCorners(drawArea1, drawArea2);
        } else {
            // 手动拖动时放弃滚轮缩放的锚点，否则每帧都会把中心改回光标位置
            clearZoomAnchor();
            // 拖动只更新目标中心，由 updateCameraAnimation 平滑跟随，避免整数事件造成跳动
            double effectiveScale = getEffectiveScale();
            switch (mapType) {
                case X_Y, Z_Y -> {
                    targetCenterX -= deltaX / effectiveScale;
                    targetCenterY += deltaY / effectiveScale;
                }
                case X_Z -> {
                    targetCenterX -= deltaX / effectiveScale;
                    targetCenterY -= deltaY / effectiveScale;
                }
            }
        }

        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scaleScrollBar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        if (mapState == MapState.EDITING_AREA) {
            this.onDrawCornersMouseRelease.run();
        }
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateScaleScrollBarBounds();
        if (scaleScrollBar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.isMouseOver(mouseX, mouseY)) {
            if (ClientData.hasPermission()) {
                if (this.mapState == MapState.EDITING_AREA) {
                    this.drawArea1 = this.cordsToWorldPos((int)(mouseX - (double)this.x), (int)(mouseY - (double)this.y));
                    this.drawArea2 = null;
                } else if (mapState == MapState.EDITING_ROUTE) {
                    final Tuple<Double, Double> mouseWorldPos = cordsToWorldPos(mouseX - x, mouseY - y);
                    mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> onClickAddPlatformToRoute.accept(savedRail.id));
                } else {
                    final Tuple<Double, Double> mouseWorldPos = cordsToWorldPos(mouseX - x, mouseY - y);
                    mouseOnSavedRail(mouseWorldPos, (savedRail, x1, z1, x2, z2) -> onClickEditSavedRail.accept(savedRail));
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double newTargetScale = Mth.clamp(targetScale * Math.pow(ZOOM_STEP, amount), SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
        if (newTargetScale != targetScale) {
            // 记录光标下的世界坐标，动画期间每帧保持该点停留在光标处，实现“以光标为中心”的平滑缩放
            Tuple<Double, Double> anchorWorldPos = cordsToWorldPos(mouseX - (double)this.x, mouseY - (double)this.y);
            this.zoomAnchorScreenX = mouseX - (double)this.x;
            this.zoomAnchorScreenY = mouseY - (double)this.y;
            this.zoomAnchorWorldA = anchorWorldPos.getA();
            this.zoomAnchorWorldB = anchorWorldPos.getB();
            this.zoomAnchorActive = true;
            this.targetScale = newTargetScale;
            // 目标中心同样落在“缩放结束后锚点仍位于光标下”的位置，动画结束后不会回弹
            double newEffectiveScale = newTargetScale * BLOCK_PIXEL_SIZE;
            double targetOffsetX = (this.zoomAnchorScreenX - (double)this.width / (double)2.0F) / newEffectiveScale;
            double targetOffsetY = (this.zoomAnchorScreenY - (double)this.height / (double)2.0F) / newEffectiveScale;
            this.targetCenterX = this.zoomAnchorWorldA - targetOffsetX;
            this.targetCenterY = this.mapType == MapType.X_Z
                    ? this.zoomAnchorWorldB - targetOffsetY
                    : this.zoomAnchorWorldB + targetOffsetY;
        }
        return true;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= (double)this.x
                && mouseY >= (double)this.y
                && mouseX < (double)(this.x + this.width)
                && mouseY < (double)(this.y + this.height)
                && (!(mouseX >= (double)(this.x + this.width - 300)) || !(mouseY >= (double)(this.y + this.height - 20)))
                && !(Boolean)this.isRestrictedMouseArea.apply(mouseX, mouseY);
    }

    public void setFocused(boolean focused) {
    }

    public boolean isFocused() {
        return false;
    }

    public void setPositionAndSize(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        updateScaleScrollBarBounds();
    }

    public void scale(double amount) {
        this.clearZoomAnchor();
        this.targetScale = Mth.clamp(this.targetScale * Math.pow(2.0F, amount), SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
    }

    public boolean isShowRailMap() {
        return this.showRailMap;
    }

    public void setShowRailMap(boolean showRailMap) {
        this.showRailMap = showRailMap;
    }

    public void toggleShowRailMap() {
        setShowRailMap(!this.showRailMap);
    }

    // 取消缩放锚点，并把目标中心同步到当前显示中心，避免动画中途跳回旧目标
    private void clearZoomAnchor() {
        if (this.zoomAnchorActive) {
            this.zoomAnchorActive = false;
            this.targetCenterX = this.centerX;
            this.targetCenterY = this.centerY;
        }
    }

    /**
     * 每帧推进相机动画：
     * 1. 显示倍率按指数曲线逐步逼近目标倍率，避免滚轮、按钮或进度条造成的瞬间放大/缩小；
     * 2. 中心点同样缓动到目标中心，使拖动、定位跳转都是平滑移动而不是瞬间跳动。
     * 缩放过程中若记录了锚点，则每帧由锚点反算中心，让锚点对应的世界坐标始终停留在光标下。
     */
    private void updateCameraAnimation() {
        long now = System.nanoTime();
        long lastUpdate = this.lastCameraUpdateNanos;
        this.lastCameraUpdateNanos = now;
        if (lastUpdate == 0L) {
            return;
        }
        double deltaSeconds = Math.min((now - lastUpdate) / 1.0E9, 0.1);
        if (this.scale != this.targetScale) {
            double currentLogScale = Math.log(this.scale);
            double targetLogScale = Math.log(this.targetScale);
            double factor = 1.0 - Math.exp(-SCALE_SMOOTHING_RATE * deltaSeconds);
            double nextScale = Math.exp(currentLogScale + (targetLogScale - currentLogScale) * factor);
            if (Math.abs(targetLogScale - Math.log(nextScale)) <= SCALE_SNAPPING_THRESHOLD) {
                nextScale = this.targetScale;
            }
            this.scale = nextScale;
        }
        if (this.zoomAnchorActive) {
            double effectiveScale = getEffectiveScale();
            double offsetX = (this.zoomAnchorScreenX - (double)this.width / (double)2.0F) / effectiveScale;
            double offsetY = (this.zoomAnchorScreenY - (double)this.height / (double)2.0F) / effectiveScale;
            this.centerX = this.zoomAnchorWorldA - offsetX;
            this.centerY = this.mapType == MapType.X_Z ? this.zoomAnchorWorldB - offsetY : this.zoomAnchorWorldB + offsetY;
            if (this.scale == this.targetScale) {
                this.zoomAnchorActive = false;
                this.targetCenterX = this.centerX;
                this.targetCenterY = this.centerY;
            }
        } else {
            double diffX = this.targetCenterX - this.centerX;
            double diffY = this.targetCenterY - this.centerY;
            double effectiveScale = getEffectiveScale();
            if (Math.abs(diffX) * effectiveScale < CENTER_SNAPPING_PIXELS && Math.abs(diffY) * effectiveScale < CENTER_SNAPPING_PIXELS) {
                this.centerX = this.targetCenterX;
                this.centerY = this.targetCenterY;
            } else {
                double factor = 1.0 - Math.exp(-CENTER_SMOOTHING_RATE * deltaSeconds);
                this.centerX += diffX * factor;
                this.centerY += diffY * factor;
            }
        }
    }

    // 把倍率换算成滑动条的 0~1 位置，使用对数刻度以保证每一段拖动带来的缩放比例一致
    private double getScaleSliderFraction(double scaleValue) {
        double logMin = Math.log(SCALE_LOWER_LIMIT);
        double logMax = Math.log(SCALE_UPPER_LIMIT);
        return Mth.clamp((Math.log(scaleValue) - logMin) / (logMax - logMin), 0.0F, 1.0F);
    }

    // 滑动条数值换算回倍率
    private double getScaleFromSliderFraction(double fraction) {
        double logMin = Math.log(SCALE_LOWER_LIMIT);
        double logMax = Math.log(SCALE_UPPER_LIMIT);
        return Mth.clamp(Math.exp(logMin + (logMax - logMin) * fraction), SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
    }

    // 滑动条定位在右边缘中间
    private void updateScaleScrollBarBounds() {
        int barHeight = Math.max(40, Math.min(SCALE_BAR_HEIGHT, this.height - 20));
        this.scaleScrollBar.setPositionAndSize(
                this.x + this.width - SCALE_BAR_MARGIN - SCALE_BAR_WIDTH,
                this.y + this.height / 2 - barHeight / 2,
                SCALE_BAR_WIDTH,
                barHeight);
    }

    private void renderScaleScrollBar(PoseStack matrices, int mouseX, int mouseY) {
        updateScaleScrollBarBounds();
        // 没有在拖动时，滑块位置跟随当前显示倍率
        if (!this.scaleScrollBar.isDragging()) {
            this.scaleScrollBar.setValue(getScaleSliderFraction(this.scale));
        }
        this.scaleScrollBar.render(matrices, mouseX, mouseY, 0.0F);
        if (this.scaleScrollBar.isDragging() || this.scaleScrollBar.isMouseOver(mouseX, mouseY)) {
            String scaleText = String.format("%.2fx", getScaleFromSliderFraction(this.scaleScrollBar.getValue()));
            int textX = this.scaleScrollBar.getX() - 8 - this.textRenderer.width(scaleText);
            int textY = this.scaleScrollBar.getThumbCenterY() - 4;
            Gui.drawString(matrices, this.textRenderer, scaleText, textX + 1, textY + 1, 0xFF000000);
            Gui.drawString(matrices, this.textRenderer, scaleText, textX, textY, ARGB_WHITE);
        }
    }

    // 滑动条回调：更新目标倍率，缩放仍以地图中心为锚点
    private void onScaleScrollBarChanged(double value) {
        this.clearZoomAnchor();
        this.targetScale = getScaleFromSliderFraction(value);
    }

    public void find(double x1, double z1, double x2, double z2) {
        this.clearZoomAnchor();
        this.targetCenterX = (x1 + x2) / (double)2.0F;
        this.targetCenterY = (z1 + z2) / (double)2.0F;
        this.targetScale = Math.max(2.0F / BLOCK_PIXEL_SIZE, this.targetScale);
    }

    public void find(BlockPos pos) {
        this.clearZoomAnchor();
        switch (mapType) {
            case X_Y -> {
                this.targetCenterX = pos.getX();
                this.targetCenterY = pos.getY();
            }
            case X_Z -> {
                this.targetCenterX = pos.getX();
                this.targetCenterY = pos.getZ();
            }
            case Z_Y -> {
                this.targetCenterX = pos.getZ();
                this.targetCenterY = pos.getY();
            }
        }
        this.targetScale = Math.max(8.0F / BLOCK_PIXEL_SIZE, this.targetScale);
    }

    public void startEditingArea(KSDAreaBase editingArea) {
        mapState = MapState.EDITING_AREA;
        switch (mapType) {
            case X_Y -> {
                drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithXY() : null;
                drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithXY() : null;
            }
            case X_Z -> {
                drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithXZ() : null;
                drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithXZ() : null;
            }
            case Z_Y -> {
                drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithZY() : null;
                drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithZY() : null;
            }
        }
    }

    public void startEditingRoute() {
        mapState = MapState.EDITING_ROUTE;
    }

    public void stopEditing() {
        mapState = MapState.DEFAULT;
    }

    public void setMapType(MapType mapType, KSDAreaBase editingArea) {
        this.mapType = mapType;
        this.zoomAnchorActive = false;
        if (player == null) {
            centerX = 0.0F;
            centerY = 0.0F;
        } else {
            centerX = mapType != MapType.Z_Y ? player.getX() : player.getZ();
            centerY = mapType != MapType.X_Z ? player.getY() : player.getZ();
        }
        // 切换平面属于整体重置，直接同步目标中心，避免从旧位置缓动过去
        targetCenterX = centerX;
        targetCenterY = centerY;
        if (mapState.equals(MapState.EDITING_AREA) && editingArea != null) {
            switch (this.mapType) {
                case X_Y -> {
                    drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithXY() : null;
                    drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithXY() : null;
                }
                case X_Z -> {
                    drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithXZ() : null;
                    drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithXZ() : null;
                }
                case Z_Y -> {
                    drawArea1 = editingArea.corner1 != null ? editingArea.corner1.toTupleWithZY() : null;
                    drawArea2 = editingArea.corner2 != null ? editingArea.corner2.toTupleWithZY() : null;
                }
            }
        }
    }

    private void mouseOnSavedRail(Tuple<Double, Double> mouseWorldPos, MouseOnSavedRailCallback mouseOnSavedRailCallback) {
        try {
            KSDClientData.DATA_CACHE.getPosToPlatforms(transportMode).forEach((savedRailPos, savedRails) -> {
                int savedRailCount = savedRails.size();
                for(int i = 0; i < savedRailCount; ++i) {
                    switch (mapType) {
                        case X_Y -> {
                            float left = savedRailPos.getX();
                            float right = (savedRailPos.getX() + 1);
                            float top = savedRailPos.getY() + (float) i / savedRailCount;
                            float bottom = savedRailPos.getY() + (i - 1.0F) / savedRailCount;
                            if (RailwayData.isBetween(mouseWorldPos.getA(), left, right) && RailwayData.isBetween(mouseWorldPos.getB(), top, bottom)) {
                                mouseOnSavedRailCallback.mouseOnSavedRailCallback(savedRails.get(i), left, top, right, bottom);
                            }
                        }
                        case X_Z -> {
                            float left = savedRailPos.getX();
                            float right = (savedRailPos.getX() + 1);
                            float top = savedRailPos.getZ() + (float) i / savedRailCount;
                            float bottom = savedRailPos.getZ() + (i + 1.0F) / savedRailCount;
                            if (RailwayData.isBetween(mouseWorldPos.getA(), left, right) && RailwayData.isBetween(mouseWorldPos.getB(), top, bottom)) {
                                mouseOnSavedRailCallback.mouseOnSavedRailCallback(savedRails.get(i), left, top, right, bottom);
                            }
                        }
                        case Z_Y -> {
                            float left = savedRailPos.getZ();
                            float right = (savedRailPos.getZ() + 1);
                            float top = savedRailPos.getY() + (float) i / savedRailCount;
                            float bottom = savedRailPos.getY() + (i - 1.0F) / savedRailCount;
                            if (RailwayData.isBetween(mouseWorldPos.getA(), left, right) && RailwayData.isBetween(mouseWorldPos.getB(), top, bottom)) {
                                mouseOnSavedRailCallback.mouseOnSavedRailCallback(savedRails.get(i), left, top, right, bottom);
                            }
                        }
                    }
                }
            });
        } catch (ConcurrentModificationException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private Tuple<Integer, Integer> cordsToWorldPos(int mouseX, int mouseY) {
        Tuple<Double, Double> worldPos = cordsToWorldPos(mouseX, (double)mouseY);
        return switch (mapType) {
            case X_Y, Z_Y -> new Tuple<>((int)Math.floor(worldPos.getA()), (int)Math.ceil(worldPos.getB()));
            case X_Z -> new Tuple<>((int)Math.floor(worldPos.getA()), (int)Math.floor(worldPos.getB()));
        };
    }

    private Tuple<Double, Double> cordsToWorldPos(double mouseX, double mouseY) {
        double effectiveScale = getEffectiveScale();
        switch (mapType) {
            case X_Y, Z_Y -> {
                double left = (mouseX - (double) width / (double)2.0F) / effectiveScale + centerX;
                double right = -((mouseY - (double) height / (double)2.0F) / effectiveScale) + centerY;
                return new Tuple<>(left, right);
            }
            case X_Z -> {
                double left = (mouseX - (double) width / (double)2.0F) / effectiveScale + centerX;
                double right = (mouseY - (double) height / (double)2.0F) / effectiveScale + centerY;
                return new Tuple<>(left, right);
            }
        }
        return new Tuple<>(mouseX, mouseY);
    }

    private void drawFromWorldCords(double worldX, double worldZ, BiConsumer<Double, Double> callback) {
        double effectiveScale = getEffectiveScale();
        switch (mapType) {
            case X_Y, Z_Y -> {
                double cordsX = (worldX - centerX) * effectiveScale + (double)width / (double)2.0F;
                double cordsY = (centerY - worldZ) * effectiveScale + (double)height / (double)2.0F;
                if (RailwayData.isBetween(cordsX, 0.0F, width) && RailwayData.isBetween(cordsY, 0.0F, height)) {
                    callback.accept(cordsX, cordsY);
                }
            }
            case X_Z -> {
                double cordsX = (worldX - centerX) * effectiveScale + (double)width / (double)2.0F;
                double cordsY = (worldZ - centerY) * effectiveScale + (double)height / (double)2.0F;
                if (RailwayData.isBetween(cordsX, 0.0F, width) && RailwayData.isBetween(cordsY, 0.0F, height)) {
                    callback.accept(cordsX, cordsY);
                }
            }
        }
    }

    private void drawRectangleFromWorldCords(BufferBuilder buffer, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int color) {
        switch (mapType) {
            case X_Y, Z_Y ->
                    drawRectangleFromWorldCords(
                            buffer,
                            corner1.getA(),
                            corner1.getB() - 1,
                            corner2.getA(),
                            corner2.getB() - 1,
                            color);
            case X_Z ->
                    drawRectangleFromWorldCords(
                            buffer,
                            corner1.getA(),
                            corner1.getB(),
                            corner2.getA(),
                            corner2.getB(),
                            color);
        }
    }

    private void drawRectangleFromWorldCords(BufferBuilder buffer, double posX1, double posZ1, double posX2, double posZ2, int color) {
        double effectiveScale = getEffectiveScale();
        switch (mapType) {
            case X_Y, Z_Y -> {
                double x1 = (posX1 - centerX) * effectiveScale + (double)width / (double)2.0F;
                double y1 = (centerY - posZ1) * effectiveScale + (double)height / (double)2.0F;
                double x2 = (posX2 - centerX) * effectiveScale + (double)width / (double)2.0F;
                double y2 = (centerY - posZ2) * effectiveScale + (double)height / (double)2.0F;
                drawRectangle(buffer, x1, y1, x2, y2, color);
            }
            case X_Z -> {
                double x1 = (posX1 - centerX) * effectiveScale + (double)width / (double)2.0F;
                double z1 = (posZ1 - centerY) * effectiveScale + (double)height / (double)2.0F;
                double x2 = (posX2 - centerX) * effectiveScale + (double)width / (double)2.0F;
                double z2 = (posZ2 - centerY) * effectiveScale + (double)height / (double)2.0F;
                drawRectangle(buffer, x1, z1, x2, z2, color);
            }
        }
    }

    private void drawRectangle(BufferBuilder buffer, double xA, double yA, double xB, double yB, int color) {
        double x1 = Math.min(xA, xB);
        double y1 = Math.min(yA, yB);
        double x2 = Math.max(xA, xB);
        double y2 = Math.max(yA, yB);
        if (x1 < (double) width && y1 < (double) height && x2 >= (double) 0.0F && y2 >= (double) 0.0F) {
            IDrawing.drawRectangle(buffer, (double) x + Math.max(0.0F, x1), (double) y + y1, (double) x + x2, (double) y + y2, color);
        }

    }

    // 一行内的格子数量；start 与 end 谁大都可以
    private static int getCellCount(int start, int end, int increment) {
        return Math.floorDiv(Math.abs(end - start), increment) + 1;
    }

    // 复用同一块颜色数组，避免每帧为每一行重新分配
    private int[] getRowColorBuffer(int size) {
        if (rowColorBuffer.length < size) {
            rowColorBuffer = new int[size];
        }
        return rowColorBuffer;
    }

    // 当前每个方块在屏幕上的像素尺寸（scale 为 1 时是 BLOCK_PIXEL_SIZE 像素）
    private double getEffectiveScale() {
        return this.scale * BLOCK_PIXEL_SIZE;
    }

    // 一个格子在世界上跨越多少方块：方块小于 1 像素时才需要合并多个方块
    private int getCellIncrement() {
        double effectiveScale = getEffectiveScale();
        return effectiveScale >= 1.0 ? 1 : (int)Math.ceil(1.0 / effectiveScale);
    }

    /**
     * 绘制一个方块格子：深色描边 + 内部填充方块颜色。
     * 描边宽度与格子边长成固定比例（1/BLOCK_BORDER_RATIO），所以放大缩小时方块和描边的比例保持一致。
     */
    private void drawTerrainCell(BufferBuilder buffer, double aLow, double aHigh, double bLow, double bHigh, int rgb) {
        double inset = (aHigh - aLow) / BLOCK_BORDER_RATIO;
        if (aHigh - aLow > inset * 2.0 && bHigh - bLow > inset * 2.0) {
            // 描边取当前方块颜色再略微压暗，和高度明暗使用同一种乘法加深
            drawRectangleFromWorldCords(buffer, aLow, bLow, aHigh, bHigh, ARGB_BLACK | multiplyColor(rgb, BLOCK_BORDER_DARKEN));
            drawRectangleFromWorldCords(buffer, aLow + inset, bLow + inset, aHigh - inset, bHigh - inset, ARGB_BLACK | rgb);
        } else {
            drawRectangleFromWorldCords(buffer, aLow, bLow, aHigh, bHigh, ARGB_BLACK | rgb);
        }
    }

    /**
     * 绘制一行格子，坐标沿 B 轴递增（X_Z）。
     * scale 达到 BLOCK_BORDER_MIN_PIXELS 时逐格绘制并加描边；否则把同色相邻格子合并成一个矩形，
     * 这样缩小时顶点数量不会随视野面积暴涨。
     */
    private void drawTerrainRowAscending(BufferBuilder buffer, int a, int startB, int increment, int count, int[] colors, boolean drawBorder) {
        if (drawBorder) {
            for (int k = 0; k < count; ++k) {
                int fromB = startB + k * increment;
                drawTerrainCell(buffer, a, a + increment, fromB, fromB + increment, colors[k]);
            }
            return;
        }
        int runStart = 0;
        for (int i = 1; i <= count; ++i) {
            if (i == count || colors[i] != colors[runStart]) {
                int fromB = startB + runStart * increment;
                int toB = startB + i * increment;
                drawRectangleFromWorldCords(buffer, a, fromB, a + increment, toB, ARGB_BLACK | colors[runStart]);
                runStart = i;
            }
        }
    }

    /**
     * 与 {@link #drawTerrainRowAscending} 相同，但坐标沿 B 轴递减（X_Y、Z_Y）。
     */
    private void drawTerrainRowDescending(BufferBuilder buffer, int a, int startB, int increment, int count, int[] colors, boolean drawBorder) {
        if (drawBorder) {
            for (int k = 0; k < count; ++k) {
                int toB = startB - k * increment;
                drawTerrainCell(buffer, a, a + increment, toB - increment, toB, colors[k]);
            }
            return;
        }
        int runStart = 0;
        for (int i = 1; i <= count; ++i) {
            if (i == count || colors[i] != colors[runStart]) {
                int topB = startB - runStart * increment;
                int bottomB = startB - i * increment;
                drawRectangleFromWorldCords(buffer, a, bottomB, a + increment, topB, ARGB_BLACK | colors[runStart]);
                runStart = i;
            }
        }
    }

    private boolean canDrawAreaText(KSDAreaBase areaBase) {
        return areaBase.getCenter() != null && getEffectiveScale() >= (double) (80.0F / (float)Math.max(Math.abs(areaBase.corner1.getX() - areaBase.corner2.getX()), Math.abs(areaBase.corner1.getZ() - areaBase.corner2.getZ())));
    }

    private void drawSavedRail(PoseStack matrices, BlockPos savedRailPos, List<? extends SavedRailBase> savedRails) {
        int savedRailCount = savedRails.size();
        for(int i = 0; i < savedRailCount; ++i) {
            int finalI = i;
            switch (mapType) {
                case X_Y ->
                        drawFromWorldCords((double)savedRailPos.getX() + (double)0.5F,
                                (double)savedRailPos.getY() + ((double)i - (double)0.5F) / (double)savedRailCount,
                                (x1, y1) -> Gui.drawCenteredString(matrices,
                                        textRenderer,
                                        savedRails.get(finalI).name,
                                        x + x1.intValue(),
                                        y + y1.intValue() - 4,
                                        -1));
                case X_Z ->
                        drawFromWorldCords((double)savedRailPos.getX() + (double)0.5F,
                                (double)savedRailPos.getZ() + ((double)i + (double)0.5F) / (double)savedRailCount,
                                (x1, y1) -> Gui.drawCenteredString(matrices,
                                        textRenderer,
                                        savedRails.get(finalI).name,
                                        x + x1.intValue(),
                                        y + y1.intValue() - 4,
                                        -1));
                case Z_Y ->
                        drawFromWorldCords((double)savedRailPos.getZ() + (double)0.5F,
                                (double)savedRailPos.getY() + ((double)i - (double)0.5F) / (double)savedRailCount,
                                (x1, y1) -> Gui.drawCenteredString(matrices,
                                        textRenderer,
                                        savedRails.get(finalI).name,
                                        x + x1.intValue(),
                                        y + y1.intValue() - 4,
                                        -1));
            }
        }
    }

    private static int divideColorRGB(int color) {
        int r = (color >> 16 & 255) / 2;
        int g = (color >> 8 & 255) / 2;
        int b = (color & 255) / 2;
        return (r << 16) + (g << 8) + b;
    }

    private enum MapState {
        DEFAULT,
        EDITING_AREA,
        EDITING_ROUTE
    }

    @FunctionalInterface
    private interface MouseOnSavedRailCallback {
        void mouseOnSavedRailCallback(SavedRailBase var1, double var2, double var4, double var6, double var8);
    }

    @FunctionalInterface
    public interface OnDrawCorners {
        void onDrawCorners(Tuple<Integer, Integer> var1, Tuple<Integer, Integer> var2);
    }

    public enum MapType {
        X_Z,
        X_Y,
        Z_Y;

        public MapType next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }
}
