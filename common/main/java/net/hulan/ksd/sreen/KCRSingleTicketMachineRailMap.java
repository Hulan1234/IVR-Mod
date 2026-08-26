package net.hulan.ksd.sreen;

import com.mojang.blaze3d.vertex.*;
import mtr.data.IGui;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.mappings.SelectableMapper;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.utils.DataUtilities;
import net.hulan.ksd.utils.RailDataUtilities;
import net.hulan.ksd.utils.RenderUtilities;
import net.hulan.ksd.data.KSDAreaBase;
import net.hulan.ksd.data.KSDPlatform;
import net.hulan.ksd.data.KSDRoute;
import net.hulan.ksd.data.KSDStation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class KCRSingleTicketMachineRailMap implements WidgetMapper, SelectableMapper, GuiEventListener, IGui {

    private int x;
    private int y;
    private int width;
    private int maxHeight;
    private double scale;
    private double centerX;
    private double centerY;
    private final Consumer<KSDStation> onClickedOnDestination;
    private final KCRSingleTicketMachineScreen ticketMachineScreen;
    private static final double SCALE_UPPER_LIMIT = 64F;
    private static final double SCALE_LOWER_LIMIT = 0.0078125F;
    private static final float RADIUS = 5F;
    private static final int RADIUS_PADDING = 8;
    private static final int SEGMENTS = 64;
    private static final float LINE_WIDTH = 5.0F;
    private static final float LINE_SPACING = 8.0F;
    private static final float STATION_RING_THICKNESS = 1.5F;
    // 站名文字缩放倍数：原版字体行高 9 像素，1.2 倍约等于原动态贴图（10 像素高）的显示效果
    private static final float STATION_NAME_SCALE = 1.2F;
    // 站名英文行缩放倍数（小号，置于中文行下方）
    private static final float STATION_EN_SCALE = 0.7F;
    private final Map<Long, List<RailMapStation>> layouts = new HashMap<>();
    private final Map<Long, double[]> stationOrigins = new HashMap<>();
    private final List<StationCircle> stationCircles = new ArrayList<>();
    private final List<InterchangeCapsule> interchangeCapsules = new ArrayList<>();
    private final List<Tuple<Float, Float>> logicalStationCenters = new ArrayList<>();
    private final List<List<Tuple<Float, Float>>> drawingLines = new ArrayList<>();
    private final List<Integer> drawingLineColors = new ArrayList<>();
    private final List<StationLabel> stationLabels = new ArrayList<>();
    private boolean lastHovering;
    private long handCursor;

    public KCRSingleTicketMachineRailMap(Consumer<KSDStation> onClickedOnDestination, KCRSingleTicketMachineScreen ticketMachineScreen) {
        this.onClickedOnDestination = onClickedOnDestination;
        this.ticketMachineScreen = ticketMachineScreen;
        Minecraft minecraftClient = Minecraft.getInstance();
        LocalPlayer player = minecraftClient.player;
        if (player == null) {
            centerX = 0.0F;
            centerY = 0.0F;
        } else {
            centerX = player.getX();
            centerY = player.getZ();
        }
        scale = 0.05F;
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        buildRenderData();
        stationLabels.clear();
        stationLabels.addAll(buildStationLabels());
        drawRoutes(matrices);
        drawStationsAndNames(matrices, mouseX, mouseY);
    }

    private void drawRoutes(PoseStack matrices) {
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        for (int i = 0; i < drawingLines.size(); i++) {
            List<Tuple<Float, Float>> points = drawingLines.get(i);
            int color = drawingLineColors.get(i);
            for (int j = 0; j < points.size() - 1; j++) {
                renderUtilities.drawThickLine(matrices, x + points.get(j).getA(), y + points.get(j).getB(),
                        x + points.get(j + 1).getA(), y + points.get(j + 1).getB(), LINE_WIDTH, color);
            }
            for (int j = 1; j < points.size() - 1; j++) {
                Tuple<Float, Float> point = points.get(j);
                renderUtilities.drawStationCircle(matrices, x + point.getA(), y + point.getB(),
                        LINE_WIDTH / 2, SEGMENTS, LINE_WIDTH / 2, color);
            }
        }
    }

    private void drawStationsAndNames(PoseStack matrices, int mouseX, int mouseY) {
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        Tuple<Float, Float> mouse = new Tuple<>((float) mouseX - x, (float) mouseY - y);
        InterchangeCapsule hoveredCapsule = null;
        for (InterchangeCapsule capsule : interchangeCapsules) {
            if (isPointInCapsule(mouse, capsule)) {
                hoveredCapsule = capsule;
                break;
            }
        }

        // 先确定最终颜色，再把每个胶囊完整绘制一次，避免同深度重复叠画产生闪烁和错位。
        for (InterchangeCapsule capsule : interchangeCapsules) {
            int color = capsule == hoveredCapsule ? renderUtilities.lightenColor(capsule.color) : capsule.color;
            drawInterchangeCapsule(matrices, capsule, color);
        }
        // 遍历所有车站圆
        for (StationCircle circle : stationCircles) {
            // 先画白色实心圆作为车站底色
            renderUtilities.drawStationCircle(matrices, (float) x + (float) circle.centerX, (float) y + (float) circle.centerY,
                    RADIUS, SEGMENTS, RADIUS, ARGB_WHITE);
            // 再画一圈线路颜色的圆环表示所属线路
            renderUtilities.drawStationCircle(matrices, (float) x + (float) circle.centerX, (float) y + (float) circle.centerY,
                    RADIUS, SEGMENTS, STATION_RING_THICKNESS, circle.color);
        }
        AtomicBoolean hovered = new AtomicBoolean(false);
        // 检测鼠标是否落在任一车站圆内，若命中则高亮该站的所有圆环
        mouseOnStation(stationCircles, new Tuple<>((float) mouseX - x, (float) mouseY - y), station -> {
            // 标记为悬停状态
            hovered.set(true);
            // 遍历所有车站圆，把与命中站同 id 的圆环提亮
            for (StationCircle circle : stationCircles) {
                // 判断圆是否属于被命中的车站
                if (circle.stationId == station.id) {
                    // 用提亮后的颜色重画该圆的圆环
                    renderUtilities.drawStationCircle(matrices, (float) x + (float) circle.centerX, (float) y + (float) circle.centerY,
                            RADIUS, SEGMENTS, STATION_RING_THICKNESS, renderUtilities.lightenColor(circle.color));
                }
            }
        });
        if (hoveredCapsule != null) {
            hovered.set(true);
        }
        // 根据悬停状态更新鼠标指针（悬停时显示小手）
        updateCursor(hovered.get());
        for (StationLabel label : stationLabels) {
            RenderUtilities.getInstance().drawTextCjk(matrices, label.text,
                    x + label.x, y + label.y, STATION_NAME_SCALE, STATION_EN_SCALE, ARGB_BLACK);
        }
    }

    private List<StationLabel> buildStationLabels() {
        List<StationLabel> labels = new ArrayList<>();
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        List<LabelBox> placedLabels = new ArrayList<>();
        for (KSDStation station : ticketMachineScreen.mainStations) {
            if (KSDAreaBase.nonNullCorners(station)) {
                BlockPos pos = station.getCenter();
                // 取该站的平台中点平均值作为文字锚点（若有）
                double[] origin = stationOrigins.get(station.id);
                // 无平台数据时退回车站中心 X
                double originX = origin != null ? origin[0] : pos.getX();
                // 无平台数据时退回车站中心 Z
                double originZ = origin != null ? origin[1] : pos.getZ();
                // 站名原文（「中文||English」形式）
                String name = station.name;
                // 测量「中文在上、英文在下」两行站名的整体宽高（供避让计算）
                float[] textSize = renderUtilities.getTextSizeCjk(name, STATION_NAME_SCALE, STATION_EN_SCALE);
                // 站名标签整体宽度
                float textWidth = textSize[0];
                // 站名标签整体高度
                float textHeight = textSize[1];
                Tuple<Float, Float> labelAnchor = getVisibleStationCenter(station.id);
                // 优先以避让后的实际圆圈质心作为站名锚点，无可见圆圈时退回世界坐标。
                if (labelAnchor == null) {
                    Tuple<Double, Double> fallback = worldPosToCords(originX, originZ);
                    labelAnchor = new Tuple<>(fallback.getA().floatValue(), fallback.getB().floatValue());
                }
                if (RailwayData.isBetween(labelAnchor.getA(), 0.0F, width)
                        && RailwayData.isBetween(labelAnchor.getB(), 0.0F, maxHeight)) {
                    float anchorX = labelAnchor.getA();
                    float anchorY = labelAnchor.getB();
                            // 八个候选方位，优先水平两侧，再尝试上下及四个对角方向。
                            float[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
                            // 已记录的最优重叠得分（越小越干净）
                            float bestScore = Float.MAX_VALUE;
                            // 最优方位序号
                            int bestSide = 0;
                            // 依次评估每个方位
                            for (int i = 0; i < sides.length; i++) {
                                float[] position = getLabelPosition(anchorX, anchorY, textWidth, textHeight, sides[i][0], sides[i][1]);
                                float labelX = position[0];
                                float labelY = position[1];
                                // 同时检查所有线路、主/辅助逻辑站点和已经放置的站名。
                                float score = labelOverlapScore(labelX, labelY, textWidth, textHeight, placedLabels);
                                // 无任何重叠时直接选中该方位并结束评估
                                if (score == 0) {
                                    bestSide = i;
                                    break;
                                }
                                // 有重叠时记录重叠最少的方位
                                if (score < bestScore) {
                                    bestScore = score;
                                    bestSide = i;
                                }
                            }
                            // 站名始终绘制：优先无重叠方位，全部重叠时选重叠最少的方位
                            // （y 为两行文字整体的垂直中心，中文在上英文在下）
                            float[] position = getLabelPosition(anchorX, anchorY, textWidth, textHeight,
                                    sides[bestSide][0], sides[bestSide][1]);
                            placedLabels.add(new LabelBox(position[0], position[1] - textHeight / 2,
                                    position[0] + textWidth, position[1] + textHeight / 2));
                            labels.add(new StationLabel(name, position[0], position[1]));
                }
            }
        }
        return labels;
    }

    private void drawInterchangeCapsule(PoseStack matrices, InterchangeCapsule capsule, int color) {
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        float x1 = x + capsule.start.getA();
        float y1 = y + capsule.start.getB();
        float x2 = x + capsule.end.getA();
        float y2 = y + capsule.end.getB();
        float innerRadius = RADIUS - STATION_RING_THICKNESS;

        renderUtilities.drawThickLine(matrices, x1, y1, x2, y2, RADIUS * 2, color);
        renderUtilities.drawStationCircle(matrices, x1, y1, RADIUS, SEGMENTS, RADIUS, color);
        renderUtilities.drawStationCircle(matrices, x2, y2, RADIUS, SEGMENTS, RADIUS, color);
        renderUtilities.drawThickLine(matrices, x1, y1, x2, y2, innerRadius * 2, ARGB_WHITE);
        renderUtilities.drawStationCircle(matrices, x1, y1, innerRadius, SEGMENTS, innerRadius, ARGB_WHITE);
        renderUtilities.drawStationCircle(matrices, x2, y2, innerRadius, SEGMENTS, innerRadius, ARGB_WHITE);
    }

    private static boolean isPointInCapsule(Tuple<Float, Float> point, InterchangeCapsule capsule) {
        return pointSegmentDistance(point, capsule.start, capsule.end) <= RADIUS;
    }

    private Tuple<Float, Float> getVisibleStationCenter(long stationId) {
        float sumX = 0;
        float sumY = 0;
        int count = 0;
        for (StationCircle circle : stationCircles) {
            if (circle.stationId == stationId) {
                sumX += (float) circle.centerX;
                sumY += (float) circle.centerY;
                count++;
            }
        }
        for (InterchangeCapsule capsule : interchangeCapsules) {
            if (capsule.stationId == stationId) {
                sumX += capsule.mainCenter.getA();
                sumY += capsule.mainCenter.getB();
                count++;
            }
        }
        return count == 0 ? null : new Tuple<>(sumX / count, sumY / count);
    }

    // 计算某个站名标签的包围盒（左上角 labelX,labelY，宽 textWidth、高 textHeight，垂直居中）与所有线路线段的重叠总长度。
    // 得分越小摆放越干净：0 表示完全无重叠，用于站名避让时选出最优方位。
    private float labelOverlapScore(float labelX, float labelY, float textWidth, float textHeight, List<LabelBox> placedLabels) {
        // 包围盒左边界
        float padding = 2;
        float minX = labelX - padding;
        // 包围盒右边界
        float maxX = labelX + textWidth + padding;
        // 包围盒上边界（文字垂直居中）
        float minY = labelY - textHeight / 2 - padding;
        // 包围盒下边界
        float maxY = labelY + textHeight / 2 + padding;
        // 重叠总长度累加器
        float total = 0;
        // 遍历每一条线路折线
        for (List<Tuple<Float, Float>> points : drawingLines) {
            // 遍历该折线的每一段
            for (int i = 0; i < points.size() - 1; i++) {
                // 段起点
                Tuple<Float, Float> a = points.get(i);
                // 段终点
                Tuple<Float, Float> b = points.get(i + 1);
                // 累加该线段落在包围盒内的长度
                total += clippedSegmentLength(a.getA(), a.getB(), b.getA(), b.getB(),
                        minX - LINE_WIDTH / 2, minY - LINE_WIDTH / 2, maxX + LINE_WIDTH / 2, maxY + LINE_WIDTH / 2) * 10;
            }
        }
        for (InterchangeCapsule capsule : interchangeCapsules) {
            total += clippedSegmentLength(capsule.start.getA(), capsule.start.getB(), capsule.end.getA(), capsule.end.getB(),
                    minX - RADIUS, minY - RADIUS, maxX + RADIUS, maxY + RADIUS) * 100;
        }
        // 辅助站虽然不画圆，也必须为其线路端点保留空间。
        for (Tuple<Float, Float> center : logicalStationCenters) {
            double closestX = Mth.clamp(center.getA(), minX, maxX);
            double closestY = Mth.clamp(center.getB(), minY, maxY);
            if (Math.hypot(center.getA() - closestX, center.getB() - closestY) < RADIUS + padding) {
                total += 10000;
            }
        }
        for (LabelBox box : placedLabels) {
            float overlapWidth = Math.max(0, Math.min(maxX, box.maxX) - Math.max(minX, box.minX));
            float overlapHeight = Math.max(0, Math.min(maxY, box.maxY) - Math.max(minY, box.minY));
            total += overlapWidth * overlapHeight * 100;
        }
        // 返回重叠总长度
        return total;
    }

    private static float[] getLabelPosition(float centerX, float centerY, float width, float height, float directionX, float directionY) {
        float gap = RADIUS + RADIUS_PADDING;
        float labelX = directionX > 0 ? centerX + gap : directionX < 0 ? centerX - gap - width : centerX - width / 2;
        float labelY = directionY > 0 ? centerY + gap + height / 2 : directionY < 0 ? centerY - gap - height / 2 : centerY;
        return new float[]{labelX, labelY};
    }

    // 用 Liang-Barsky 线段裁剪算法求线段 (x1,y1)-(x2,y2) 落在矩形 [minX,maxX]x[minY,maxY] 内的长度，
    // 完全在矩形外返回 0，用于衡量站名与线路的重叠程度。
    private static float clippedSegmentLength(double x1, double y1, double x2, double y2, float minX, float minY, float maxX, float maxY) {
        // 线段进入矩形的参数 t 下界，从 0 开始
        double t0 = 0;
        // 线段离开矩形的参数 t 上界，到 1 结束
        double t1 = 1;
        // 线段在 X 方向上的增量
        double dx = x2 - x1;
        // 线段在 Y 方向上的增量
        double dy = y2 - y1;
        // Liang-Barsky 的 p 系数：-dx, dx, -dy, dy 对应四条裁剪边
        double[] p = {-dx, dx, -dy, dy};
        // Liang-Barsky 的 q 系数：起点到四条边界的距离
        double[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};
        // 依次处理四条裁剪边
        for (int i = 0; i < 4; i++) {
            // 该边与线段方向平行（p 接近 0）
            if (Math.abs(p[i]) < 1e-9) {
                // 起点已在该边外侧，线段整体在矩形外
                if (q[i] < 0) {
                    return 0;
                }
            } else {
                // 计算线段与该裁剪边的交点参数
                double r = q[i] / p[i];
                // p < 0 表示从矩形外进入内部，更新下界 t0
                if (p[i] < 0) {
                    // 交点比上界还远，线段已被完全裁剪掉
                    if (r > t1) {
                        return 0;
                    }
                    // 交点推进下界
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    // p > 0 表示从矩形内离开外部，更新上界 t1
                    if (r < t0) {
                        return 0;
                    }
                    // 交点回收上界
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        // 把裁剪后的参数区间换算成实际线段长度
        return (float) Math.hypot(dx * (t1 - t0), dy * (t1 - t0));
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        centerX -= deltaX / scale;
        centerY -= deltaY / scale;
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY)) {
            Tuple<Float, Float> mouse = new Tuple<>((float) mouseX - x, (float) mouseY - y);
            for (InterchangeCapsule capsule : interchangeCapsules) {
                if (isPointInCapsule(mouse, capsule)) {
                    KSDStation station = getMainStation(capsule.stationId);
                    if (station != null) {
                        updateCursor(false);
                        onClickedOnDestination.accept(station);
                        return true;
                    }
                }
            }
            mouseOnStation(stationCircles, new Tuple<>((float) mouseX - x, (float) mouseY - y), s -> {
                updateCursor(false);
                onClickedOnDestination.accept(s);
            });
            return true;
        } else {
            return false;
        }
    }

    private KSDStation getMainStation(long stationId) {
        for (KSDStation station : ticketMachineScreen.mainStations) {
            if (station.id == stationId) {
                return station;
            }
        }
        return null;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double oldScale = this.scale;
        if (oldScale > SCALE_LOWER_LIMIT && amount < (double) 0.0F) {
            this.centerX -= (mouseX - (double) this.x - (double) this.width / (double) 2.0F) / this.scale;
            this.centerY -= (mouseY - (double) this.y - (double) this.maxHeight / (double) 2.0F) / this.scale;
        }
        this.scale(amount);
        if (oldScale < SCALE_UPPER_LIMIT && amount > (double) 0.0F) {
            this.centerX += (mouseX - (double) this.x - (double) this.width / (double) 2.0F) / this.scale;
            this.centerY += (mouseY - (double) this.y - (double) this.maxHeight / (double) 2.0F) / this.scale;
        }
        return true;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= (double) this.x
                && mouseY >= (double) this.y
                && mouseX < (double) (this.x + this.width)
                && mouseY < (double) (this.y + this.maxHeight);
    }

    public void setFocused(boolean focused) {
    }

    public boolean isFocused() {
        return false;
    }

    // 重建线路图布局数据：计算各站在屏幕上的圆位置、每条线路的折线顶点列表
    private void buildRenderData() {
        stationCircles.clear();
        interchangeCapsules.clear();
        logicalStationCenters.clear();
        drawingLines.clear();
        drawingLineColors.clear();
        Map<Long, Map<String, Tuple<Float, Float>>> endpointsByStation = new HashMap<>();
        Map<Long, List<Tuple<Float, Float>>> centersByStation = new HashMap<>();
        Map<Long, List<MainStationEndpoint>> mainEndpointsByStation = new HashMap<>();
        Map<Long, InterchangeCapsule> capsulesByStation = new HashMap<>();
        Set<String> visibleCircleKeys = new HashSet<>();
        Set<String> drawnPairs = new HashSet<>();

        // 严格按主线路、其他线路、轻轨线路的顺序逐条完成站点与线段布局。
        for (KSDRoute route : getAllRoutes()) {
            List<RailMapStation> railMapStations = layouts.get(route.id);
            if (railMapStations == null || railMapStations.size() < 2) {
                continue;
            }
            String lineKey = RailDataUtilities.getRouteKey(route);
            boolean mainRoute = ticketMachineScreen.mainRoutes.contains(route);
            List<Tuple<Float, Float>> path = new ArrayList<>();

            // 先为本线路依次放置站点；候选位置只参考此前已经登记的线路和站点。
            for (RailMapStation railMapStation : railMapStations) {
                long stationId = railMapStation.station.id;
                Map<String, Tuple<Float, Float>> endpoints = endpointsByStation.computeIfAbsent(stationId, key -> new HashMap<>());
                Tuple<Float, Float> endpoint = endpoints.get(lineKey);
                if (endpoint == null) {
                    Tuple<Float, Float> preferred = getPreferredStationPosition(railMapStation);
                    List<MainStationEndpoint> mainEndpoints = mainEndpointsByStation.get(stationId);
                    if (!mainRoute && mainEndpoints != null && !mainEndpoints.isEmpty()) {
                        endpoint = placeAuxiliaryEndpointInCapsule(preferred, stationId, railMapStation.direction,
                                centersByStation, mainEndpoints, capsulesByStation);
                    } else {
                        endpoint = chooseStationPosition(preferred, stationId, railMapStation.direction, centersByStation);
                    }
                    endpoints.put(lineKey, endpoint);
                    centersByStation.computeIfAbsent(stationId, key -> new ArrayList<>()).add(endpoint);
                    logicalStationCenters.add(endpoint);
                    if (mainRoute) {
                        mainEndpointsByStation.computeIfAbsent(stationId, key -> new ArrayList<>())
                                .add(new MainStationEndpoint(lineKey, endpoint, railMapStation.direction, getDrawingColor(route)));
                    }
                }
                path.add(endpoint);

                // 只有主线路站点生成可见圆圈；辅助站点只登记逻辑端点。
                String circleKey = stationId + ":" + lineKey;
                if (mainRoute
                        && ticketMachineScreen.mainStations.contains(railMapStation.station)
                        && visibleCircleKeys.add(circleKey)) {
                    stationCircles.add(new StationCircle(stationId, endpoint.getA(), endpoint.getB(), getDrawingColor(route)));
                }
            }

            // 本线路站点确定后立即生成线段，供下一条线路的站点和线段避让。
            for (int i = 0; i < path.size() - 1; i++) {
                String pairKey = stationPairKey(railMapStations.get(i).station.id, railMapStations.get(i + 1).station.id);
                if (!drawnPairs.add(lineKey + ":" + pairKey)) {
                    continue;
                }
                Tuple<Float, Float> p0 = path.get(i);
                Tuple<Float, Float> p3 = path.get(i + 1);
                if (dist(p0, p3) < 0.001) {
                    continue;
                }
                List<Tuple<Float, Float>> curve = createNonOverlappingThreePartLine(p0, p3);
                drawingLines.add(curve);
                drawingLineColors.add(getDrawingColor(route));
            }
        }

        // 胶囊已经包含其锚定主线路圆，原位置不再重复绘制普通圆圈。
        stationCircles.removeIf(circle -> interchangeCapsules.stream().anyMatch(capsule ->
                capsule.stationId == circle.stationId
                        && dist(capsule.mainCenter, new Tuple<>((float) circle.centerX, (float) circle.centerY)) < 0.001));
    }

    /** Returns routes in deterministic drawing priority: main network, other network, then light rail. */
    private List<KSDRoute> getAllRoutes() {
        List<KSDRoute> routes = new ArrayList<>();
        addSortedRoutes(routes, ticketMachineScreen.mainRoutes);
        addSortedRoutes(routes, ticketMachineScreen.otherRoutes);
        addSortedRoutes(routes, ticketMachineScreen.lightRailRoutes);
        return routes;
    }

    /** Adds one route group in stable key/id order so incremental collision decisions remain reproducible. */
    private static void addSortedRoutes(List<KSDRoute> target, Set<KSDRoute> source) {
        List<KSDRoute> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparing(RailDataUtilities::getRouteKey).thenComparingLong(route -> route.id));
        target.addAll(sorted);
    }

    /** Converts this route's own platform midpoint (or station fallback) into local map coordinates. */
    private Tuple<Float, Float> getPreferredStationPosition(RailMapStation railMapStation) {
        KSDStation station = railMapStation.station;
        BlockPos pos = station.getCenter();
        double[] origin = stationOrigins.get(station.id);
        double worldX = railMapStation.platformMid != null ? railMapStation.platformMid.getX() : origin == null ? pos.getX() : origin[0];
        double worldZ = railMapStation.platformMid != null ? railMapStation.platformMid.getZ() : origin == null ? pos.getZ() : origin[1];
        Tuple<Double, Double> point = worldPosToCords(worldX, worldZ);
        return new Tuple<>(point.getA().floatValue(), point.getB().floatValue());
    }

    /** Assigns an auxiliary route endpoint to the existing station capsule, extending one axis as required. */
    private Tuple<Float, Float> placeAuxiliaryEndpointInCapsule(Tuple<Float, Float> preferred, long stationId, int direction,
                                                                Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                                                List<MainStationEndpoint> mainEndpoints,
                                                                Map<Long, InterchangeCapsule> capsulesByStation) {
        InterchangeCapsule capsule = capsulesByStation.get(stationId);
        if (capsule == null) {
            capsule = createInterchangeCapsule(preferred, stationId, direction, centersByStation, mainEndpoints);
            capsulesByStation.put(stationId, capsule);
            interchangeCapsules.add(capsule);
            return capsule.auxiliaryCenters.get(0);
        }

        Tuple<Float, Float> startCandidate;
        Tuple<Float, Float> endCandidate;
        float spacing = RADIUS * 2;
        if (capsule.horizontal) {
            startCandidate = new Tuple<>(capsule.start.getA() - spacing, capsule.start.getB());
            endCandidate = new Tuple<>(capsule.end.getA() + spacing, capsule.end.getB());
        } else {
            startCandidate = new Tuple<>(capsule.start.getA(), capsule.start.getB() - spacing);
            endCandidate = new Tuple<>(capsule.end.getA(), capsule.end.getB() + spacing);
        }

        double startScore = capsulePlacementScore(capsule, startCandidate, capsule.end, preferred, stationId, centersByStation);
        double endScore = capsulePlacementScore(capsule, capsule.start, endCandidate, preferred, stationId, centersByStation);
        Tuple<Float, Float> endpoint;
        if (startScore <= endScore) {
            capsule.start = startCandidate;
            endpoint = startCandidate;
        } else {
            capsule.end = endCandidate;
            endpoint = endCandidate;
        }
        capsule.auxiliaryCenters.add(endpoint);
        capsule.memberCenters.add(endpoint);
        return endpoint;
    }

    /** Creates the station's single capsule using fixed route directions and the closest axis endpoint. */
    private InterchangeCapsule createInterchangeCapsule(Tuple<Float, Float> preferred, long stationId, int direction,
                                                         Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                                         List<MainStationEndpoint> mainEndpoints) {
        InterchangeCapsule best = null;
        Tuple<Float, Float> bestEndpoint = null;
        double bestScore = Double.MAX_VALUE;
        float spacing = RADIUS * 2;
        List<Integer> auxiliaryDirections = getAuxiliaryDirections(stationId);

        for (MainStationEndpoint mainEndpoint : mainEndpoints) {
            boolean horizontal = isCapsuleHorizontal(mainEndpoint, auxiliaryDirections, preferred);
            for (int sign : new int[]{-1, 1}) {
                Tuple<Float, Float> endpoint = horizontal
                        ? new Tuple<>(mainEndpoint.center.getA() + sign * spacing, mainEndpoint.center.getB())
                        : new Tuple<>(mainEndpoint.center.getA(), mainEndpoint.center.getB() + sign * spacing);
                Tuple<Float, Float> start = horizontal
                        ? (endpoint.getA() < mainEndpoint.center.getA() ? endpoint : mainEndpoint.center)
                        : (endpoint.getB() < mainEndpoint.center.getB() ? endpoint : mainEndpoint.center);
                Tuple<Float, Float> end = start == endpoint ? mainEndpoint.center : endpoint;
                InterchangeCapsule candidate = new InterchangeCapsule(stationId, mainEndpoint.center, start, end,
                        horizontal, mainEndpoint.color);
                candidate.memberCenters.add(candidate.mainCenter);
                double score = capsulePlacementScore(candidate, start, end, preferred, stationId, centersByStation);
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestEndpoint = endpoint;
                }
            }
        }

        MainStationEndpoint fallback = mainEndpoints.get(0);
        if (best == null || bestEndpoint == null) {
            boolean horizontal = isCapsuleHorizontal(fallback, auxiliaryDirections, preferred);
            bestEndpoint = horizontal
                    ? new Tuple<>(fallback.center.getA() + spacing, fallback.center.getB())
                    : new Tuple<>(fallback.center.getA(), fallback.center.getB() + spacing);
            best = new InterchangeCapsule(stationId, fallback.center, fallback.center, bestEndpoint, horizontal, fallback.color);
        }
        best.auxiliaryCenters.add(bestEndpoint);
        if (!best.containsMember(best.mainCenter)) {
            best.memberCenters.add(best.mainCenter);
        }
        if (!best.containsMember(bestEndpoint)) {
            best.memberCenters.add(bestEndpoint);
        }
        return best;
    }

    /** Collects fixed directions of all auxiliary routes serving one station. */
    private List<Integer> getAuxiliaryDirections(long stationId) {
        List<Integer> directions = new ArrayList<>();
        addRouteDirectionsAtStation(directions, ticketMachineScreen.otherRoutes, stationId);
        addRouteDirectionsAtStation(directions, ticketMachineScreen.lightRailRoutes, stationId);
        return directions;
    }

    /** Looks up the snapped direction of each route at the requested station. */
    private void addRouteDirectionsAtStation(List<Integer> directions, Set<KSDRoute> routes, long stationId) {
        for (KSDRoute route : routes) {
            List<RailMapStation> stations = layouts.get(route.id);
            if (stations == null) {
                continue;
            }
            for (RailMapStation station : stations) {
                if (station.station.id == stationId) {
                    directions.add(station.direction);
                    break;
                }
            }
        }
    }

    /** Selects capsule axis from route-normal weights; ties follow the main route for stability. */
    private static boolean isCapsuleHorizontal(MainStationEndpoint mainEndpoint, List<Integer> auxiliaryDirections,
                                               Tuple<Float, Float> auxiliaryPreferred) {
        double horizontalWeight = normalHorizontalWeight(mainEndpoint.direction);
        double verticalWeight = normalVerticalWeight(mainEndpoint.direction);
        for (int direction : auxiliaryDirections) {
            horizontalWeight += normalHorizontalWeight(direction);
            verticalWeight += normalVerticalWeight(direction);
        }
        if (Math.abs(horizontalWeight - verticalWeight) < 0.001) {
            return normalHorizontalWeight(mainEndpoint.direction) >= normalVerticalWeight(mainEndpoint.direction);
        }
        return horizontalWeight > verticalWeight;
    }

    private static double normalHorizontalWeight(int direction) {
        return Math.abs(Math.sin(Math.toRadians(direction)));
    }

    private static double normalVerticalWeight(int direction) {
        return Math.abs(Math.cos(Math.toRadians(direction)));
    }

    /** Scores only proximity to the auxiliary route anchor; capsule collision avoidance is intentionally disabled. */
    private double capsulePlacementScore(InterchangeCapsule capsule, Tuple<Float, Float> start, Tuple<Float, Float> end,
                                         Tuple<Float, Float> preferred, long stationId,
                                         Map<Long, List<Tuple<Float, Float>>> centersByStation) {
        Tuple<Float, Float> newEndpoint = dist(start, capsule.start) > 0.001 ? start
                : dist(end, capsule.end) > 0.001 ? end
                : dist(start, capsule.mainCenter) > dist(end, capsule.mainCenter) ? start : end;
        return dist(newEndpoint, preferred);
    }

    /** Places an interchange circle along its route normal while retaining general station collision scoring. */
    private Tuple<Float, Float> chooseStationPosition(Tuple<Float, Float> preferred, long stationId, int direction,
                                                      Map<Long, List<Tuple<Float, Float>>> centersByStation) {
        List<Tuple<Float, Float>> sameStationCenters = centersByStation.get(stationId);
        if (sameStationCenters == null || sameStationCenters.isEmpty()) {
            return chooseFreeStationPosition(preferred, stationId, direction, centersByStation, null);
        }

        Tuple<Float, Float> best = null;
        double bestScore = Double.MAX_VALUE;
        double spacing = RADIUS * 2;

        // 换乘圆只沿本线路法线两侧选点，使圆组方向跟随线路走向。
        for (Tuple<Float, Float> center : sameStationCenters) {
            for (int ring = 1; ring <= sameStationCenters.size() + 1; ring++) {
                for (int sign : new int[]{-1, 1}) {
                    Tuple<Float, Float> candidate = offsetByRouteDirection(center, direction, spacing * ring, true, sign);
                    double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best == null ? preferred : best;
    }

    /** Finds a free location, preferring route-normal movement over movement along the route. */
    private Tuple<Float, Float> chooseFreeStationPosition(Tuple<Float, Float> preferred, long stationId, int direction,
                                                          Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                                          List<Tuple<Float, Float>> sameStationCenters) {
        Tuple<Float, Float> best = preferred;
        double bestScore = stationPositionScore(preferred, preferred, stationId, centersByStation, sameStationCenters);

        // 首选原锚点；冲突时先沿线路法线，再沿线路本身寻找最近空位。
        for (int ring = 1; ring <= 4; ring++) {
            double distance = ring * (RADIUS * 2 + 2);
            for (boolean normal : new boolean[]{true, false}) {
                for (int sign : new int[]{-1, 1}) {
                    Tuple<Float, Float> candidate = offsetByRouteDirection(preferred, direction, distance, normal, sign);
                    double score = stationPositionScore(candidate, preferred, stationId, centersByStation, sameStationCenters)
                            + (normal ? 0 : distance * 10);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (bestScore < 1) {
                break;
            }
        }
        return best;
    }

    /** Produces a local point offset along a route or its perpendicular normal. */
    private static Tuple<Float, Float> offsetByRouteDirection(Tuple<Float, Float> center, int direction,
                                                              double distance, boolean normal, int sign) {
        double[] offset = normal
                ? rotatePoint(0, sign * distance, direction)
                : rotatePoint(sign * distance, 0, direction);
        return new Tuple<>((float) (center.getA() + offset[0]), (float) (center.getB() + offset[1]));
    }

    /** Penalizes movement, station overlap, existing lines and capsules when choosing a station endpoint. */
    private double stationPositionScore(Tuple<Float, Float> candidate, Tuple<Float, Float> preferred, long stationId,
                                        Map<Long, List<Tuple<Float, Float>>> centersByStation,
                                        List<Tuple<Float, Float>> sameStationCenters) {
        double score = dist(candidate, preferred);
        double sameStationSpacing = RADIUS * 2;
        double otherStationSpacing = RADIUS * 2 + 2;

        for (Map.Entry<Long, List<Tuple<Float, Float>>> entry : centersByStation.entrySet()) {
            for (Tuple<Float, Float> center : entry.getValue()) {
                double distance = dist(candidate, center);
                double required = entry.getKey() == stationId ? sameStationSpacing : otherStationSpacing;
                if (distance < required - 0.01) {
                    score += (required - distance) * 10000;
                }
            }
        }

        // 同站候选优先与至少一个既有圆心相切，不在圆组外留下明显缝隙。
        if (sameStationCenters != null && !sameStationCenters.isEmpty()) {
            double nearest = Double.MAX_VALUE;
            for (Tuple<Float, Float> center : sameStationCenters) {
                nearest = Math.min(nearest, dist(candidate, center));
            }
            score += Math.abs(nearest - sameStationSpacing) * 100;
        }

        double lineClearance = RADIUS + LINE_WIDTH / 2 + 2;
        for (List<Tuple<Float, Float>> line : drawingLines) {
            for (int i = 0; i < line.size() - 1; i++) {
                double distance = pointSegmentDistance(candidate, line.get(i), line.get(i + 1));
                if (distance < lineClearance) {
                    score += (lineClearance - distance) * 1000;
                }
            }
        }
        for (InterchangeCapsule capsule : interchangeCapsules) {
            double distance = pointSegmentDistance(candidate, capsule.start, capsule.end);
            if (capsule.stationId != stationId && distance < lineClearance) {
                score += (lineClearance - distance) * 1000;
            }
        }
        return score;
    }

    /** Applies category colors while preserving each main route's configured color. */
    private int getDrawingColor(KSDRoute route) {
        if (ticketMachineScreen.otherRoutes.contains(route)) {
            return argb(0x808080);
        }
        if (ticketMachineScreen.lightRailRoutes.contains(route)) {
            return argb(0xF98C2B);
        }
        return argb(route.color);
    }

    // 根据两站圆心连线的正切值，生成水平/斜线/水平或竖直/斜线/竖直的三段线路。
    private static void addThreePartLine(List<Tuple<Float, Float>> curve, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        addThreePartLine(curve, start, end, 0.5);
    }

    private static void addThreePartLine(List<Tuple<Float, Float>> curve, Tuple<Float, Float> start, Tuple<Float, Float> end, double split) {
        double x1 = start.getA();
        double y1 = start.getB();
        double x2 = end.getA();
        double y2 = end.getB();
        double dx = x2 - x1;
        double dy = y2 - y1;
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);

        curve.add(start);

        // 同列时直接画竖直线，同排时直接画水平线。
        if (absDx < 0.001 || absDy < 0.001) {
            curve.add(end);
            return;
        }

        double signX = Math.signum(dx);
        double signY = Math.signum(dy);
        double tan = absDy / absDx;

        if (tan < 1) {
            // 两侧为水平线，中间斜线的横向和纵向位移相等。
            double remaining = absDx - absDy;
            double firstLength = remaining * split;
            double lastLength = remaining - firstLength;
            curve.add(new Tuple<>((float) (x1 + signX * firstLength), (float) y1));
            curve.add(new Tuple<>((float) (x2 - signX * lastLength), (float) y2));
        } else {
            // 两侧为竖直线，中间斜线的横向和纵向位移相等。
            double remaining = absDy - absDx;
            double firstLength = remaining * split;
            double lastLength = remaining - firstLength;
            curve.add(new Tuple<>((float) x1, (float) (y1 + signY * firstLength)));
            curve.add(new Tuple<>((float) x2, (float) (y2 - signY * lastLength)));
        }
        curve.add(end);
    }

    /** Chooses the least-conflicting split among valid horizontal/diagonal/vertical three-part paths. */
    private List<Tuple<Float, Float>> createNonOverlappingThreePartLine(Tuple<Float, Float> start, Tuple<Float, Float> end) {
        double[] splits = {0.5, 0.25, 0.75, 0, 1};
        List<Tuple<Float, Float>> best = null;
        double bestScore = Double.MAX_VALUE;
        for (double split : splits) {
            List<Tuple<Float, Float>> candidate = new ArrayList<>();
            addThreePartLine(candidate, start, end, split);
            double score = routeOverlapScore(candidate, start, end);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? new ArrayList<>() : best;
    }

    /** Measures a candidate route against previously accepted routes, capsules and station centers. */
    private double routeOverlapScore(List<Tuple<Float, Float>> candidate, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        double score = 0;
        double clearance = LINE_WIDTH + 2;
        for (int i = 0; i < candidate.size() - 1; i++) {
            Tuple<Float, Float> a = candidate.get(i);
            Tuple<Float, Float> b = candidate.get(i + 1);
            for (List<Tuple<Float, Float>> existing : drawingLines) {
                for (int j = 0; j < existing.size() - 1; j++) {
                    // 同一线路或共线换乘在共同端点处正常接续，不视为碰撞。
                    if (shareEndpoint(a, b, existing.get(j), existing.get(j + 1))) {
                        continue;
                    }
                    double distance = segmentDistance(a, b, existing.get(j), existing.get(j + 1));
                    if (distance < clearance) {
                        score += (clearance - distance) * 100;
                    }
                }
            }
            for (InterchangeCapsule capsule : interchangeCapsules) {
                if (shareEndpoint(a, b, capsule.start, capsule.end)) {
                    continue;
                }
                double distance = segmentDistance(a, b, capsule.start, capsule.end);
                double capsuleClearance = RADIUS + LINE_WIDTH / 2 + 2;
                if (distance < capsuleClearance) {
                    score += (capsuleClearance - distance) * 100;
                }
            }
            for (Tuple<Float, Float> center : logicalStationCenters) {
                if (dist(center, start) < 0.001 || dist(center, end) < 0.001) {
                    continue;
                }
                double distance = pointSegmentDistance(center, a, b);
                if (distance < RADIUS + LINE_WIDTH / 2 + 2) {
                    score += (RADIUS + LINE_WIDTH / 2 + 2 - distance) * 1000;
                }
            }
        }
        return score;
    }

    /** Returns the shortest distance between two finite line segments. */
    private static double segmentDistance(Tuple<Float, Float> a1, Tuple<Float, Float> a2,
                                          Tuple<Float, Float> b1, Tuple<Float, Float> b2) {
        if (segmentsIntersect(a1, a2, b1, b2)) {
            return 0;
        }
        return Math.min(Math.min(pointSegmentDistance(a1, b1, b2), pointSegmentDistance(a2, b1, b2)),
                Math.min(pointSegmentDistance(b1, a1, a2), pointSegmentDistance(b2, a1, a2)));
    }

    /** Returns the shortest distance between a point and a finite line segment. */
    private static double pointSegmentDistance(Tuple<Float, Float> point, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        double dx = end.getA() - start.getA();
        double dy = end.getB() - start.getB();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1E-6) {
            return dist(point, start);
        }
        double t = ((point.getA() - start.getA()) * dx + (point.getB() - start.getB()) * dy) / lengthSquared;
        t = Mth.clamp(t, 0, 1);
        double x = start.getA() + t * dx;
        double y = start.getB() + t * dy;
        return Math.hypot(point.getA() - x, point.getB() - y);
    }

    /** Detects finite segment intersection, including collinear endpoint contact. */
    private static boolean segmentsIntersect(Tuple<Float, Float> a1, Tuple<Float, Float> a2,
                                             Tuple<Float, Float> b1, Tuple<Float, Float> b2) {
        double d1 = cross(a1, a2, b1);
        double d2 = cross(a1, a2, b2);
        double d3 = cross(b1, b2, a1);
        double d4 = cross(b1, b2, a2);
        double epsilon = 1E-6;
        if (((d1 > epsilon && d2 < -epsilon) || (d1 < -epsilon && d2 > epsilon))
                && ((d3 > epsilon && d4 < -epsilon) || (d3 < -epsilon && d4 > epsilon))) {
            return true;
        }
        return Math.abs(d1) <= epsilon && pointOnSegment(b1, a1, a2)
                || Math.abs(d2) <= epsilon && pointOnSegment(b2, a1, a2)
                || Math.abs(d3) <= epsilon && pointOnSegment(a1, b1, b2)
                || Math.abs(d4) <= epsilon && pointOnSegment(a2, b1, b2);
    }

    /** Tests whether a collinear point lies inside a finite segment's bounding box. */
    private static boolean pointOnSegment(Tuple<Float, Float> point, Tuple<Float, Float> start, Tuple<Float, Float> end) {
        double epsilon = 1E-6;
        return point.getA() >= Math.min(start.getA(), end.getA()) - epsilon
                && point.getA() <= Math.max(start.getA(), end.getA()) + epsilon
                && point.getB() >= Math.min(start.getB(), end.getB()) - epsilon
                && point.getB() <= Math.max(start.getB(), end.getB()) + epsilon;
    }

    /** Allows route segments to meet at a shared station endpoint without treating the junction as overlap. */
    private static boolean shareEndpoint(Tuple<Float, Float> a1, Tuple<Float, Float> a2,
                                         Tuple<Float, Float> b1, Tuple<Float, Float> b2) {
        return dist(a1, b1) < 0.001 || dist(a1, b2) < 0.001 || dist(a2, b1) < 0.001 || dist(a2, b2) < 0.001;
    }

    /** Computes the 2D cross product used by segment intersection tests. */
    private static double cross(Tuple<Float, Float> start, Tuple<Float, Float> end, Tuple<Float, Float> point) {
        return (end.getA() - start.getA()) * (point.getB() - start.getB())
                - (end.getB() - start.getB()) * (point.getA() - start.getA());
    }

    public void setPositionAndSize(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.maxHeight = height;
    }

    public void scale(double amount) {
        this.scale *= Math.pow(2.0F, amount);
        this.scale = Mth.clamp(this.scale, SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
    }

    private void mouseOnStation(List<StationCircle> circles, Tuple<Float, Float> mouseCord, MouseOnStationCallback callback) {
        for (StationCircle circle : circles) {
            if (dist(new Tuple<>((float) circle.centerX, (float) circle.centerY), mouseCord) <= RADIUS) {
                for (KSDStation station : ticketMachineScreen.mainStations) {
                    if (station.id == circle.stationId) {
                        callback.mouseOnStation(station);
                        break;
                    }
                }
            }
        }
    }

    private void updateCursor(boolean hovering) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        // 悬停状态
        if (hovering) {
            // 小手光标尚未创建则创建一次并缓存
            if (handCursor == 0L) {
                handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            }
            // 创建成功则设置小手光标
            if (handCursor != 0L) {
                GLFW.glfwSetCursor(window, handCursor);
            }
        } else if (lastHovering) {
            // 之前悬停而现在离开，恢复默认光标
            GLFW.glfwSetCursor(window, 0L);
        }
        // 记录本次悬停状态供下次判断
        lastHovering = hovering;
    }

    public void load() {
        loadLayout();
    }

    // 计算线路图布局：每条线路的站点顺序、平台方向、站内分流序号与偏移
    private void loadLayout() {
        // 清空旧布局
        layouts.clear();
        // 清空站点锚点
        stationOrigins.clear();
        // 每条线路的 RailMapStation 列表
        Map<Long, List<RailMapStation>> railMapStationsByRoute = new HashMap<>();
        // 每个站点、每个方向对应的线路名列表（用于分配分流序号）
        Map<Long, Map<Integer, List<String>>> stationRouteDirections = new HashMap<>();
        // 线路 id → 线路标识
        Map<Long, String> routeLineKeys = new HashMap<>();
        // 站点 id → 平台中点坐标映射（取均值作锚点）
        Map<Long, Map<String, double[]>> platformMidsByStation = new HashMap<>();
        // 三类线路全部使用相同的站点顺序、平台锚点、方向和分流判定。
        for (KSDRoute route : getAllRoutes()) {
            // 本线路去重后的站点列表
            List<KSDStation> stationList = new ArrayList<>();
            // 各站点对应平台中点（可能为空）
            List<BlockPos> platformMids = new ArrayList<>();
            // 遍历线路的每个站台
            for (Route.RoutePlatform routePlatform : route.platformIds) {
                // 由站台 id 查站点
                KSDStation station = KSDClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
                if (KSDAreaBase.nonNullCorners(station) && isStationInRouteGroup(route, station)) {
                    // 连续相同车站跳过（站内多站台或同名同色车站）
                    if (!stationList.isEmpty() && RailDataUtilities.isSameStation(stationList.get(stationList.size() - 1), station)) {
                        continue;
                    }
                    // 加入去重站点列表
                    stationList.add(station);
                    // 取站台数据
                    KSDPlatform platform = DataUtilities.getPlatform(KSDClientData.PLATFORMS, routePlatform.platformId);
                    // 平台中点，默认为空
                    BlockPos platformMid = null;
                    // 平台数据存在时计算中点
                    if (platform != null) {
                        // 取平台中心方块位置
                        platformMid = platform.getMidPos();
                    }
                    // 记录平台中点
                    platformMids.add(platformMid);
                }
            }
            // 站点不足两个无法成线
            if (stationList.size() < 2) {
                continue;
            }
            // 线路标识：颜色+中文线路名
            String lineKey = RailDataUtilities.getRouteKey(route);
            // 记录线路标识
            routeLineKeys.put(route.id, lineKey);
            // 构建 RailMapStation 列表
            List<RailMapStation> railMapStations = new ArrayList<>();
            // 为每个站点生成布局数据
            for (int i = 0; i < stationList.size(); i++) {
                // 新建布局站点对象
                RailMapStation railMapStation = new RailMapStation(stationList.get(i));
                // 计算站点处线路的方向（45 度快照）
                railMapStation.direction = stationDirection(stationList, i);
                // 记录平台中点
                railMapStation.platformMid = platformMids.get(i);
                // 加入线路站点列表
                railMapStations.add(railMapStation);
                // 有平台中点时收集用于计算站点锚点
                if (platformMids.get(i) != null) {
                    // 取平台中点
                    BlockPos mid = platformMids.get(i);
                    // 按"x,z"去重记录该平台中点
                    platformMidsByStation.computeIfAbsent(stationList.get(i).id, key -> new HashMap<>())
                            .put(mid.getX() + "," + mid.getZ(), new double[]{mid.getX(), mid.getZ()});
                }
                // 按站点+方向登记该线路名
                List<String> lineKeys = stationRouteDirections.computeIfAbsent(stationList.get(i).id, key -> new HashMap<>())
                        .computeIfAbsent(railMapStation.direction, key -> new ArrayList<>());
                // 避免重复登记
                if (!lineKeys.contains(lineKey)) {
                    lineKeys.add(lineKey);
                }
            }
            // 保存该线路的布局站点列表
            railMapStationsByRoute.put(route.id, railMapStations);
        }
        // 计算每个站点的锚点：该站所有平台中点的平均
        for (Map.Entry<Long, Map<String, double[]>> entry : platformMidsByStation.entrySet()) {
            // X 坐标累加
            double sumX = 0;
            // Z 坐标累加
            double sumZ = 0;
            // 累加所有平台中点
            for (double[] mid : entry.getValue().values()) {
                sumX += mid[0];
                sumZ += mid[1];
            }
            // 平台中点数量
            int count = entry.getValue().size();
            // 数量大于零才计算平均
            if (count > 0) {
                stationOrigins.put(entry.getKey(), new double[]{sumX / count, sumZ / count});
            }
        }
        // 为每条线路的每个站点分配分流序号（同站同方向的线路按线路名排序）
        for (Map.Entry<Long, List<RailMapStation>> entry : railMapStationsByRoute.entrySet()) {
            // 线路 id
            long routeId = entry.getKey();
            // 遍历该线路的布局站点
            for (RailMapStation railMapStation : entry.getValue()) {
                // 取该站该方向登记的所有线路名
                List<String> lineKeys = stationRouteDirections.get(railMapStation.station.id).get(railMapStation.direction);
                // 按线路名排序，保证序号稳定
                lineKeys.sort(String::compareTo);
                // 本线路在排序中的位置即分流序号
                railMapStation.offsetIndex = lineKeys.indexOf(routeLineKeys.get(routeId));
                // 同方向线路总数
                railMapStation.routeCount = lineKeys.size();
            }
        }
        // 写入布局结果供渲染使用
        layouts.putAll(railMapStationsByRoute);
    }

    private Tuple<Double, Double> worldPosToCords(double worldX, double worldZ) {
        double cordsX = (worldX - centerX) * scale + (double) width / (double) 2.0F;
        double cordsY = (worldZ - centerY) * scale + (double) maxHeight / (double) 2.0F;
        return new Tuple<>(cordsX, cordsY);
    }

    // 计算某站在线路中的走向角度并快照到 45 度倍数（0~135 之间）
    private static int stationDirection(List<KSDStation> stationList, int index) {
        // 前一个站中心，默认空
        BlockPos prev;
        // 后一个站中心，默认空
        BlockPos next;
        // 首站：用第一个与第二个站确定方向
        if (index == 0) {
            prev = stationList.get(0).getCenter();
            next = stationList.get(1).getCenter();
        } else if (index == stationList.size() - 1) {
            // 末站：用倒数第二与最后一个站确定方向
            prev = stationList.get(stationList.size() - 2).getCenter();
            next = stationList.get(stationList.size() - 1).getCenter();
        } else {
            // 中间站：用前后两个站确定方向
            prev = stationList.get(index - 1).getCenter();
            next = stationList.get(index + 1).getCenter();
        }
        // 任一中心缺失时返回 0 度
        if (prev == null || next == null) {
            return 0;
        }
        // 计算前后两站的连线角度（度）
        double angle = Math.toDegrees(Math.atan2(next.getZ() - prev.getZ(), next.getX() - prev.getX()));
        // 归一到 0~180 区间（线路不分正反）
        angle = ((angle % 180) + 180) % 180;
        // 快照到最近的 45 度倍数
        int snapped = (int) Math.round(angle / 45) * 45;
        // 避免出现 180 度，归一到 0~135
        if (snapped >= 180) {
            snapped -= 180;
        }
        // 返回快照后的方向角度
        return snapped;
    }

    private boolean isStationInRouteGroup(KSDRoute route, KSDStation station) {
        if (ticketMachineScreen.mainRoutes.contains(route)) {
            return ticketMachineScreen.mainStations.contains(station);
        }
        if (ticketMachineScreen.otherRoutes.contains(route)) {
            return ticketMachineScreen.otherStations.contains(station);
        }
        if (ticketMachineScreen.lightRailRoutes.contains(route)) {
            return ticketMachineScreen.lightRailStations.contains(station);
        }
        return false;
    }

    // 生成无向的站点对 key（小的在前，大的在后）
    private static String stationPairKey(long id1, long id2) {
        // 返回 "小id,大id"
        return Math.min(id1, id2) + "," + Math.max(id1, id2);
    }

    // 把点 (x,y) 绕原点旋转 direction 度（仅支持 45 度倍数的快照角度）
    private static double[] rotatePoint(double x, double y, int direction) {
        // 把角度归一到 0~359
        int d = ((direction % 360) + 360) % 360;
        // 正弦值，由分支赋值
        double sin;
        // 余弦值，按角度分支计算（含对角线）
        double cos = switch (d) {
            // 90 度：sin=1, cos=0
            case 90 -> {
                sin = 1;
                yield 0;
            }
            // 270 度：sin=-1, cos=0
            case 270 -> {
                sin = -1;
                yield 0;
            }
            // 45 度：两者都是 √0.5
            case 45 -> {
                sin = Math.sqrt(0.5);
                yield Math.sqrt(0.5);
            }
            // 135 度：sin=√0.5, cos=-√0.5
            case 135 -> {
                sin = Math.sqrt(0.5);
                yield -Math.sqrt(0.5);
            }
            // 225 度：sin=-√0.5, cos=-√0.5
            case 225 -> {
                sin = -Math.sqrt(0.5);
                yield -Math.sqrt(0.5);
            }
            // 315 度：sin=-√0.5, cos=√0.5
            case 315 -> {
                sin = -Math.sqrt(0.5);
                yield Math.sqrt(0.5);
            }
            // 180 度：sin=0, cos=-1
            case 180 -> {
                sin = 0;
                yield -1;
            }
            // 默认 0 度：sin=0, cos=1
            default -> {
                sin = 0;
                yield 1;
            }
        };
        // 应用二维旋转公式并返回
        return new double[]{x * cos - y * sin, x * sin + y * cos};
    }

    // 把 RGB 颜色补全为不透明的 ARGB
    private static int argb(int color) {
        // 高位补 0xFF 透明度
        return 0xFF000000 | (color & 0xFFFFFF);
    }

    private static double dist(Tuple<Float, Float> corner1, Tuple<Float, Float> corner2) {
        return Math.sqrt(Math.pow(corner1.getA() - corner2.getA(), 2) + Math.pow(corner1.getB() - corner2.getB(), 2));
    }

    // 线路图布局中的单个站点：记录所属站点、平台数据与站内分流信息
    private static final class RailMapStation {

        final KSDStation station;
        BlockPos platformMid;
        int direction;
        int offsetIndex;
        int routeCount;

        // 构造器：绑定站点对象
        RailMapStation(KSDStation station) {
            // 保存站点引用
            this.station = station;
        }
    }

    // 已解析的车站圆：站点 id、圆心位置与线路颜色
    private static final class StationCircle {

        final long stationId;
        final double centerX;
        final double centerY;
        final int color;

        // 构造器：记录站点 id、圆心与颜色
        StationCircle(long stationId, double centerX, double centerY, int color) {
            this.stationId = stationId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.color = color;
        }
    }

    private static final class MainStationEndpoint {

        final String lineKey;
        final Tuple<Float, Float> center;
        final int direction;
        final int color;

        MainStationEndpoint(String lineKey, Tuple<Float, Float> center, int direction, int color) {
            this.lineKey = lineKey;
            this.center = center;
            this.direction = direction;
            this.color = color;
        }
    }

    private static final class InterchangeCapsule {

        final long stationId;
        final Tuple<Float, Float> mainCenter;
        final boolean horizontal;
        final int color;
        final List<Tuple<Float, Float>> auxiliaryCenters = new ArrayList<>();
        final List<Tuple<Float, Float>> memberCenters = new ArrayList<>();
        Tuple<Float, Float> start;
        Tuple<Float, Float> end;

        InterchangeCapsule(long stationId, Tuple<Float, Float> mainCenter, Tuple<Float, Float> start,
                           Tuple<Float, Float> end, boolean horizontal, int color) {
            this.stationId = stationId;
            this.mainCenter = mainCenter;
            this.start = start;
            this.end = end;
            this.horizontal = horizontal;
            this.color = color;
        }

        /** Returns whether a logical route endpoint belongs to this capsule. */
        boolean containsMember(Tuple<Float, Float> center) {
            for (Tuple<Float, Float> member : memberCenters) {
                if (dist(member, center) < 0.001) {
                    return true;
                }
            }
            return false;
        }
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

    // 鼠标命中车站时的回调接口
    @FunctionalInterface
    public interface MouseOnStationCallback {
        void mouseOnStation(KSDStation terminus);
    }
}
