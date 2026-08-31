package net.hulan.ksd.data;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class JSONData {

    protected static final String KEY_UUID = "uuid";

    public abstract void writeToJson(JsonWriter writer) throws IOException;

    public abstract String getId();

    public abstract int hashCode();

    public abstract boolean equals(Object obj);

    protected static <T> T parseId(String id, Function<String, T> supplier, Supplier<T> supplierWhenException) {
        try {
            return supplier.apply(id);
        } catch (RuntimeException e) {
            return supplierWhenException.get();
        }
    }
}
