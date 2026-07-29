package net.hulan.ksd.sreen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.RailwayData;
import mtr.data.RouteType;
import mtr.mappings.SelectableMapper;
import mtr.mappings.UtilitiesClient;
import mtr.mappings.WidgetMapper;
import net.hulan.ksd.client.KSDClientData;
import net.hulan.ksd.data.KSDAreaBase;
import net.hulan.ksd.data.KSDRoute;import net.hulan.ksd.data.KSDStation;
import net.hulan.ksd.data.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class KCRTicketMachineRailMap implements WidgetMapper, SelectableMapper, GuiEventListener, IGui {

    private int x;
    private int y;
    private int width;
    private int height;
    private double scale;
    private double centerX;
    private double centerY;
    private RouteType routeType = Utils.KCR_CLASSICAL;
    private final Set<KSDStation> stations = new HashSet<>();
    private final Set<KSDRoute> routes = new HashSet<>();
    private final ClientLevel world;
    private final LocalPlayer player;
    private final Font textRenderer;
    private final Consumer<KSDStation> onClickedOnTerminus;
    private static final int ARGB_BLUE = -12417548;
    private static final double SCALE_UPPER_LIMIT = 64F;
    private static final double SCALE_LOWER_LIMIT = 0.0078125F;
    private static final int RADIUS = 10;
    private static final int RADIUS_PADDING = 5;
    private static final int SEGMENTS = 64;

    public KCRTicketMachineRailMap(Consumer<KSDStation> onClickedOnTerminus) {
        this.onClickedOnTerminus = onClickedOnTerminus;
        Minecraft minecraftClient = Minecraft.getInstance();
        world = minecraftClient.level;
        player = minecraftClient.player;
        textRenderer = minecraftClient.font;
        if (player == null) {
            centerX = 0.0F;
            centerY = 0.0F;
        } else {
            centerX = player.getX();
            centerY = player.getZ();
        }
        scale = 1.0F;
    }

    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        try {
            for (KSDStation station : stations) {
                if (KSDAreaBase.nonNullCorners(station)) {
                    BlockPos midPos = station.getCenter();
                    drawFromWorldCords(
                            midPos.getX(),
                            midPos.getZ(),
                            (x1, y1) -> Utils.getInstance().drawStationCircle(
                                    matrices,
                                    x + x1.floatValue(),
                                    y + y1.floatValue(),
                                    RADIUS,
                                    SEGMENTS,
                                    (float) RADIUS / 5,
                                    ARGB_BLACK_TRANSLUCENT + station.color));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        UtilitiesClient.beginDrawingRectangle(buffer);
        RenderSystem.enableBlend();
        if (player != null) {
            drawFromWorldCords(player.getX(), player.getZ(), (x1, y1) -> {
                drawRectangle(buffer, x1 - (double) 2.0F, y1 - (double) 3.0F, x1 + (double) 2.0F, y1 + (double) 3.0F, -1);
                drawRectangle(buffer, x1 - (double) 3.0F, y1 - (double) 2.0F, x1 + (double) 3.0F, y1 + (double) 2.0F, -1);
                drawRectangle(buffer, x1 - (double) 2.0F, y1 - (double) 2.0F, x1 + (double) 2.0F, y1 + (double) 2.0F, ARGB_BLUE);
            });
        }
        tesselator.end();
        RenderSystem.disableBlend();
        UtilitiesClient.finishDrawingRectangle();
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        for (KSDRoute route : routes) {
            int routeColor = route.color;
            int r = (routeColor >> 16) & 0xFF;
            int g = (routeColor >> 8) & 0xFF;
            int b = routeColor & 0xFF;
            for (int i = 0; i < route.platformIds.size() - 1; i++) {
                KSDStation station1 = KSDClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(i).platformId);
                KSDStation station2 = KSDClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(i + 1).platformId);
                if (KSDAreaBase.nonNullCorners(station1) && KSDAreaBase.nonNullCorners(station2)) {
                    BlockPos pos1 = station1.getCenter();
                    BlockPos pos2 = station2.getCenter();
                    Tuple<Double, Double> cord1 = worldPosToCords((double) pos1.getX(), pos1.getZ());
                    Tuple<Double, Double> cord2 = worldPosToCords((double) pos2.getX(), pos2.getZ());
                    IDrawing.drawLine(
                            matrices,
                            immediate,
                            x + cord1.getA().floatValue(),
                            y + cord1.getB().floatValue(),
                            0,
                            x + cord2.getA().floatValue(),
                            y + cord2.getB().floatValue(),
                            0,
                            r,
                            g,
                            b);
                }
            }
        }
        for (KSDStation station : stations) {
            if (KSDAreaBase.nonNullCorners(station)) {
                BlockPos pos = station.getCenter();
                String stationString = String.format("%s", station.name);
                drawFromWorldCords(
                        pos.getX(),
                        pos.getZ(),
                        (x1, y1) -> IDrawing.drawStringWithFont(
                                matrices,
                                this.textRenderer,
                                immediate,
                                stationString,
                                HorizontalAlignment.LEFT,
                                VerticalAlignment.CENTER,
                                (float) this.x + x1.floatValue() + RADIUS + RADIUS_PADDING,
                                (float) this.y + y1.floatValue(), -1.0F, -1.0F, 1.5F, -1, true,
                                MAX_LIGHT_GLOWING, null));
            }
        }
        immediate.endBatch();
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        centerX -= deltaX / scale;
        centerY -= deltaY / scale;
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY)) {
            final Tuple<Double, Double> mouseCord = new Tuple<>(mouseX - x, mouseY - y);
            mouseOnStation(mouseCord, onClickedOnTerminus::accept);
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
        return mouseX >= (double)this.x && mouseY >= (double)this.y && mouseX < (double)(this.x + this.width) && mouseY < (double)(this.y + this.height) && (!(mouseX >= (double)(this.x + this.width - 200)) || !(mouseY >= (double)(this.y + this.height - 20)));
    }

    public void setRouteType(RouteType routeType) {
        this.routeType = routeType;
        load();
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
    }

    public void scale(double amount) {
        this.scale *= Math.pow(2.0F, amount);
        this.scale = Mth.clamp(this.scale, SCALE_LOWER_LIMIT, SCALE_UPPER_LIMIT);
    }

    public void find(double x1, double z1, double x2, double z2) {
        this.centerX = (x1 + x2) / (double)2.0F;
        this.centerY = (z1 + z2) / (double)2.0F;
        this.scale = Math.max(2.0F, this.scale);
    }

    public void find(BlockPos pos) {
        this.centerX = pos.getX();
        this.centerY = pos.getZ();
        this.scale = Math.max(8.0F, this.scale);
    }

    private void mouseOnStation(Tuple<Double, Double> mouseCord, MouseOnStationCallback callback) {
        for (KSDStation station : stations) {
            BlockPos centerPos = station.getCenter();
            Tuple<Double, Double> centerCord = worldPosToCords((double) centerPos.getX(), centerPos.getZ());
            if (dist(centerCord, mouseCord) <= RADIUS) {
                callback.mouseOnStation(station);
            }
        }
    }

    private void load() {
        loadStations();
        loadRoutes();
    }

    private void loadStations() {
        stations.clear();
        KSDClientData.STATIONS.forEach(s -> {
            Set<KSDRoute> rs = KSDClientData.DATA_CACHE.stationIdToRoutes.get(s.id);
            if (rs != null) {
                for (KSDRoute r : rs) {
                    if (r.routeType.equals(routeType)) {
                        stations.add(s);
                        break;
                    }
                }
            }
        });
    }

    private void loadRoutes() {
        routes.clear();
        for (KSDRoute r : KSDClientData.ROUTES) {
            if (r.routeType.equals(routeType)) {
                routes.add(r);
            }
        }
    }

    private Tuple<Integer, Integer> cordsToWorldPos(int mouseX, int mouseY) {
        Tuple<Double, Double> worldPos = cordsToWorldPos(mouseX, (double) mouseY);
        return new Tuple<>((int) Math.floor(worldPos.getA()), (int) Math.floor(worldPos.getB()));
    }

    private Tuple<Double, Double> cordsToWorldPos(double mouseX, double mouseY) {
        double left = (mouseX - (double) width / (double) 2.0F) / scale + centerX;
        double right = (mouseY - (double) height / (double) 2.0F) / scale + centerY;
        return new Tuple<>(left, right);
    }

    private Tuple<Integer, Integer> worldPosToCords(int worldX, int worldZ) {
        Tuple<Double, Double> worldPos = worldPosToCords(worldX, (double) worldZ);
        return new Tuple<>((int) Math.floor(worldPos.getA()), (int) Math.floor(worldPos.getB()));
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

    private void drawRectangleFromWorldCords(BufferBuilder buffer, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int color) {
        drawRectangleFromWorldCords(
                buffer,
                corner1.getA(),
                corner1.getB(),
                corner2.getA(),
                corner2.getB(),
                color);
    }

    private void drawRectangleFromWorldCords(BufferBuilder buffer, double posX1, double posZ1, double posX2, double posZ2, int color) {
        double x1 = (posX1 - centerX) * scale + (double) width / (double) 2.0F;
        double z1 = (posZ1 - centerY) * scale + (double) height / (double) 2.0F;
        double x2 = (posX2 - centerX) * scale + (double) width / (double) 2.0F;
        double z2 = (posZ2 - centerY) * scale + (double) height / (double) 2.0F;
        drawRectangle(buffer, x1, z1, x2, z2, color);
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

    private static int divideColorRGB(int color) {
        int r = (color >> 16 & 255) / 2;
        int g = (color >> 8 & 255) / 2;
        int b = (color & 255) / 2;
        return (r << 16) + (g << 8) + b;
    }

    private static double dist(Tuple<Double, Double> corner1, Tuple<Double, Double> corner2) {
        return Math.sqrt(Math.pow(corner1.getA() - corner2.getA(), 2) + Math.pow(corner1.getB() - corner2.getB(), 2));
    }

    @FunctionalInterface
    public interface MouseOnStationCallback {
        void mouseOnStation(KSDStation terminus);
    }
}
