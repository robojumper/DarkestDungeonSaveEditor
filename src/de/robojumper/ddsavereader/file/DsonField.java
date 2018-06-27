package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;


// Dson for Darkest Json
public class DsonField {
	
	static final String[] FLOAT_FIELD_NAMES = {"current_hp", "m_Stress", "actor@buff_group@*@amount", "chapters@*@*@percent", "non_rolled_additional_chances@*@chance"};
	
	// TODO: Make array a special kind of field with an Inner type??
	static final String[] INTARRAY_FIELD_NAMES = {
		"read_page_indexes", "raid_read_page_indexes", "raid_unread_page_indexes",	// journal.json
		"dungeons_unlocked", "played_video_list", // game_knowledge.json
		"goal_ids", "trinket_retention_ids",	// quest.json
		"last_party_guids", "dungeon_history", // roster.json
		"result_event_history", // town_event.json
		"additional_mash_disabled_infestation_monster_class_ids", // campaign_mash.json
		// example for how to make variable names more specific
		"party@heroes", // raid.json
		"narration_audio_event_queue_tags", // loading_screen.json
	};
	
	static final String[] STRINGARRAY_FIELD_NAMES = {
		"goal_ids", // quest.json
	};
	
	static final String[] FLOATVECTOR_FIELD_NAMES = {
		"map@bounds", "areas@*@bounds", "areas@*@tiles@*@mappos", "areas@*@tiles@*@sidepos", // map.json  
	};

	
	// When loading, all Integers will check for a matching hash and replace their display string with #"<name>" (where <name> is the unhashed string)
	// This is much better than trying to find a good reverse. 
	public static final HashMap<Integer, String> NAME_TABLE = new HashMap<Integer, String>();
	
	static final String STR_TRUE = "true";
	static final String STR_FALSE = "false";

	public enum FieldType {
		TYPE_Object,        // has a Meta1Block entry
		TYPE_Bool,          // 1 byte, 0x00 or 0x01
		TYPE_Char,          // 1 byte, only seems to be used in upgrades.json 
		TYPE_TwoBool,       // aligned, 8 bytes (only used in gameplay options??). emitted as t[true, true]
		TYPE_String,        // aligned, int size + null-terminated string of size (including \0)
		TYPE_File,          // Actually an object, but encoded as a string (embedded DsonFile). used in roster.json and map.json 
		TYPE_Int,           // aligned, 4 byte integer
		// Begin hardcode types: these types do not have enough characteristics to make the heuristic work
		// As such, the field names/paths are hardcoded above
		// Fields matching the names will ALWAYS assume the corresponding type, even if parsing fails
		// So they should be used sparingly and be as specific as possible
		TYPE_Float,         // aligned, 4-byte float
		TYPE_IntArray,      // aligned. 4-byte int [count], then [count] 4-byte integers
		TYPE_StringArray,   // aligned, 4-byte int [count], then [count] null-terminated strings
		TYPE_FloatVector,   // aligned, arbitrary number of 4-byte floats. emitted as v[1.0, 2.0, ...]
		TYPE_Unknown
	};
	
	public FieldType type = FieldType.TYPE_Unknown;
	private DsonField parent;
	
	public String name;

	public String dataString = "\"UNKNOWN. PLEASE PARSE TYPE\"";
	
	private String hashedValue;
	
	// Some strings are a full file.
	public DsonFile embeddedFile;
	// both only used when reading
	// raw data from JSON file, used to score Type
	public byte[] rawData;
	// the offset of this field from the beginning of the DATA block
	// (required since some types are aligned) 
	public int dataStartInFile;
	
	
	public int meta1EntryIdx = -1;
	public int meta2EntryIdx = -1;
	
	
	// ONLY for Object type!!
	public DsonField[] children;
	
