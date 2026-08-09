package net.hulan.ksd.sreen;

import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import net.hulan.ksd.data.KSDStation;

public class TicketProcessingScreen extends ScreenMapper {

    private final KSDStation current;
    private final KSDStation terminus;

    protected TicketProcessingScreen(KSDStation current, KSDStation terminus) {
        super(Text.literal(""));
        this.current = current;
        this.terminus = terminus;
    }

    protected void init() {
    }

    private enum Payment {
        EMERALDS,
        DIAMONDS,
        MTR_BALANCE,
        OCTOPUS
    }
}
