package net.hulan.ksd.data;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public abstract class JSONData {

    protected static final String KEY_UUID = "uuid";

    public abstract void writeToJson(JsonWriter writer) throws IOException;

    public abstract String getId();

    public abstract int hashCode();

    public abstract boolean equals(Object obj);

}