	// If external code has not determined this field to be TYPE_Object, guess the type
	public boolean guessType() {
		if (parseHardcodedType()) {
			return true;
		} else if (rawData.length == 1) {
			if (rawData[0] == 0x00 || rawData[0] == 0x01) {
				type = FieldType.TYPE_Bool;
				dataString = rawData[0] == 0x00 ? STR_FALSE : STR_TRUE;
			} else {
				type = FieldType.TYPE_Char;
				dataString = "\"" + Character.toString((char)rawData[0]) + "\"";
			}
		} else if (alignedSize() == 8 &&
				(rawData[alignmentSkip() + 0] == 0x00 || rawData[alignmentSkip() + 0] == 0x01) &&
				(rawData[alignmentSkip() + 4] == 0x00 || rawData[alignmentSkip() + 4] == 0x01)) {
			type = FieldType.TYPE_TwoBool;
			dataString = "[" + (rawData[alignmentSkip() + 4] == 0x00 ? STR_FALSE : STR_TRUE) + ", " + (rawData[alignmentSkip() + 4] == 0x00 ? STR_FALSE : STR_TRUE) + "]";
		} else if (alignedSize() == 4) {
			type = FieldType.TYPE_Int;
			byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
			int tempInt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			dataString = Integer.toString(tempInt);
			String unHashed = NAME_TABLE.get(tempInt);
			if (unHashed != null) {
			    hashedValue = dataString;
				dataString = "\"" + unHashed + "\"";
			}
		} else if (parseString()) {
			// Some strings are actually embedded files
			if (dataString.length() >= 6) {
				byte[] unquoteData = Arrays.copyOfRange(rawData, alignmentSkip() + 4, rawData.length);
				byte[] tempHeader = Arrays.copyOfRange(unquoteData, 0, 4);
				if (Arrays.equals(tempHeader, DsonFile.MAGICNR_HEADER)) {
					type = FieldType.TYPE_File;
					embeddedFile = new DsonFile(unquoteData, true);
					dataString = embeddedFile.getJSonString(0, false);
				}
			}
		} else {
			return false;
		}
		
		return true;
	}
	
	private boolean parseHardcodedType() {
		return parseFloatVector() || parseIntArray() || parseStringArray() || parseFloat();
	}
	
	private boolean parseFloat() {
		if (nameInArray(FLOAT_FIELD_NAMES)) {
			if (alignedSize() == 4) {
				type = FieldType.TYPE_Float;
				byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
				float tempFlt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getFloat();
				dataString = Float.toString(tempFlt);
				return true;
			}
		}
		return false;
	}
	
