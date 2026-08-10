package net.hulan.ksd.data;

public enum Payment {

    EMERALDS,
    MTR_BALANCE,
    OCTOPUS;

    public Payment next() {
        return values()[(this.ordinal() + 1) % values().length];
    }
}
