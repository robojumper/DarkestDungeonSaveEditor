package de.robojumper.ddsavereader.model;

import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.annotations.SerializedName;

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
        
        private int val;
        private String status;
        
        HeroStatus(int v, String strStatus) {
            this.val = v;
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
        
        private int val;
        private String status;
        
        DamageType(int v, String strStatus) {
            this.val = v;
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
    String resolveXP;
    
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
    
    public String getName() {
        return data.name;
    }
    
    public HeroStatus getStatus() {
        return status;
    }
    
    public int getKills() {
        return kills;
    }
    
    public String getQuirks() {
        return quirkMap.entrySet().stream().map(e -> e.getKey() + ((e.getValue().isLocked) ? " (\uD83D\uDD12)" : "")).collect(Collectors.joining(", "));
    }
}