	private boolean parseStringArray() {
		if (nameInArray(STRINGARRAY_FIELD_NAMES)) {
			type = FieldType.TYPE_StringArray;
			byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
			@SuppressWarnings("unused")
			int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			// read the rest
			byte[] strings = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + alignedSize());
			ByteBuffer bf = ByteBuffer.wrap(strings).order(ByteOrder.LITTLE_ENDIAN);
			StringBuilder sb = new StringBuilder();
			sb.append("[");
			while (bf.remaining() > 0) {
				int strlen = bf.getInt();
				byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4 + bf.position(), alignmentSkip() + 4 + bf.position() + strlen - 1);
				sb.append("\"" + new String(tempArr2) + "\"");
				bf.position(bf.position() + strlen);
				if (bf.remaining() > 0) {
					sb.append(", ");
				}
			}
			sb.append("]");
			dataString = sb.toString();
			return true;
		}
		return false;
	}

	private boolean parseIntArray() {
		if (nameInArray(INTARRAY_FIELD_NAMES)) {
			byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
			int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			if (alignedSize() == (arrLen + 1) * 4) {
				type = FieldType.TYPE_IntArray;
				byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + (arrLen + 1) * 4);
				ByteBuffer buffer = ByteBuffer.wrap(tempArr2).order(ByteOrder.LITTLE_ENDIAN);
				StringBuilder sb = new StringBuilder();
				StringBuilder hsb = new StringBuilder();
				sb.append("[");
				hsb.append("[");
				boolean foundHashed = false;
				for (int i = 0; i < arrLen; i++) {
					int tempInt = buffer.getInt();
					String unHashed = NAME_TABLE.get(tempInt);
					if (unHashed != null) {
						unHashed = "\"" + unHashed + "\"";
						sb.append(unHashed);
						hsb.append(tempInt);
						foundHashed = true;
					} else {
						sb.append(Integer.toString(tempInt));
						hsb.append(Integer.toString(tempInt));
					}
					if (i != arrLen - 1) {
						sb.append(", ");
						hsb.append(", ");
					}
				}
				sb.append("]");
				hsb.append("]");
				dataString = sb.toString();

				if (foundHashed) {
				    hashedValue = hsb.toString();
				}
				return true;
			}
		}
		return false;
	}
	
	private boolean parseFloatVector() {
		if (nameInArray(FLOATVECTOR_FIELD_NAMES)) {
			type = FieldType.TYPE_FloatVector;
			byte[] floats = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + alignedSize());
			ByteBuffer bf = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN);
			StringBuilder sb = new StringBuilder();
			// v for vector
			sb.append("[");
			while (bf.remaining() > 0) {
				float f = bf.getFloat();
				sb.append(Float.toString(f));
				if (bf.remaining() > 0) {
					sb.append(", ");
				}
			}
			sb.append("]");
			dataString = sb.toString();
			return true;
		}
		return false;
	}
	
	private boolean nameInArray(String[] arr) {
		DsonField checkField;
		boolean match;
		for (int i = 0; i < arr.length; i++) {
			String[] names = arr[i].split("@");
			match = true;
			checkField = this;
			for (int j = names.length - 1; j >= 0; j--) {
				if (checkField == null || !(names[j].equals("*") || names[j].equals(checkField.name))) {
					match = false;
					break;
				}
				checkField = checkField.parent;
			}
			if (match) {
				return true;
			}
		}
		return false;
	}


	private boolean parseString() {
		// A string has a 4-byte int for the length, followed by a null-term'd string. So it's at least 5 bytes long
		if (alignedSize() >= 5) {
			byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
			int strlen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			// We can't read a null-term string because some strings actually include the null character (like embedded files)
			// String str = DsonFile.ReadNullTermString(RawData, AlignmentSkip() + 4);
			if (alignedSize() == 4 + strlen) {
				type = FieldType.TYPE_String;
				byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + 4 + strlen - 1);
				dataString = "\"" + new String(tempArr2) + "\"";
				return true;
			}
		}
		return false;
		
	}
	
	private int rawSize() {
		return rawData.length;
	}

	// When loading, IF THIS FIELD'S TYPE WERE ALIGNED
	private int alignedSize() {
		return rawSize() - alignmentSkip();
	}
	
	private int alignmentSkip() {
		return (4 - (dataStartInFile % 4)) % 4;
	}
	
	// ONLY for Object type!!
	public void setNumChildren(int num) {
		children = new DsonField[num];
	}

	// only if there are empty Children entries!
	public void addChild(DsonField Field) {
		for (int i = 0; i < children.length; i++) {
			if (children[i] == null) {
				children[i] = Field;
				Field.parent = this;
				return;
			}
		}
		assert(false);
	}
	
	public boolean hasAllChilds() {
		for (int i = 0; i < children.length; i++) {
			if (children[i] == null) {
				return false;
			}
		}
		return true;
	}

	public String getExtraComments() {
	    StringBuilder sb = new StringBuilder();
	    sb.append("Type: ");
	    sb.append(type.name());
	    if (hashedValue != null) {
	        sb.append(", Hashed Integer(s): ");
	        sb.append(hashedValue);
	    }

	    if (type == FieldType.TYPE_Unknown) {
	        sb.append(", Raw Data: ");
	        sb.append(DsonFile.LEBytesToHexStr(rawData));
	    }
	    return sb.toString();
	}
}
