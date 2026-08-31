package net.hulan.ksd.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import mtr.data.EnumHelper;
import mtr.mappings.Text;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class Octopus extends JSONData implements PrintableData {

    public final UUID uuid;
    public int balance;
    public final boolean isConcessionary;
    public final List<History> histories = new ArrayList<>();
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_IS_CONCESSIONARY = "is_concessionary";
    private static final String KEY_HISTORIES = "histories";

    public Octopus(JsonObject octopusObject) {
        uuid = UUID.fromString(octopusObject.get(KEY_UUID).getAsString());
        balance = octopusObject.get(KEY_BALANCE).getAsInt();
        isConcessionary = octopusObject.get(KEY_IS_CONCESSIONARY).getAsBoolean();
        JsonArray historyArray = octopusObject.get(KEY_HISTORIES).getAsJsonArray();
        for (JsonElement historyElement : historyArray) {
            histories.add(new History(historyElement.getAsJsonObject()));
        }
        histories.sort(Comparator.comparingLong(h -> h.time));
    }

    public Octopus(boolean isConcessionary) {
        uuid = UUID.randomUUID();
        this.isConcessionary = isConcessionary;
    }

    @Override
    public void writeToJson(JsonWriter writer) throws IOException {
        writer.name(KEY_UUID).value(uuid.toString());
        writer.name(KEY_BALANCE).value(balance);
        writer.name(KEY_IS_CONCESSIONARY).value(isConcessionary);
        writer.name(KEY_HISTORIES).beginArray();
        for (History history : histories) {
            writer.beginObject();
            history.writeToJson(writer);
            writer.endObject();
        }
        writer.endArray();
    }

    public void toNBT(CompoundTag tag) {
        tag.putUUID(KEY_UUID, uuid);
        tag.putInt(KEY_BALANCE, balance);
        tag.putBoolean(KEY_IS_CONCESSIONARY, isConcessionary);
        ListTag historyTags = new ListTag();
        histories.forEach(history -> {
            CompoundTag historyTag = new CompoundTag();
            history.toNBT(historyTag);
            historyTags.add(historyTag);
        });
        tag.put(KEY_HISTORIES, historyTags);
    }

    @Override
    public String getId() {
        return uuid.toString();
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Octopus octopus) {
            return octopus.uuid == uuid;
        }
        return false;
    }

    @Override
    public String getPrintedData() {
        StringBuilder printed = new StringBuilder();
        printed.append(Text.translatable("gui.ksd.pd_balance", balance).getString()).append("\n");
        printed.append(Text.translatable("gui.ksd.pd_histories").getString()).append("\n");
        for (History history : histories) {
            printed.append("\t").append(history.getPrintedData());
        }
        return printed.toString();
    }

    public void addBalance(int balance, History.Source source) {
        this.balance += balance;
        addHistory(balance, source);
    }

    private void addHistory(int change, History.Source source) {
        History history = new History(uuid, change, source);
        histories.sort(Comparator.comparingLong(h -> h.time));
        if (histories.size() >= 50) {
            histories.remove(0);
        }
        histories.add(history);
    }

    public static class History extends JSONData implements PrintableData {

        public final UUID cardUUID;
        public long time;
        public long count;
        public Source source;
        private static final String KEY_TIME = "time";
        private static final String KEY_AMOUNT = "amount";
        private static final String KEY_SOURCE = "source";

        public History(JsonObject historyObject) {
            cardUUID = UUID.fromString(historyObject.get(KEY_UUID).getAsString());
            time = historyObject.get(KEY_TIME).getAsLong();
            count = historyObject.get(KEY_AMOUNT).getAsLong();
            source = EnumHelper.valueOf(Source.NONE, historyObject.get(KEY_SOURCE).getAsString());
        }

        public History(UUID cardUUID, long count, Source source) {
            this.cardUUID = cardUUID;
            time = System.currentTimeMillis();
            this.count = count;
            this.source = source;
        }

        @Override
        public void writeToJson(JsonWriter writer) throws IOException {
            writer.name(KEY_UUID).value(cardUUID.toString());
            writer.name(KEY_TIME).value(time);
            writer.name(KEY_AMOUNT).value(count);
            writer.name(KEY_SOURCE).value(source.name());
        }

        public void toNBT(CompoundTag tag) {
            tag.putUUID(KEY_UUID, cardUUID);
            tag.putLong(KEY_TIME, time);
            tag.putLong(KEY_AMOUNT, count);
            tag.putString(KEY_SOURCE, source.name());
        }

        @Override
        public String getId() {
            return cardUUID.toString();
        }

        @Override
        public int hashCode() {
            return cardUUID.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof History history) {
                return history.cardUUID == cardUUID;
            }
            return false;
        }

        @Override
        public String getPrintedData() {
            return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.FULL)) +
                    " " +
                    source.getSerializedName() +
                    " " +
                    count;
        }

        public enum Source implements StringRepresentable {

            NONE,
            ADD_VALUE,
            MTR,
            KCR,
            LRT,
            BUS,
            TAXI,
            SHOP,
            RESTAURANT;

            @Override
            public @NotNull String getSerializedName() {
                return name();
            }
        }
    }
}
