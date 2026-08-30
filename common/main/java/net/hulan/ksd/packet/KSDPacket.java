package net.hulan.ksd.packet;

import net.minecraft.resources.ResourceLocation;

public interface KSDPacket {

    ResourceLocation KSD_PACKET_OPEN_KSD_DASHBOARD_SCREEN = new ResourceLocation("ksd", "packet_open_dashboard_screen");
    ResourceLocation KSD_PACKET_OPEN_KCR_SINGLE_TICKET_MACHINE_SCREEN = new ResourceLocation("ksd", "packet_open_kcr_single_ticket_machine_screen");
    ResourceLocation KSD_PACKET_OPEN_KCR_SINGLE_TICKET_FARE_ADJUSTMENT_SCREEN = new ResourceLocation("ksd", "packet_open_kcr_single_ticket_fare_adjustment_screen");
    ResourceLocation KSD_PACKET_CREATE_SINGLE_TICKET = new ResourceLocation("ksd", "packet_create_single_ticket");
    ResourceLocation KSD_PACKET_ADJUST_SINGLE_TICKET_FARE = new ResourceLocation("ksd", "packet_adjust_single_ticket_fare");
    ResourceLocation KSD_PACKET_PAYMENT = new ResourceLocation("ksd", "packet_paying");
    ResourceLocation KSD_PACKET_CHUNK_S2C = new ResourceLocation("ksd", "packet_chunk_s2c");
    ResourceLocation KSD_PACKET_UPDATE_STATION = new ResourceLocation("ksd", "packet_update_station");
    ResourceLocation KSD_PACKET_DELETE_STATION = new ResourceLocation("ksd", "packet_delete_station");
    ResourceLocation KSD_PACKET_UPDATE_PLATFORM = new ResourceLocation("ksd", "packet_update_platform");
    ResourceLocation KSD_PACKET_DELETE_PLATFORM = new ResourceLocation("ksd", "packet_delete_platform");
    ResourceLocation KSD_PACKET_UPDATE_ROUTE = new ResourceLocation("ksd", "packet_update_route");
    ResourceLocation KSD_PACKET_DELETE_ROUTE = new ResourceLocation("ksd", "packet_delete_route");
    //IPacket
}
