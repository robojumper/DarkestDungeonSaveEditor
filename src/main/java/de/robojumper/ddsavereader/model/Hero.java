package de.robojumper.ddsavereader.model;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;

import de.robojumper.ddsavereader.model.helper.HashedString;

public class Hero {

    public enum HeroStatus {
        @SerializedName("0")
        AVAILABLE(0, "Available"),
        @SerializedName("1")
        SQUAD(1, "In Squad"),
        @SerializedName("2")
        STAFFED(2, "Staffed"),
        @SerializedName("3")
        DEAD(3, "Dead"),
        @SerializedName("4")
        GONE(4, "Underway");
        
        private String status;
        
        HeroStatus(int v, String strStatus) {
            this.status = strStatus;
        }
        
        public String toString() {
            return this.status;
        }
    };
    
    // TODO
    public enum DamageType {
        @SerializedName("0")
        NONE(0, "Nothing"),
        @SerializedName("4")
        PHYSICAL(4, "Physical"),
        @SerializedName("7")
        BLIGHT(7, "Blight");
        
        private String status;
        
        DamageType(int v, String strStatus) {
            this.status = strStatus;
        }
        
        public String toString() {
            return this.status;
        }
    };

    @SerializedName("roster.status")
    HeroStatus status;
    
    @SerializedName("heroClass")
    HashedString heroClass;
    
    @SerializedName("resolveXp")
    int resolveXP;
    
    @SerializedName("m_Stress")
    float stress;
    
    @SerializedName("enemies_killed")
    int kills;
    
    class ActorData {
        
        @SerializedName("name")
        String name;
        
        @SerializedName("current_hp")
        String hp;
        
        @SerializedName("damage_source_data")
        HashedString lastDamageDealer;
        
        @SerializedName("damage_type")
        DamageType damageType;
    }
    
    @SerializedName("actor")
    ActorData data;
    
    class Quirk {
        
        @SerializedName("is_locked")
        boolean isLocked;
    }
    
    @SerializedName("quirks")
    Map<String, Quirk> quirkMap;
    
    class Skills {
        @SerializedName("selected_combat_skills")
        Map<String, Integer> combatSkills = new LinkedTreeMap<>();
        @SerializedName("selected_camping_skills")
        Map<String, Integer> campingSkills = new LinkedTreeMap<>();
    }
    
    @SerializedName("skills")
    Skills skills = new Skills();
    
    public class Trinket {
        @SerializedName("id")
        String id;
    }

    
    class Trinkets {
        @SerializedName("items")
        Map<Integer, Trinket> items = new LinkedTreeMap<>();
    }
    
    @SerializedName("trinkets")
    Trinkets trinkets = new Trinkets();
    
    int id;
    
    void setID(int ID) {
        this.id = ID; 
    }
    
    public int getID() {
        return this.id;
    }
    
    public String getName() {
        return data.name;
    }
    
    public HeroStatus getStatus() {
        return status;
    }
    
    public String getHeroClass() {
        return heroClass.toString();
    }
    
    public int getXP() {
        return resolveXP;
    }
    
    public int getKills() {
        return kills;
    }
    
    public String getQuirks() {
        return quirkMap.entrySet().stream().map(e -> e.getKey() + ((e.getValue().isLocked) ? " (\uD83D\uDD12)" : "")).collect(Collectors.joining(", "));
    }
    
    public Collection<String> getSkills() {
        return Collections.unmodifiableCollection(skills.combatSkills.keySet());
    }
    
    public Collection<String> getCampingSkills() {
        return Collections.unmodifiableCollection(skills.campingSkills.keySet());
    }
    
    public Collection<String> getTrinkets() {
        return trinkets.items.values().stream().map(i -> i.id).collect(Collectors.toList());
    }
}
