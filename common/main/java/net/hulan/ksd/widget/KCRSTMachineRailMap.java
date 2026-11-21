package net.hulan.ksd.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.RailwayData;
import mtr.mappings.SelectableMapper;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.data.KSDAreaBase;
import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.SingleTicketSystem;
import net.hulan.ksd.path.KSDPath;
import net.hulan.ksd.path.KSDRailMapGenerator;
import net.hulan.ksd.sreen.KSDGui;
import net.hulan.ksd.utils.RenderUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 线路图组件：只保留渲染、鼠标交互和标签排版。
 *
 * <p>站点布局、避让、线路曲线与换乘胶囊由 {@link KSDRailMapGenerator} 生成，
 * 本组件只读取生成结果并负责绘制。</p>
 */
public class KCRSTMachineRailMap implements WidgetMapper, SelectableMapper, GuiEventListener, KSDGui {

    private int x;
    private int y;
    private int width;
    private int height;
    private double scale;
    private double centerX;
    private double centerY;
    private final boolean isLRT;
    private final SingleTicketSystem.TicketType ticketType;
    private final Consumer<KSDStation> onClickedOnDestination;
    private final KSDStation currentStation;
    private final KSDRailMapGenerator generator = KSDRailMapGenerator.INSTANCE;
    private final List<List<double[]>> drawingLines = new ArrayList<>();
    private final List<Integer> drawingLineColors = new ArrayList<>();
    private final List<StationLabel> stationLabels = new ArrayList<>();
    private boolean lastHovering;
    private long handCursor;

    public KCRSTMachineRailMap(Consumer<KSDStation> onClickedOnDestination,
                               SingleTicketSystem.TicketType ticketType,
                               KSDStation currentStation) {
        this.ticketType = ticketType;
        this.onClickedOnDestination = onClickedOnDestination;
        this.currentStation = currentStation;
        final Minecraft minecraftClient = Minecraft.getInstance();
        final LocalPlayer player = minecraftClient.player;
        if (player == null) {
            centerX = 0.0F;
            centerY = 0.0F;
        } else {
            centerX = player.getX();
            centerY = player.getZ();
        }
        scale = 0.05F;
        isLRT = ticketType.equals(SingleTicketSystem.TicketType.LRT);
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        Gui.fill(matrices, x, y, x + width, y + height, ARGB_WHITE);
        generator.setViewTransform(scale, centerX, centerY, width, height);
        refreshDrawingLines();
        stationLabels.clear();
        stationLabels.addAll(buildStationLabels());
        drawRoutes(matrices);
        drawStationsAndNames(matrices, mouseX, mouseY);
    }

    /** 从生成器路径取当前帧要绘制和避让的采样折线。 */
    private void refreshDrawingLines() {
        drawingLines.clear();
        drawingLineColors.clear();
        for (KSDPath path : generator.getPaths()) {
            drawingLines.add(path.getPoints(generator.getSampleStep(path)));
            drawingLineColors.add(generator.getPathColor(path));
        }
    }

    private void drawRoutes(PoseStack matrices) {
        for (int i = 0; i < drawingLines.size(); i++) {
            final List<double[]> points = drawingLines.get(i);
            final int color = drawingLineColors.get(i);
            for (int j = 0; j < points.size() - 1; j++) {
                final double[] from = points.get(j);
                final double[] to = points.get(j + 1);
                RenderUtilities.drawThickLine(matrices, x + (float) from[0], y + (float) from[1],
                        x + (float) to[0], y + (float) to[1], LINE_WIDTH, color);
            }
            // 为每条线路段补上线宽直径的圆形端盖。
            if (points.size() >= 2) {
                final double[] start = points.get(0);
                final double[] end = points.get(points.size() - 1);
                final float endpointRadius = LINE_WIDTH / 2.0F;
                RenderUtilities.drawStationCircle(matrices, x + (float) start[0], y + (float) start[1],
                        endpointRadius, SEGMENTS, endpointRadius, color);
                RenderUtilities.drawStationCircle(matrices, x + (float) end[0], y + (float) end[1],
                        endpointRadius, SEGMENTS, endpointRadius, color);
            }
        }
    }

