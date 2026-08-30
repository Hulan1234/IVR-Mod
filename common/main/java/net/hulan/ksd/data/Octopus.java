package net.hulan.ksd.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import mtr.data.EnumHelper;
import mtr.mappings.Text;
import net.minecraft.nbt.CompoundTag;
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
    public boolean isConcessionary;
    public List<History> histories;
    private static final String KEY_UUID = "uuid";
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_HISTORY = "histories";

    public Octopus(String id) {
        this.uuid = parseId(id, UUID::fromString, UUID::randomUUID);
    }

    public Octopus() {
        uuid = UUID.randomUUID();
        histories = new ArrayList<>();
    }

    @Override
    public void readFromJson(JsonObject json) throws JsonSyntaxException {
        balance = json.get(KEY_BALANCE).getAsInt();
        JsonObject historyObjects = json.get(KEY_HISTORY).getAsJsonObject();
        histories = new ArrayList<>(historyObjects.size());
        for (String key : historyObjects.keySet()) {
            History history = new History(key);
            JsonObject historyObject = historyObjects.get(key).getAsJsonObject();
            history.readFromJson(historyObject);
            histories.add(history);
        }
    }

    @Override
    public void writeToJson(JsonWriter writer) throws IOException {
        writer.name(KEY_BALANCE).value(balance);
        writer.name(KEY_HISTORY).beginObject();
        for (History history : histories) {
            writer.name(history.getId()).beginObject();
            history.writeToJson(writer);
            writer.endObject();
        }
        writer.endObject();
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

    public void addBalance(int balance, History.TransactionType source) {
        this.balance += balance;
        addHistory(balance, source);
    }

    public void addHistory(int change, History.TransactionType source) {
        History history = new History(uuid, change, source);
        histories.sort(Comparator.comparingLong(h -> h.time));
        if (histories.size() >= 50) {
            histories.remove(0);
        }
        histories.add(history);
    }

    public void toNBT(CompoundTag tag) {
        tag.putUUID(KEY_UUID, uuid);
        tag.putInt(KEY_BALANCE, balance);
        tag.putBoolean(KEY_HISTORY, isConcessionary);
    }

    public static class History extends JSONData implements PrintableData {

        public final UUID cardUUID;
        public long time;
        public TransactionType transactionType;
        public long count;
        private static final String KEY_TIME = "time";
        private static final String KEY_AMOUNT = "amount";
        private static final String KEY_TRANSACTION_TYPE = "transaction_type";

        public History(String id) {
            this.cardUUID = parseId(id, UUID::fromString, UUID::randomUUID);
        }

        public History(UUID cardUUID, long count, TransactionType transactionType) {
            this.cardUUID = cardUUID;
            time = System.currentTimeMillis();
            this.count = count;
            this.transactionType = transactionType;
        }

        @Override
        public void readFromJson(JsonObject json) throws JsonSyntaxException {
            time = json.get(KEY_TIME).getAsLong();
            transactionType = EnumHelper.valueOf(TransactionType.NONE, json.get(KEY_TRANSACTION_TYPE).getAsString());
            count = json.get(KEY_AMOUNT).getAsLong();
        }

        @Override
        public void writeToJson(JsonWriter writer) throws IOException {
            writer.name(KEY_TIME).value(time);
            writer.name(KEY_TRANSACTION_TYPE).value(transactionType.name());
            writer.name(KEY_AMOUNT).value(count);
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
                    transactionType.getSerializedName() +
                    " " +
                    count;
        }

        public enum TransactionType implements StringRepresentable {

            NONE,
            ADD_VALUE,
            MTR,
            KCR,
            LIGHT_RAIL,
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
