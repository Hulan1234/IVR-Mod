package net.hulan.ksd.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.hulan.ksd.utils.DataUtilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class JSONDataManager {

    public final Set<Octopus> octopuses = new HashSet<>();
    private final Path ocPath;

    public JSONDataManager(ServerLevel world) {
        Path savedPath = world.getServer().getWorldPath(LevelResource.ROOT).resolve("json-data").resolve(world.dimension().location().getPath());
        ocPath = savedPath.resolve("octopus");
        try {
            Files.createDirectories(ocPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void onLoad() {
        octopuses.clear();
        loadData(ocPath, Octopus::new, octopuses::add);
    }

    public void onSave() {
        saveData(ocPath, octopuses);
    }

    public Octopus getOctopus(UUID uuid) {
        return DataUtilities.getOrNull(octopuses, o -> o.uuid.equals(uuid));
    }

    private <T extends JSONData> void loadData(Path path, Function<JsonObject, T> instance, Consumer<T> storeData) {
        try (Stream<Path> dataPathStream = Files.list(path)) {
            dataPathStream.forEach(dataPath -> {
                try (JsonReader reader = new JsonReader(Files.newBufferedReader(dataPath))) {
                    JsonElement jsonElement = JsonParser.parseReader(reader);
                    if (!jsonElement.isJsonObject()) return;
                    T data = instance.apply(jsonElement.getAsJsonObject());
                    storeData.accept(data);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private <T extends JSONData> void saveData(Path path, Set<T> dataSet) {
        dataSet.forEach(data -> {
            String id = data.getId();
            Path filePath = path.resolve(id + ".json");
            try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                writer.setIndent("  ");
                writer.beginObject();
                data.writeToJson(writer);
                writer.endObject();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