    private void drawStationsAndNames(PoseStack matrices, int mouseX, int mouseY) {
        final List<KSDRailMapGenerator.InterchangeCapsule> capsules = generator.getInterchangeCapsules();
        final List<KSDRailMapGenerator.StationCircle> circles = generator.getStationCircles();
        final Tuple<Float, Float> mouse = new Tuple<>((float) mouseX - x, (float) mouseY - y);
        KSDRailMapGenerator.InterchangeCapsule hoveredCapsule = null;
        for (KSDRailMapGenerator.InterchangeCapsule capsule : capsules) {
            if (isPointInCapsule(mouse, capsule)) {
                hoveredCapsule = capsule;
                break;
            }
        }

        // 先确定最终颜色，再把每个胶囊完整绘制一次，避免同深度重复叠画产生闪烁和错位。
        for (KSDRailMapGenerator.InterchangeCapsule capsule : capsules) {
            final int color = capsule == hoveredCapsule ? RenderUtilities.lightenColor(capsule.color) : capsule.color;
            drawInterchangeCapsule(matrices, capsule, color);
        }
        // 遍历所有车站圆
        for (KSDRailMapGenerator.StationCircle circle : circles) {
            // 先画白色实心圆作为车站底色
            RenderUtilities.drawStationCircle(matrices, (float) x + circle.centerX, (float) y + circle.centerY,
                    RADIUS, SEGMENTS, RADIUS, ARGB_WHITE);
            // 再画一圈线路颜色的圆环表示所属线路
            RenderUtilities.drawStationCircle(matrices, (float) x + circle.centerX, (float) y + circle.centerY,
                    RADIUS, SEGMENTS, STATION_RING_THICKNESS, circle.color);
        }
        final AtomicBoolean hovered = new AtomicBoolean(false);
        // 检测鼠标是否落在任一车站圆内，若命中则高亮该站的所有圆环
        mouseOnStation(new Tuple<>((float) mouseX - x, (float) mouseY - y), station -> {
            hovered.set(true);
            for (KSDRailMapGenerator.StationCircle circle : circles) {
                if (circle.stationId == station.id) {
                    final int color = RenderUtilities.lightenColor(isLRT ? ARGB_BLACK : circle.color);
                    RenderUtilities.drawStationCircle(matrices, (float) x + circle.centerX, (float) y + circle.centerY,
                            RADIUS, SEGMENTS, STATION_RING_THICKNESS, color);
                }
            }
        });
        if (hoveredCapsule != null) {
            hovered.set(true);
        }
        updateCursor(hovered.get());
        for (StationLabel label : stationLabels) {
            RenderUtilities.drawTextCjk(matrices, label.text,
                    x + label.x, y + label.y, STATION_NAME_SCALE, STATION_EN_SCALE, ARGB_BLACK);
        }
    }

