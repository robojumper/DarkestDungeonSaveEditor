package de.robojumper.ddsavereader.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;

import de.robojumper.ddsavereader.model.helper.HashedString;

/**
 * Class that manages the current save state of a game.
 * Provides functions to update and query the current save state.
 * This class has a very high throughput of data, as Darkest Dungeon
 * saves its state pretty much constantly. Hence, there's not a whole
 * lot of optimization wrt data structures, as the cost of rebuilding
 * them probably exceeds the cost of the one or two queries we expect
 * before the data becomes updated again.
 * In fact, it may even be beneficial to not deserialize at all, and
 * instead get to the relevant data just with a JsonParser.
 * @author robojumper
 *
 */
public class SaveState {
    
    public static class TypeAdapterMapping<T> {
        Class<T> cls;
        TypeAdapter<T> adapter;
        
        public TypeAdapterMapping (Class<T> cls, TypeAdapter<T> adapter) {
            this.cls = cls;
            this.adapter = adapter;
        }
    }

    public static final List<TypeAdapterMapping<?>> GLOBAL_TYPE_ADAPTERS = new ArrayList<>();
    
    public static Gson makeGson() {
        GsonBuilder b = new GsonBuilder();
        for (TypeAdapterMapping<?> m : GLOBAL_TYPE_ADAPTERS) {
            b.registerTypeAdapter(m.cls, m.adapter);
        }
        return b.create();
    }

    private Roster roster = new Roster();
    private Estate estate = new Estate();
    private Town town = new Town();
    private int weeks = -1;
    
    
    
    static {
        GLOBAL_TYPE_ADAPTERS.add(new TypeAdapterMapping<HashedString>(HashedString.class, new HashedString.HashedStringAdapter()));
    }



    public void update(String fileName, String jsonData) {
        synchronized (getSynchronizationLock()) {
            switch (fileName) {
                case "persist.roster.json":
                    roster.update(jsonData);
                    break;
                case "persist.estate.json":
                    estate.update(jsonData);
                    break;
                case "persist.town.json":
                    town.update(jsonData);
                    break;
                case "persist.campaign_log.json":
                    updateWeeks(jsonData);
                    break;
                default:
                    break;
            }
        }
    }
    
    private void updateWeeks(String jsonData) {
        JsonParser parser = new JsonParser();
        JsonObject o = parser.parse(jsonData).getAsJsonObject();
        o = o.getAsJsonObject("base_root");
        
        this.weeks = o.get("total_weeks").getAsInt();
    }

    public Object getSynchronizationLock() {
        return this;
    }
    
    public Roster getRoster() {
        return roster;
    }
    
    public Estate getEstate() {
        return estate;
    }
    
    public Town getTown() {
        return town;
    }
    
    public int getNumWeeks() {
        return weeks;
    }
    
    // https://stackoverflow.com/questions/8519669/replace-non-ascii-character-from-string/17786019
    public static String normalizeQueryString(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "").toLowerCase();
    }
}
