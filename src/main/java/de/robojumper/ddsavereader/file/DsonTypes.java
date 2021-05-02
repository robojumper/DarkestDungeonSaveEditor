package de.robojumper.ddsavereader.file;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Supplier;

public class DsonTypes {

    public enum FieldType {
        TYPE_OBJECT, // has a Meta1Block entry
        TYPE_BOOL, // 1 byte, 0x00 or 0x01
        // Needed for saving
        TYPE_CHAR(new String[][] { { "requirement_code" }, // upgrades.json
        }), // 1 byte, only seems to be used in upgrades.json
        TYPE_TWOBOOL, // aligned, 8 bytes (only used in gameplay options??). emitted as [true, true]
        TYPE_STRING, // aligned, int size + null-terminated string of size (including \0)
        TYPE_FILE, // Actually an object, but encoded as a string (embedded DsonFile). used in
                    // roster.json and map.json
        TYPE_INT, // aligned, 4 byte integer
        // Begin hardcoded types: these types do not have enough characteristics to make
        // the heuristic work
        // As such, the field names/paths are hardcoded in DsonTypes
        // Fields matching the names will ALWAYS assume the corresponding type, even if
        // parsing fails
        // So they should be used sparingly and be as specific as possible
        // aligned, 4-byte float
        TYPE_FLOAT(new String[][] { { "current_hp" }, { "m_Stress" }, { "actor", "buff_group", "*", "amount" },
                { "chapters", "*", "*", "percent" }, { "non_rolled_additional_chances", "*", "chance" }, }),
        // aligned. 4-byte int [count], then [count] 4-byte integers
        TYPE_INTVECTOR(
                new String[][] { { "read_page_indexes" }, { "raid_read_page_indexes" }, { "raid_unread_page_indexes" }, // journal.json
                        { "dungeons_unlocked" }, { "played_video_list" }, // game_knowledge.json
                        { "trinket_retention_ids" }, // quest.json
                        { "last_party_guids" }, { "dungeon_history" }, { "buff_group_guids" }, // roster.json
                        { "result_event_history" }, // town_event.json
                        { "dead_hero_entries" }, // town_event.json
                        { "additional_mash_disabled_infestation_monster_class_ids" }, // campaign_mash.json
                        { "mash", "valid_additional_mash_entry_indexes" }, // raid.json
                        { "party", "heroes" }, // raid.json
                        { "skill_cooldown_keys" }, // raid.json
                        { "skill_cooldown_values" },
                        { "bufferedSpawningSlotsAvailable" }, // raid.json
                        { "curioGroups", "*", "curios" }, // raid.json
                        { "curioGroups", "*", "curio_table_entries" }, // raid.json
                        { "raid_finish_quirk_monster_class_ids" }, // raid.json
                        { "narration_audio_event_queue_tags" }, // loading_screen.json
                        { "dispatched_events" }, // tutorial.json
                        { "backer_heroes", "*", "combat_skills"},
                        { "backer_heroes", "*", "camping_skills"},
                        { "backer_heroes", "*", "quirks"},
                }),
        // aligned, 4-byte int [count], then [count] string length + null-terminated
        // string
        TYPE_STRINGVECTOR(new String[][] { { "goal_ids" }, // quest.json
                { "roaming_dungeon_2_ids", "*", "s" }, // campaign_mash.json
                { "quirk_group" }, // campaign_log.json
                { "backgroundNames" }, // raid.json
                { "backgroundGroups", "*", "backgrounds" }, // raid.json
                { "backgroundGroups", "*", "background_table_entries" }, // raid.json
        }),
        // aligned, arbitrary number of 4-byte floats. emitted as [1.0, 2.0, ...]
        TYPE_FLOATARRAY(new String[][] { { "map", "bounds" }, { "areas", "*", "bounds" },
                { "areas", "*", "tiles", "*", "mappos" }, { "areas", "*", "tiles", "*", "sidepos" }, // map.json
        }),
        TYPE_TWOINT(new String[][] { { "killRange" } }), // raid.json
        // Unknown Type
        TYPE_UNKNOWN;

        final String[][] names;

        FieldType() {
            this.names = null;
        }

        FieldType(String[][] names) {
            this.names = names;
        }
    };

    // When loading, all Integers will check for a matching hash and replace their
    // display string as "<name>" (where <name> is the unhashed string)
    // This is much better than trying to find a good reverse.
    public static final HashMap<Integer, String> NAME_TABLE = new HashMap<Integer, String>();

    public static void offerName(String name) {
        NAME_TABLE.put(DsonTypes.stringHash(name), name);
    }

    public static void offerNames(Collection<String> names) {
        for (String name : names) {
            NAME_TABLE.put(DsonTypes.stringHash(name), name);
        }
    }

    public static int stringHash(String str) {
        int hash = 0;
        byte[] arr = str.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < arr.length; i++) {
            hash = hash * 53 + Byte.toUnsignedInt(arr[i]);
        }
        return hash;
    }

    /**
     * Determines whether a field is hardcoded as specific type.
     * 
     * @param type  One of TYPE_CHAR, TYPE_FLOAT, TYPE_INTVECTOR, TYPE_STRINGVECTOR,
     *              TYPE_FLOATARRAY
     * @param names Supplied that gives an Iterator that yields field names, first
     *              the current field name, then the parent, ...
     * @return True if a matching field is found.
     */
    static boolean isA(FieldType type, Supplier<Iterator<String>> names) {

        String[][] arr = type.names;

        if (arr == null) {
            throw new IllegalArgumentException("Not a hardcoded type: " + type.name());
        }

        String checkString;
        boolean match;
        for (int i = 0; i < arr.length; i++) {
            match = true;
            Iterator<String> nameIter = names.get();
            checkString = nameIter.next();
            for (int j = arr[i].length - 1; j >= 0; j--) {
                if (checkString == null || !(arr[i][j].equals("*") || arr[i][j].equals(checkString))) {
                    match = false;
                    break;
                }
                checkString = nameIter.hasNext() ? nameIter.next() : null;
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
