package de.robojumper.ddsavereader.model;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;


public class Town extends AbstractFile {

    public static class BuildingActivitiesTypeAdapter extends TypeAdapter<Map<Integer, String>> {

        @Override
        public Map<Integer, String> read(JsonReader in) throws IOException {
            Map<Integer, String> map = new LinkedTreeMap<>();
            JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
            for (Entry<String, JsonElement> e : o.entrySet()) {
                String activityName = e.getKey();
                for (Entry<String, JsonElement> slot : e.getValue().getAsJsonObject().entrySet()) {
                    if (slot.getValue().isJsonObject() && slot.getValue().getAsJsonObject().has("hero")) {
                        map.put(slot.getValue().getAsJsonObject().getAsJsonPrimitive("hero").getAsInt(), activityName);
                    }
                }
            }
            
            return map;
        }

        @Override
        public void write(JsonWriter out, Map<Integer, String> in) throws IOException {
            System.err.println("Serialization not supported for activities");
        }

    }

    class TownData {
        
        class Building {
            
            @SerializedName("activities")
            @JsonAdapter(BuildingActivitiesTypeAdapter.class)
            Map<Integer, String> activities = new LinkedTreeMap<>(); // Hero -> Activity
        }
        
        @SerializedName("buildings")
        Map<String, Building> buildings = new LinkedTreeMap<>();
        
        class DistrictsObject {
            
            class District {
                @SerializedName("built")
                boolean built = false;
            }
            
            @SerializedName("buildings")
            Map<String, District> districts = new LinkedTreeMap<>();
        }
        
        @SerializedName("districts")
        DistrictsObject districts = new DistrictsObject();
        
        
    }

    TownData townData = new TownData();
    
    @Override
    public void update(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o = o.getAsJsonObject("base_root");
        
        Gson g = SaveState.makeGson();
        this.townData = g.fromJson(o, TownData.class);
    }
    
    public String getHeroActivity(Integer id) {
        for (Map.Entry<String, TownData.Building> e : townData.buildings.entrySet()) {
            for (Map.Entry<Integer, String> activity : e.getValue().activities.entrySet()) {
                if (activity.getKey() == id) {
                    return activity.getValue() + " in " + e.getKey();
                }
            }
        }
        return "";
    }
    
    public String buildDistricts() {
        return townData.districts.districts.entrySet().stream().filter(e -> e.getValue().built).map(e -> e.getKey()).collect(Collectors.joining(", "));
    }

}