    private List<StationLabel> buildStationLabels() {
        final List<StationLabel> labels = new ArrayList<>();
        final List<LabelBox> placedLabels = new ArrayList<>();
        for (KSDStation station : generator.getRenderableStations()) {
            if (KSDAreaBase.nonNullCorners(station)) {
                final String name = station.name;
                final float[] textSize = RenderUtilities.getTextSizeCjk(name, STATION_NAME_SCALE, STATION_EN_SCALE);
                final float textWidth = textSize[0];
                final float textHeight = textSize[1];
                Tuple<Float, Float> labelAnchor = getVisibleStationCenter(station.id);
                // 优先以避让后的实际圆圈质心作为站名锚点，无可见圆圈时退回世界坐标。
                if (labelAnchor == null) {
                    final BlockPos stationCenter = station.getCenter();
                    final Tuple<Double, Double> fallback = generator.worldPosToCords(stationCenter.getX(), stationCenter.getZ());
                    labelAnchor = new Tuple<>(fallback.getA().floatValue(), fallback.getB().floatValue());
                }
                if (RailwayData.isBetween(labelAnchor.getA(), 0.0F, width)
                        && RailwayData.isBetween(labelAnchor.getB(), 0.0F, height)) {
                    final float anchorX = labelAnchor.getA();
                    final float anchorY = labelAnchor.getB();
                    // 八个候选方位，优先水平两侧，再尝试上下及四个对角方向。
                    final float[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
                    float bestScore = Float.MAX_VALUE;
                    int bestSide = 0;
                    for (int i = 0; i < sides.length; i++) {
                        final float[] position = getLabelPosition(anchorX, anchorY, textWidth, textHeight, sides[i][0], sides[i][1]);
                        final float labelX = position[0];
                        final float labelY = position[1];
                        final float score = labelOverlapScore(labelX, labelY, textWidth, textHeight, placedLabels);
                        if (score == 0) {
                            bestSide = i;
                            break;
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            bestSide = i;
                        }
                    }
                    final float[] position = getLabelPosition(anchorX, anchorY, textWidth, textHeight,
                            sides[bestSide][0], sides[bestSide][1]);
                    placedLabels.add(new LabelBox(position[0], position[1] - textHeight / 2,
                            position[0] + textWidth, position[1] + textHeight / 2));
                    labels.add(new StationLabel(name, position[0], position[1]));
                }
            }
        }
        return labels;
    }

    private void drawInterchangeCapsule(PoseStack matrices, KSDRailMapGenerator.InterchangeCapsule capsule, int color) {
        final float x1 = x + capsule.start.getA();
        final float y1 = y + capsule.start.getB();
        final float x2 = x + capsule.end.getA();
        final float y2 = y + capsule.end.getB();
        final float innerRadius = capsule.radius - STATION_RING_THICKNESS;

        RenderUtilities.drawThickLine(matrices, x1, y1, x2, y2, capsule.radius * 2, color);
        RenderUtilities.drawStationCircle(matrices, x1, y1, capsule.radius, SEGMENTS, capsule.radius, color);
        RenderUtilities.drawStationCircle(matrices, x2, y2, capsule.radius, SEGMENTS, capsule.radius, color);
        RenderUtilities.drawThickLine(matrices, x1, y1, x2, y2, innerRadius * 2, ARGB_WHITE);
        RenderUtilities.drawStationCircle(matrices, x1, y1, innerRadius, SEGMENTS, innerRadius, ARGB_WHITE);
        RenderUtilities.drawStationCircle(matrices, x2, y2, innerRadius, SEGMENTS, innerRadius, ARGB_WHITE);
    }

    private static boolean isPointInCapsule(Tuple<Float, Float> point, KSDRailMapGenerator.InterchangeCapsule capsule) {
        return pointSegmentDistance(point, capsule.start, capsule.end) <= capsule.radius;
    }

    private Tuple<Float, Float> getVisibleStationCenter(long stationId) {
        float sumX = 0;
        float sumY = 0;
        int count = 0;
        for (KSDRailMapGenerator.StationCircle circle : generator.getStationCircles()) {
            if (circle.stationId == stationId) {
                sumX += circle.centerX;
                sumY += circle.centerY;
                count++;
            }
        }
        for (KSDRailMapGenerator.InterchangeCapsule capsule : generator.getInterchangeCapsules()) {
            if (capsule.stationId == stationId) {
                sumX += capsule.mainCenter.getA();
                sumY += capsule.mainCenter.getB();
                count++;
            }
        }
        return count == 0 ? null : new Tuple<>(sumX / count, sumY / count);
    }

    // 计算某个站名标签的包围盒与所有线路线段的重叠总长度；0 表示完全无重叠。
    private float labelOverlapScore(float labelX, float labelY, float textWidth, float textHeight, List<LabelBox> placedLabels) {
        final float padding = 2;
        final float minX = labelX - padding;
        final float maxX = labelX + textWidth + padding;
        final float minY = labelY - textHeight / 2 - padding;
        final float maxY = labelY + textHeight / 2 + padding;
        float total = 0;
        for (List<double[]> points : drawingLines) {
            for (int i = 0; i < points.size() - 1; i++) {
                final double[] a = points.get(i);
                final double[] b = points.get(i + 1);
                total += clippedSegmentLength(a[0], a[1], b[0], b[1],
                        minX - LINE_WIDTH / 2, minY - LINE_WIDTH / 2, maxX + LINE_WIDTH / 2, maxY + LINE_WIDTH / 2) * 10;
            }
        }
        for (KSDRailMapGenerator.InterchangeCapsule capsule : generator.getInterchangeCapsules()) {
            total += clippedSegmentLength(capsule.start.getA(), capsule.start.getB(), capsule.end.getA(), capsule.end.getB(),
                    minX - capsule.radius, minY - capsule.radius, maxX + capsule.radius, maxY + capsule.radius) * 100;
        }
        // 辅助站虽然不画圆，也必须为其线路端点保留空间。
        for (Tuple<Float, Float> center : generator.getLogicalStationCenters()) {
            final double closestX = Mth.clamp(center.getA(), minX, maxX);
            final double closestY = Mth.clamp(center.getB(), minY, maxY);
            if (Math.hypot(center.getA() - closestX, center.getB() - closestY) < RADIUS + padding) {
                total += 10000;
            }
        }
        for (LabelBox box : placedLabels) {
            final float overlapWidth = Math.max(0, Math.min(maxX, box.maxX) - Math.max(minX, box.minX));
            final float overlapHeight = Math.max(0, Math.min(maxY, box.maxY) - Math.max(minY, box.minY));
            total += overlapWidth * overlapHeight * 100;
        }
        return total;
    }

    private static float[] getLabelPosition(float centerX, float centerY, float width, float height, float directionX, float directionY) {
        final float gap = RADIUS + RADIUS_PADDING;
        final float labelX = directionX > 0 ? centerX + gap : directionX < 0 ? centerX - gap - width : centerX - width / 2;
        final float labelY = directionY > 0 ? centerY + gap + height / 2 : directionY < 0 ? centerY - gap - height / 2 : centerY;
        return new float[]{labelX, labelY};
    }

    // 用 Liang-Barsky 线段裁剪算法求线段落在矩形内的长度，用于衡量站名与线路的重叠程度。
    private static float clippedSegmentLength(double x1, double y1, double x2, double y2, float minX, float minY, float maxX, float maxY) {
        double t0 = 0;
        double t1 = 1;
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double[] p = {-dx, dx, -dy, dy};
        final double[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                if (q[i] < 0) {
                    return 0;
                }
            } else {
                final double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) {
                        return 0;
                    }
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    if (r < t0) {
                        return 0;
                    }
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        return (float) Math.hypot(dx * (t1 - t0), dy * (t1 - t0));
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        centerX -= deltaX / scale;
        centerY -= deltaY / scale;
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY)) {
            final Tuple<Float, Float> mouse = new Tuple<>((float) mouseX - x, (float) mouseY - y);
            for (KSDRailMapGenerator.InterchangeCapsule capsule : generator.getInterchangeCapsules()) {
                if (isPointInCapsule(mouse, capsule)) {
                    final KSDStation station = generator.getRenderableStation(capsule.stationId);
                    if (station != null) {
                        updateCursor(false);
                        onClickedOnDestination.accept(station);
                        return true;
                    }
                }
            }
            mouseOnStation(new Tuple<>((float) mouseX - x, (float) mouseY - y), s -> {
                updateCursor(false);
                onClickedOnDestination.accept(s);
            });
            return true;
        } else {
            return false;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        final double oldScale = this.scale;
        if (oldScale > SCALE_LOWER_LIMIT && amount < (double) 0.0F) {
            this.centerX -= (mouseX - (double) this.x - (double) this.width / (double) 2.0F) / this.scale;
            this.centerY -= (mouseY - (double) this.y - (double) this.height / (double) 2.0F) / this.scale;
        }
        this.scale(amount);
        if (oldScale < SCALE_UPPER_LIMIT && amount > (double) 0.0F) {
            this.centerX += (mouseX - (double) this.x - (double) this.width / (double) 2.0F) / this.scale;
            this.centerY += (mouseY - (double) this.y - (double) this.height / (double) 2.0F) / this.scale;
        }
        return true;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= (double) this.x
                && mouseY >= (double) this.y
                && mouseX < (double) (this.x + this.width)
                && mouseY < (double) (this.y + this.height);
    }

