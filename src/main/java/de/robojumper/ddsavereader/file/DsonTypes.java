package de.robojumper.ddsavereader.file;

import java.util.HashMap;

public class DsonTypes {
    static final String[][] FLOAT_FIELD_NAMES = {{"current_hp"}, {"m_Stress"},  {"actor", "buff_group", "*", "amount"}, {"chapters", "*", "*", "percent"}, {"non_rolled_additional_chances", "*", "chance"}, };
    
    // TODO: Make array a special kind of field with an Inner type??
    static final String[][] INTVECTOR_FIELD_NAMES = {
        {"read_page_indexes"}, {"raid_read_page_indexes"}, {"raid_unread_page_indexes"},    // journal.json
        {"dungeons_unlocked"}, {"played_video_list"}, // game_knowledge.json
        {"trinket_retention_ids"},    // quest.json
        {"last_party_guids"}, {"dungeon_history"}, // roster.json
        {"result_event_history"}, // town_event.json
        {"additional_mash_disabled_infestation_monster_class_ids"}, // campaign_mash.json
        {"party", "heroes"}, // raid.json
        {"narration_audio_event_queue_tags"}, // loading_screen.json
        {"dispatched_events"}, // tutorial.json
    };
    
    static final String[][] STRINGVECTOR_FIELD_NAMES = {
        {"goal_ids"}, // quest.json
        {"roaming_dungeon_2_ids", "*", "s"}, // campaign_mash.json
    };
    
    static final String[][] FLOATARRAY_FIELD_NAMES = {
        {"map", "bounds"}, {"areas", "*", "bounds"}, {"areas", "*", "tiles", "*", "mappos"}, {"areas", "*", "tiles", "*", "sidepos"}, // map.json  
    };
    
    static final String[][] CHAR_FIELD_NAMES = {
        {"requirement_code"}, // upgrades.json
    };

    
    // When loading, all Integers will check for a matching hash and replace their display string as "<name>" (where <name> is the unhashed string)
    // This is much better than trying to find a good reverse. 
    public static final HashMap<Integer, String> NAME_TABLE = new HashMap<Integer, String>();
}
