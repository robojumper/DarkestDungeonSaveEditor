package de.robojumper.ddsavereader.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import de.robojumper.ddsavereader.model.helper.HashedString;

public class CampaignLog extends AbstractFile {

    public static class ChapterAdapter extends TypeAdapter<Chapter> {

        @Override
        public void write(JsonWriter out, Chapter value) throws IOException {
            throw new IOException("Saving chapters not supported");
        }

        @Override
        public Chapter read(JsonReader in) throws IOException {
            JsonObject chapterRoot = JsonParser.parseReader(in).getAsJsonObject();
            Chapter c = new Chapter();
            c.chapterIndex = chapterRoot.getAsJsonPrimitive("chapterIndex").getAsInt();
            Gson g = SaveState.makeGson();
            for (Entry<String, JsonElement> entry : chapterRoot.entrySet()) {
                try {
                    Integer.parseInt(entry.getKey());
                    JsonObject RTTIObject = entry.getValue().getAsJsonObject();
                    int rtti = RTTIObject.get("rtti").getAsInt();
                    Class<? extends BaseRTTI> cls = RTTI_TO_CLASS_MAP.get(rtti);
                    if (cls != null) {
                        c.events.add(g.fromJson(RTTIObject, cls));
                    }
                } catch (NumberFormatException e) {
                    // Do nothing
                }
            }
            return c;
        }

    }

    private static final Map<Integer, Class<? extends BaseRTTI>> RTTI_TO_CLASS_MAP = new HashMap<>();

    static {
        RTTI_TO_CLASS_MAP.put(-579125384, HeroLevelupEvent.class);
        RTTI_TO_CLASS_MAP.put(2006063882, MissionEvent.class);
        RTTI_TO_CLASS_MAP.put(-37270005, BuildingUpgradeEvent.class);
        RTTI_TO_CLASS_MAP.put(844919810, TownEvent.class);
    }

    public static class Chapter {
        public int chapterIndex;
        public List<BaseRTTI> events = new ArrayList<>(0);
    }

    class CampaignLogData {

        @SerializedName("total_weeks")
        int weeks = -1;

        @SerializedName("chapters")
        Map<Integer, Chapter> chapters = new LinkedTreeMap<>();
    }

    public abstract class BaseRTTI {
        @SerializedName("rtti")
        int rtti;

        public abstract List<String> getCells();
    }

    class HeroLevelupEvent extends BaseRTTI {
        @SerializedName("name")
        String name;
        @SerializedName("class")
        HashedString heroClass;
        @SerializedName("guid")
        int guid;
        @SerializedName("level")
        int level;

        @Override
        public List<String> getCells() {
            return Arrays.asList("Hero Level Up", name + " (" + heroClass.toString() + ")  is now level " + level + ".");
        }
    }

    public class HeroRef {
        @SerializedName("name")
        String name;
        @SerializedName("class")
        HashedString heroClass;
        @SerializedName("died")
        boolean died;
        @SerializedName("guid")
        int guid;
    }

    class MissionEvent extends BaseRTTI {
        @SerializedName("heroes")
        Map<Integer, HeroRef> heroes;

        @SerializedName("quest")
        HashedString quest;

        @SerializedName("dungeon_type")
        HashedString dungeonType;

        @SerializedName("difficulty")
        int difficulty;

        @SerializedName("length")
        int length;

        @SerializedName("start")
        boolean start;

        @SerializedName("success")
        boolean success;

        @Override
        public List<String> getCells() {
            String missionTag = start ? "Mission Start" : (success ? "Mission Success" : "Mission Fail");
            return Arrays.asList(missionTag,
                    heroes.values().stream().map(h -> h.name + (h.died ? " \uD83D\uDC80" : "") + " (" + h.heroClass.toString() + ")")
                            .collect(Collectors.joining(", ")),
                    "quest: " + quest.toString(), "in: " + dungeonType.toString(), "difficulty: " + difficulty,
                    "length: " + length);
        }
    }

    class TreeObject {
        @SerializedName("tree")
        HashedString tree;
        @SerializedName("level")
        int level;
    }

    class BuildingUpgradeEvent extends BaseRTTI {
        @SerializedName("trees")
        Map<Integer, TreeObject> trees;

        @SerializedName("id")
        HashedString id;

        @SerializedName("percent")
        float percent;

        @SerializedName("unlocked")
        boolean unlocked;

        @Override
        public List<String> getCells() {
            if (unlocked) {
                return Arrays.asList("Building Unlocked", id.toString());
            } else {
                return Arrays.asList("Building Upgrade", id.toString(), String.format("%.02f", percent * 100) + "%");
            }
        }
    }

    class TownEvent extends BaseRTTI {
        @SerializedName("town_event_result_id")
        HashedString id;

        @Override
        public List<String> getCells() {
            return Arrays.asList("Town Event", id.toString());
        }
    }

    private CampaignLogData campaignLog = new CampaignLogData();

    @Override
    public void update(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o = o.getAsJsonObject("base_root");

        Gson g = SaveState.makeGson();
        this.campaignLog = g.fromJson(o, CampaignLogData.class);
    }

    public int getNumWeeks() {
        return campaignLog.weeks;
    }

    public int getHeroLevel(final int HeroID) {
        return campaignLog.chapters.values().stream().flatMap(c -> c.events.stream())
                .filter(e -> (e instanceof HeroLevelupEvent) && ((HeroLevelupEvent) e).guid == HeroID)
                .map(a -> ((HeroLevelupEvent) a).level).max(Integer::compare).orElse(0);
    }

    public int getHeroMissionCount(final int HeroID) {
        return (int) campaignLog.chapters.values().stream().flatMap(c -> c.events.stream())
                .filter(e -> (e instanceof MissionEvent) && ((MissionEvent) e).start
                        && ((MissionEvent) e).heroes.values().stream().anyMatch(h -> h.guid == HeroID))
                .count();
    }

    public List<Chapter> getChapters() {
        return campaignLog.chapters.entrySet().stream().sorted((a, b) -> a.getKey() - b.getKey()).map(e -> e.getValue())
                .collect(Collectors.toList());
    }

}
