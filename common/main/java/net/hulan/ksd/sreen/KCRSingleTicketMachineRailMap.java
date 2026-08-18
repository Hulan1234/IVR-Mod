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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class KCRSingleTicketMachineRailMap implements WidgetMapper, SelectableMapper, GuiEventListener, IGui {

    private int x;
    private int y;
    private int width;
    private int height;
    private double scale;
    private double centerX;
    private double centerY;
    private final Set<KSDRoute> lightRails = new HashSet<>();
    private final Set<KSDRoute> mtrRoutes = new HashSet<>();
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
    private final List<List<Tuple<Float, Float>>> drawingLines = new ArrayList<>();
    private final List<Integer> drawingLineColors = new ArrayList<>();
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
        // 根据当前缩放与中心重新计算所有站点/线路的位置（每次渲染都重算，保证与缩放同步）
        buildRenderData();
        RenderUtilities renderUtilities = RenderUtilities.getInstance();
        // 遍历所有线路折线
        for (int i = 0; i < drawingLines.size(); i++) {
            // 取出当前折线的所有顶点
            List<Tuple<Float, Float>> points = drawingLines.get(i);
            // 取出该线路的颜色
            int color = drawingLineColors.get(i);
            // 逐段绘制粗线段（相邻两个顶点构成一段）
            for (int j = 0; j < points.size() - 1; j++) {
                // 把线段两端点平移到控件内坐标并画成粗线
                renderUtilities.drawThickLine(matrices, x + points.get(j).getA(), y + points.get(j).getB(),
                        x + points.get(j + 1).getA(), y + points.get(j + 1).getB(), LINE_WIDTH, color);
            }
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
        // 根据悬停状态更新鼠标指针（悬停时显示小手）
        updateCursor(hovered.get());

        for (KSDStation station : ticketMachineScreen.stations) {
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
                // 把世界坐标换算成控件内坐标，若在可见范围内则尝试摆放站名
                drawFromWorldCords(
                        originX,
                        originZ,
                        (x1, y1) -> {
                            // 四个候选方位：右、左、下、上（相对车站圆心的偏移方向）
                            float[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                            // 已记录的最优重叠得分（越小越干净）
                            float bestScore = Float.MAX_VALUE;
                            // 最优方位序号
                            int bestSide = 0;
                            // 依次评估每个方位
                            for (int i = 0; i < sides.length; i++) {
                                // 候选位置 X：圆心沿该方位偏移 半径+留白
                                float labelX = x1.floatValue() + sides[i][0] * (RADIUS + RADIUS_PADDING);
                                // 候选位置 Y：圆心沿该方位偏移 半径+留白
                                float labelY = y1.floatValue() + sides[i][1] * (RADIUS + RADIUS_PADDING);
                                // 计算该位置与所有线路线段的重叠总长度
                                float score = labelOverlapScore(labelX, labelY, textWidth, textHeight);
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
                            renderUtilities.drawTextCjk(matrices, name,
                                    (float) this.x + x1.floatValue() + sides[bestSide][0] * (RADIUS + RADIUS_PADDING),
                                    (float) this.y + y1.floatValue() + sides[bestSide][1] * (RADIUS + RADIUS_PADDING),
                                    STATION_NAME_SCALE, STATION_EN_SCALE, ARGB_BLACK);
                        });
            }
        }
    }

    // 计算某个站名标签的包围盒（左上角 labelX,labelY，宽 textWidth、高 textHeight，垂直居中）与所有线路线段的重叠总长度。
    // 得分越小摆放越干净：0 表示完全无重叠，用于站名避让时选出最优方位。
    private float labelOverlapScore(float labelX, float labelY, float textWidth, float textHeight) {
        // 包围盒左边界
        float minX = labelX;
        // 包围盒右边界
        float maxX = labelX + textWidth;
        // 包围盒上边界（文字垂直居中）
        float minY = labelY - textHeight / 2;
        // 包围盒下边界
        float maxY = labelY + textHeight / 2;
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
                total += clippedSegmentLength(a.getA(), a.getB(), b.getA(), b.getB(), minX, minY, maxX, maxY);
            }
        }
        // 返回重叠总长度
        return total;
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
            mouseOnStation(stationCircles, new Tuple<>((float) mouseX - x, (float) mouseY - y), s -> {
                updateCursor(false);
                onClickedOnDestination.accept(s);
            });
            return true;
        } else {
            return false;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        double oldScale = this.scale;
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

    public void setFocused(boolean focused) {
    }

    public boolean isFocused() {
        return false;
    }

    // 重建线路图布局数据：计算各站在屏幕上的圆位置、每条线路的折线顶点列表
    private void buildRenderData() {
        // 清空上一帧的车站圆列表
        stationCircles.clear();
        // 清空上一帧的折线列表
        drawingLines.clear();
        // 清空上一帧的折线颜色列表
        drawingLineColors.clear();
        // 记录每个车站出现的所有线路候选位置（含沿线方向的偏移）
        Map<Long, List<StationPosition>> stationLines = new HashMap<>();
        // 记录每个车站每条线路(key)占用的候选序号
        Map<Long, Map<String, Integer>> stationLineKeyIndex = new HashMap<>();
        // 记录每个车站每条线路(routeId)对应的候选序号
        Map<Long, Map<Long, Integer>> stationRouteIndex = new HashMap<>();
        // 遍历所有线路
        for (KSDRoute route : ticketMachineScreen.routes) {
            // 取该线路的站点列表
            List<RailMapStation> railMapStations = layouts.get(route.id);
            // 无站点数据时跳过
            if (railMapStations == null) {
                continue;
            }
            // 线路标识（颜色+线路名）
            String lineKey = getLineKey(route);
            // 线路颜色（转为含不透明度的 ARGB）
            int lineColor = argb(route.color);
            // 遍历该线路的每个站点
            for (RailMapStation railMapStation : railMapStations) {
                // 取站点对象
                KSDStation station = railMapStation.station;
                // 站点位置非法（角点缺失）时跳过
                if (!KSDAreaBase.nonNullCorners(station)) {
                    continue;
                }
                // 取站点中心方块位置
                BlockPos pos = station.getCenter();
                // 取平台中点均值作为坐标锚点（若有）
                double[] origin = stationOrigins.get(station.id);
                // 无锚点时退回站点中心 X
                double originX = origin != null ? origin[0] : pos.getX();
                // 无锚点时退回站点中心 Z
                double originZ = origin != null ? origin[1] : pos.getZ();
                // 世界坐标 → 控件坐标
                Tuple<Double, Double> cord = worldPosToCords(originX, originZ);
                // 按线路在该站的分流序号计算垂直站台方向的偏移量
                double offset = (railMapStation.offsetIndex - (railMapStation.routeCount - 1) / 2.0) * LINE_SPACING;
                // 把偏移旋转到站点方向（45 度倍数）
                double[] rotated = rotatePoint(offset, 0, railMapStation.direction);
                // 取该站已有的线路序号映射（不存在则新建）
                Map<String, Integer> lineKeyIndex = stationLineKeyIndex.computeIfAbsent(station.id, key -> new HashMap<>());
                // 查该线路是否已在该站登记过
                Integer index = lineKeyIndex.get(lineKey);
                // 该线路在此站还未登记
                if (index == null) {
                    // 取该站的候选位置列表（不存在则新建）
                    List<StationPosition> lines = stationLines.computeIfAbsent(station.id, key -> new ArrayList<>());
                    // 新线路占用下一个序号
                    index = lines.size();
                    // 记录该线路的序号，避免重复登记
                    lineKeyIndex.put(lineKey, index);
                    // 加入该线路在此站的候选位置（站心 + 旋转后的偏移）
                    lines.add(new StationPosition(lineColor, cord.getA() + rotated[0], cord.getB() + rotated[1]));
                }
                // 记录该路线(routeId)在此站的序号，供后续取端点用
                stationRouteIndex.computeIfAbsent(station.id, key -> new HashMap<>()).put(route.id, index);
            }
        }
        // 每个车站解析后的圆位置列表
        Map<Long, List<Tuple<Float, Float>>> resolvedCirclesByStation = new HashMap<>();
        // 每个车站按路线映射到各自的圆位置
        Map<Long, Map<Long, Tuple<Float, Float>>> resolvedEndpointsByRoute = new HashMap<>();
        // 遍历每个车站的候选位置
        for (Map.Entry<Long, List<StationPosition>> entry : stationLines.entrySet()) {
            // 站点 id
            long stationId = entry.getKey();
            // 多线路时把候选位置水平排开，避免重叠
            List<Tuple<Float, Float>> resolved = resolveStationCirclePositions(entry.getValue());
            // 记录该站的圆位置列表
            resolvedCirclesByStation.put(stationId, resolved);
            // 该站的路线→圆位置映射
            Map<Long, Tuple<Float, Float>> byRoute = new HashMap<>();
            // 取该站各路线登记的序号
            Map<Long, Integer> routeIndex = stationRouteIndex.get(stationId);
            // 为每条路线建立到圆位置的映射
            for (Map.Entry<Long, Integer> routeEntry : routeIndex.entrySet()) {
                byRoute.put(routeEntry.getKey(), resolved.get(routeEntry.getValue()));
            }
            // 保存该站按路线索引的端点表
            resolvedEndpointsByRoute.put(stationId, byRoute);
        }
        // 把解析后的圆位置生成 StationCircle 供渲染与点击检测
        for (Map.Entry<Long, List<Tuple<Float, Float>>> entry : resolvedCirclesByStation.entrySet()) {
            // 取该站的候选列表（用于取颜色）
            List<StationPosition> lines = stationLines.get(entry.getKey());
            // 逐个圆生成
            for (int i = 0; i < entry.getValue().size(); i++) {
                // 该圆的位置坐标
                Tuple<Float, Float> circle = entry.getValue().get(i);
                // 加入车站圆渲染列表（含所属线路颜色）
                stationCircles.add(new StationCircle(entry.getKey(), circle.getA(), circle.getB(), lines.get(i).color));
            }
        }
        // 每条线路的端点路径（依次经过各站）
        Map<Long, List<Tuple<Float, Float>>> pathByRoute = new HashMap<>();
        // 遍历所有线路
        for (KSDRoute route : ticketMachineScreen.routes) {
            // 取该线路站点列表
            List<RailMapStation> railMapStations = layouts.get(route.id);
            // 站点不足两个时无法成线
            if (railMapStations == null || railMapStations.size() < 2) {
                continue;
            }
            // 该线路的路径顶点
            List<Tuple<Float, Float>> path = new ArrayList<>();
            // 标记路径是否有效
            boolean valid = true;
            // 逐个站点取解析后的圆位置作为路径顶点
            for (RailMapStation railMapStation : railMapStations) {
                // 取该站的路线端点映射
                Map<Long, Tuple<Float, Float>> byRoute = resolvedEndpointsByRoute.get(railMapStation.station.id);
                // 站点无端点数据则路径无效
                if (byRoute == null) {
                    valid = false;
                    break;
                }
                // 取该路线在此站的端点
                Tuple<Float, Float> resolved = byRoute.get(route.id);
                // 无该路线的端点则路径无效
                if (resolved == null) {
                    valid = false;
                    break;
                }
                // 追加到路径顶点列表
                path.add(resolved);
            }
            // 路径无效或顶点不足时跳过
            if (!valid || path.size() < 2) {
                continue;
            }
            // 保存该线路的路径
            pathByRoute.put(route.id, path);
        }
        // 记录已绘制过的"线路:站点对"，避免同一线路对同一站对重复画线
        Set<String> drawnPairs = new HashSet<>();
        // 遍历所有线路生成折线
        for (KSDRoute route : ticketMachineScreen.routes) {
            // 取该线路路径
            List<Tuple<Float, Float>> path = pathByRoute.get(route.id);
            if (path == null) {
                continue;
            }
            // 取该线路站点列表
            List<RailMapStation> railMapStations = layouts.get(route.id);
            // 线路标识
            String lineKey = getLineKey(route);
            // 逐段连接相邻站点：每对站点单独画直线，
            // 线路方向的变化在车站处由白底圆直接盖住
            for (int i = 0; i < path.size() - 1; i++) {
                // 生成站点对 key
                String pairKey = stationPairKey(railMapStations.get(i).station.id, railMapStations.get(i + 1).station.id);
                // 该站点对已由同线路画过则跳过
                if (!drawnPairs.add(lineKey + ":" + pairKey)) {
                    continue;
                }
                // 段起点（已解析的圆位置）
                Tuple<Float, Float> p0 = path.get(i);
                // 段终点
                Tuple<Float, Float> p3 = path.get(i + 1);
                // 两点间弦长
                double chord = dist(p0, p3);
                // 两点重合时无需画线
                if (chord < 0.001) {
                    continue;
                }
                // 本对站点的直线顶点：站点间直接连线，方向变化在车站处由白底圆盖住
                List<Tuple<Float, Float>> curve = new ArrayList<>();
                // 先加入起点
                curve.add(p0);
                // 再追加终点，完成"起点-终点"直线
                curve.add(p3);
                // 加入渲染折线列表
                drawingLines.add(curve);
                // 记录该折线的颜色
                drawingLineColors.add(argb(route.color));
            }
        }
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

    private void mouseOnStation(List<StationCircle> circles, Tuple<Float, Float> mouseCord, MouseOnStationCallback callback) {
        for (StationCircle circle : circles) {
            if (dist(new Tuple<>((float) circle.centerX, (float) circle.centerY), mouseCord) <= RADIUS) {
                for (KSDStation station : ticketMachineScreen.stations) {
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
        // 遍历所有线路
        for (KSDRoute route : ticketMachineScreen.routes) {
            // 本线路去重后的站点列表
            List<KSDStation> stationList = new ArrayList<>();
            // 各站点对应平台中点（可能为空）
            List<BlockPos> platformMids = new ArrayList<>();
            // 遍历线路的每个站台
            for (Route.RoutePlatform routePlatform : route.platformIds) {
                // 由站台 id 查站点
                KSDStation station = KSDClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
                if (KSDAreaBase.nonNullCorners(station)) {
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
            String lineKey = getLineKey(route);
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
        double cordsY = (worldZ - centerY) * scale + (double) height / (double) 2.0F;
        return new Tuple<>(cordsX, cordsY);
    }

    private void drawFromWorldCords(double worldX, double worldZ, BiConsumer<Double, Double> callback) {
        double cordsX = (worldX - centerX) * scale + (double) width / (double) 2.0F;
        double cordsY = (worldZ - centerY) * scale + (double) height / (double) 2.0F;
        if (RailwayData.isBetween(cordsX, 0.0F, width) && RailwayData.isBetween(cordsY, 0.0F, height)) {
            callback.accept(cordsX, cordsY);
        }
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

    // 解析一个站的多条线路候选位置：单线用原位置，多线围绕质心水平排开
    private static List<Tuple<Float, Float>> resolveStationCirclePositions(List<StationPosition> lines) {
        // 解析结果列表
        List<Tuple<Float, Float>> resolved = new ArrayList<>();
        // 只有一条线路时直接使用其位置
        if (lines.size() == 1) {
            resolved.add(new Tuple<>((float) lines.get(0).x, (float) lines.get(0).y));
            return resolved;
        }
        // 质心 X 累加
        double centerX = 0;
        // 质心 Y 累加
        double centerY = 0;
        // 累加所有候选位置
        for (StationPosition line : lines) {
            centerX += line.x;
            centerY += line.y;
        }
        // 质心 X 均值
        centerX /= lines.size();
        // 质心 Y 均值
        centerY /= lines.size();
        // 两圆间距取直径
        double spacing = RADIUS * 2;
        // 逐个候选位置水平排开
        for (int i = 0; i < lines.size(); i++) {
            resolved.add(new Tuple<>((float) (centerX + (i - (lines.size() - 1) / 2.0) * spacing), (float) centerY));
        }
        // 返回排布后的圆位置
        return resolved;
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

    // 生成线路标识：颜色+线路主名（用于区分同名的不同线路）
    private static String getLineKey(KSDRoute route) {
        // 返回 "颜色:中文线路名"
        return route.color + ":" + RailDataUtilities.getMainName(route);
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

    // 某个线路在某个站的候选绘制位置（含线路颜色）
    private static final class StationPosition {

        final int color;
        final double x;
        final double y;

        // 构造器：记录颜色与位置
        StationPosition(int color, double x, double y) {
            this.color = color;
            this.x = x;
            this.y = y;
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

    // 鼠标命中车站时的回调接口
    @FunctionalInterface
    public interface MouseOnStationCallback {
        void mouseOnStation(KSDStation terminus);
    }
}
