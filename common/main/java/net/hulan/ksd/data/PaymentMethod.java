package net.hulan.ksd.data;

public enum PaymentMethod {

    EMERALDS,
    MTR_BALANCE,
    OCTOPUS;

    public PaymentMethod next() {
        return values()[(this.ordinal() + 1) % values().length];
    }
}