    @SuppressWarnings("unused")
    public void setFocused(boolean focused) {
    }

    @SuppressWarnings("unused")
    public boolean isFocused() {
        return false;
    }

    public void setPositionAndSize(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void scale(double amount) {
        this.scale *= Math.pow(2.0F, amount);
        this.scale = Mth.clamp(this.scale, SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
    }

    private void mouseOnStation(Tuple<Float, Float> mouseCord, MouseOnStationCallback callback) {
        for (KSDRailMapGenerator.StationCircle circle : generator.getStationCircles()) {
            if (dist(new Tuple<>(circle.centerX, circle.centerY), mouseCord) <= RADIUS) {
                final KSDStation station = generator.getRenderableStation(circle.stationId);
                if (station != null) {
                    callback.mouseOnStation(station);
                }
            }
        }
    }

    public void updateCursor(boolean hovering) {
        final long window = Minecraft.getInstance().getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        if (hovering) {
            if (handCursor == 0L) {
                handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            }
            if (handCursor != 0L) {
                GLFW.glfwSetCursor(window, handCursor);
            }
        } else if (lastHovering) {
            GLFW.glfwSetCursor(window, 0L);
        }
        lastHovering = hovering;
    }

    public void load() {
        generator.load(currentStation, ticketType);
        generator.setViewTransform(scale, centerX, centerY, width, height);
    }

    private static double pointSegmentDistance(Tuple<Float, Float> point, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        final double dx = end.getA() - start.getA();
        final double dy = end.getB() - start.getB();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1E-6) {
            return dist(point, start);
        }
        double t = ((point.getA() - start.getA()) * dx + (point.getB() - start.getB()) * dy) / lengthSquared;
        t = Mth.clamp(t, 0, 1);
        final double x = start.getA() + t * dx;
        final double y = start.getB() + t * dy;
        return Math.hypot(point.getA() - x, point.getB() - y);
    }

    private static double dist(Tuple<Float, Float> corner1, Tuple<Float, Float> corner2) {
        return Math.sqrt(Math.pow(corner1.getA() - corner2.getA(), 2) + Math.pow(corner1.getB() - corner2.getB(), 2));
    }

    private static final class StationLabel {

        final String text;
        final float x;
        final float y;

        StationLabel(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    private static final class LabelBox {

        final float minX;
        final float minY;
        final float maxX;
        final float maxY;

        LabelBox(float minX, float minY, float maxX, float maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    @FunctionalInterface
    public interface MouseOnStationCallback {
        void mouseOnStation(KSDStation terminus);
    }
}
