package net.hulan.ksd.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import mtr.data.EnumHelper;
import mtr.mappings.Text;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Octopus extends JSONData implements PrintableData {

    public final long id;
    public int balance;
    public boolean isConcessionary;
    public List<History> histories;
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_HISTORY = "histories";

    public Octopus(String id) {
        this.id = parseId(id, Long::parseLong, () -> new Random().nextLong());
    }

    public Octopus() {
        id = new Random().nextLong();
        histories = new ArrayList<>(20);
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
        return String.valueOf(id);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Octopus octopus) {
            return octopus.id == id;
        }
        return false;
    }

    @Override
    public String getPrintedData() {
        StringBuilder printed = new StringBuilder();
        printed.append(Text.translatable("gui.ksd.balance", balance)).append("\n");
        printed.append(Text.translatable("gui.ksd.histories")).append("\n");
        for (History history : histories) {
            printed.append("\t").append(history.getPrintedData());
        }
        return printed.toString();
    }

    public void addBalance(int balance) {
        this.balance += balance;
    }

    public void decBalance(int balance) {
        this.balance -= balance;
    }

    public static class History extends JSONData implements PrintableData {

        public final long cardId;
        public long time;
        public TransactionType transactionType;
        public long count;
        private static final String KEY_TIME = "time";
        private static final String KEY_AMOUNT = "amount";
        private static final String KEY_BALANCE = "balance";
        private static final String KEY_TRANSACTION_TYPE = "transaction_type";

        public History(String cardId) {
            this.cardId = parseId(cardId, Long::parseLong, () -> new Random().nextLong());
        }

        public History(long carId, long count, TransactionType transactionType) {
            this.cardId = carId;
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
            return String.valueOf(cardId);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(cardId);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof History history) {
                return history.cardId == cardId;
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
