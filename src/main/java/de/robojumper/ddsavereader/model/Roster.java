package de.robojumper.ddsavereader.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public class Roster extends AbstractFile {
    
    public static class HeroMapAdapter extends TypeAdapter<Map<Integer, Hero>> {

        @Override
        public Map<Integer, Hero> read(JsonReader in) throws IOException {
            Map<Integer, Hero> map = new HashMap<>();
            in.beginObject();
            while (in.peek() != JsonToken.END_OBJECT) {
                int id = Integer.valueOf(in.nextName());
                
                in.beginObject();
                if (!Objects.equals(in.nextName(), "hero_file_data"))
                    throw new IOException();
                
                in.beginObject();
                if (!Objects.equals(in.nextName(), "raw_data"))
                    throw new IOException();
                
                in.beginObject();
                if (!Objects.equals(in.nextName(), "base_root"))
                    throw new IOException();
                
                Hero h = SaveState.makeGson().fromJson(in, Hero.class);
                h.setID(id);
                map.put(id, h);
                
                in.endObject();
                in.endObject();
                in.endObject();
                
            }
            in.endObject();
            return map;
        }

        @Override
        public void write(JsonWriter out, Map<Integer, Hero> in) throws IOException {
            out.beginObject();
            out.name("hero_file_data");
            out.beginObject();
            out.name("raw_data");
            out.beginObject();
            out.name("base_root");
            SaveState.makeGson().toJson(in, new TypeToken<Map<Integer, Hero>>() {}.getType(), out);
            out.endObject();
            out.endObject();
            out.endObject();
        }

    }
    
    class LastParty {
        @SerializedName("last_party_guids")
        int[] lastPartyGuids = new int[0];
    }
    
    class RosterData {
        @SerializedName("dismissed_hero_count")
        private int dismissedHeroCount = -1;
        
        @SerializedName("heroes")
        @JsonAdapter(HeroMapAdapter.class)
        private final Map<Integer, Hero> heroes = new HashMap<>();
        
        @SerializedName("last_party")
        LastParty party = new LastParty();
    }

    private RosterData rosterData = new RosterData();
    
    @Override
    public void update(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o = o.getAsJsonObject("base_root");
        
        Gson g = SaveState.makeGson();
        this.rosterData = g.fromJson(o, RosterData.class);
    }
    
    public Hero getHero(Integer id) {
        if (id != null) {
            return rosterData.heroes.get(id);
        }
        return null;
    }
    
    public Hero getHero(String heroName) {
        return getHero(getHeroID(heroName));
    }
    
    public Integer getHeroID(String heroName) {
        String normalizedHeroName = SaveState.normalizeQueryString(heroName);
        for (Map.Entry<Integer, Hero> e : rosterData.heroes.entrySet()) {
            String normalizedEName = SaveState.normalizeQueryString(e.getValue().data.name);
            if (normalizedEName.equals(normalizedHeroName)) {
                return e.getKey();
            }
        }
        return null;
    }
    
    public Collection<Hero> getHeroes() {
        return Collections.unmodifiableCollection(rosterData.heroes.values());
    }
    
    public int getNumDismissedHeroes() {
        return rosterData.dismissedHeroCount;
    }
    
    public List<Hero> getParty() {
        return Arrays.stream(rosterData.party.lastPartyGuids).mapToObj(i -> rosterData.heroes.get(i)).collect(Collectors.toList());
    }
}
