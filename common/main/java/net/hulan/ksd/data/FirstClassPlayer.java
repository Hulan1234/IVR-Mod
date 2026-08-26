package net.hulan.ksd.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import mtr.data.EnumHelper;

import java.io.IOException;
import java.util.UUID;

public final class FirstClassPlayer extends JSONData {

    public final UUID uuid;
    public FirstClassValidationSystem.FirstClassState state;
    private static final String KEY_STATE = "state";

    public FirstClassPlayer(String id) {
        uuid = parseId(id, UUID::fromString, UUID::randomUUID);
    }

    public FirstClassPlayer(UUID uuid) {
        this.uuid = uuid;
        state = FirstClassValidationSystem.FirstClassState.MTR;
    }

    @Override
    public void readFromJson(JsonObject json) throws JsonSyntaxException {
        state = EnumHelper.valueOf(FirstClassValidationSystem.FirstClassState.MTR, json.get(KEY_STATE).getAsString());
    }

    @Override
    public void writeToJson(JsonWriter writer) throws IOException {
        writer.name(KEY_STATE).value(state.name());
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
        if (obj instanceof FirstClassPlayer data) {
            return data.uuid.equals(uuid);
        }
        return false;
    }
}
