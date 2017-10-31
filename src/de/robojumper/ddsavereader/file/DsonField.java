package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;


// Dson for Darkest Json
public class DsonField {
	
	static final String[] FLOAT_FIELD_NAMES = {"current_hp", "m_Stress", "actor@buff_group@*@amount", "chapters@*@*@percent"};
	
	// TODO: Make array a special kind of field with an Inner type??
	static final String[] INTARRAY_FIELD_NAMES = {
		"read_page_indexes", "raid_read_page_indexes", "raid_unread_page_indexes",	// journal.json
		"dungeons_unlocked", // game_knowledge.json
		"goal_ids", "trinket_retention_ids",	// quest.json
		"last_party_guids", // roster.json
		"result_event_history", // town_event.json
		"additional_mash_disabled_infestation_monster_class_ids", // campaign_mash.json
		// example for how to make variable names more specific
		"party@heroes", // raid.json
		"narration_audio_event_queue_tags", // loading_screen.json
	};
	
	static final String[] STRINGARRAY_FIELD_NAMES = {
		"goal_ids", // quest.json
	};

	
	// When loading, all Integers will check for a matching hash and replace their display string with #"<name>" (where <name> is the unhashed string)
	// This is much better than trying to find a good reverse
	public static final HashMap<Integer, String> NAME_TABLE = new HashMap<Integer, String>();

	// TODO: map@bounds is a rect?
	public enum FieldType {
		TYPE_Object,	// has a Meta1Block entry
		TYPE_Bool,		// 1 byte, 0x00 or 0x01
		TYPE_Char,		// 1 byte, only seems to be used in upgrades.json 
		TYPE_TwoBool,	// aligned, 6 bytes (only used in gameplay options??)
		TYPE_String,	// aligned, int size + null-terminated string of size (including \0)
		TYPE_File,		// Actually an object, but encoded as a string (embedded DsonFile). only seems to be used in roster.json 
		TYPE_Int,		// aligned, 4 byte integer
		TYPE_Float,		// aligned, 4-byte float
		TYPE_IntArray,	// aligned. 4-byte int [length], then [length] 4-byte integers
		TYPE_StringArray,
		TYPE_Unknown
	};
	
	public FieldType Type = FieldType.TYPE_Unknown;
	private DsonField Parent;
	
	public String Name;
	
	public String DataString = "UNKNOWN. PLEASE PARSE TYPE";
	
	// Some strings are a full file.
	public DsonFile EmbeddedFile;
	// both only used when reading
	// raw data from JSON file, used to score Type
	public byte[] RawData;
	// the offset of this field from the beginning of the DATA block
	// (required since some types are aligned) 
	public int DataStartInFile;
	
	
	public int Meta1EntryIdx = -1;
	public int Meta2EntryIdx = -1;
	
	
	// ONLY for Object type!!
	public DsonField[] Children;
	
	// If external code has not determined this field to be TYPE_Object, guess the type
	public boolean GuessType() {
		if (ParseHardcodedType()) {
			return true;
		} else if (RawData.length == 1) { 
			if (RawData[0] == 0x00 || RawData[0] == 0x01) {
				Type = FieldType.TYPE_Bool;
				DataString = RawData[0] == 0x00 ? "False" : "True";
			} else {
				Type = FieldType.TYPE_Char;
				DataString = "'" + Character.toString((char)RawData[0]) + "'";
			}
		} else if (AlignedSize() == 8 && 
				(RawData[AlignmentSkip() + 0] == 0x00 || RawData[AlignmentSkip() + 0] == 0x01) &&
				(RawData[AlignmentSkip() + 4] == 0x00 || RawData[AlignmentSkip() + 4] == 0x01)) {
			Type = FieldType.TYPE_TwoBool;
			DataString = (RawData[AlignmentSkip() + 4] == 0x00 ? "False" : "True") + " " + (RawData[AlignmentSkip() + 4] == 0x00 ? "False" : "True");
		} else if (AlignedSize() == 4) {
			Type = FieldType.TYPE_Int;
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			int tempInt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			DataString = Integer.toString(tempInt);
			String UnHashed = NAME_TABLE.get(tempInt);
			if (UnHashed != null) {
				DataString = "#\"" + UnHashed + "\"";
			}
		} else if (ParseString()) {
			// Some strings are actually embedded files
			if (DataString.length() >= 6) {
				byte[] unquoteData = Arrays.copyOfRange(RawData, AlignmentSkip() + 4, RawData.length);
				byte[] tempHeader = Arrays.copyOfRange(unquoteData, 0, 4);
				if (Arrays.equals(tempHeader, DsonFile.MAGICNR_HEADER)) {
					Type = FieldType.TYPE_File;
					EmbeddedFile = new DsonFile(unquoteData, true);
					DataString = EmbeddedFile.GetJSonString(0, false);
				}
			}
		} else {
			return false;
		}
		
		return true;
	}
	
	private boolean ParseHardcodedType() {
		return ParseIntArray() || ParseStringArray() || ParseFloat();
	}
	
	private boolean ParseFloat() {
		if (NameInArray(FLOAT_FIELD_NAMES)) {
			if (AlignedSize() == 4) {
				Type = FieldType.TYPE_Float;
				byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
				float tempFlt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getFloat();
				DataString = Float.toString(tempFlt);
				return true;
			}
		}
		return false;
	}
	
	private boolean ParseStringArray() {
		if (NameInArray(STRINGARRAY_FIELD_NAMES)) {
			Type = FieldType.TYPE_StringArray;
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			@SuppressWarnings("unused")
			int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			// read the rest
			byte[] strings = Arrays.copyOfRange(RawData, AlignmentSkip() + 4, AlignmentSkip() + AlignedSize());
			ByteBuffer bf = ByteBuffer.wrap(strings).order(ByteOrder.LITTLE_ENDIAN);
			StringBuilder sb = new StringBuilder();
			sb.append("[");
			while (bf.remaining() > 0) {
				int strlen = bf.getInt();
				byte[] tempArr2 = Arrays.copyOfRange(RawData, AlignmentSkip() + 4 + bf.position(), AlignmentSkip() + 4 + bf.position() + strlen - 1);
				sb.append("\"" + new String(tempArr2) + "\"");
				bf.position(bf.position() + strlen);
				if (bf.remaining() > 0) {
					sb.append(",");
				}
			}
			sb.append("]");
			DataString = sb.toString();
			return true;
		}
		return false;
	}

	private boolean ParseIntArray() {
		if (NameInArray(INTARRAY_FIELD_NAMES)) {
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			if (AlignedSize() == (arrLen + 1) * 4) {
				Type = FieldType.TYPE_IntArray;
				byte[] tempArr2 = Arrays.copyOfRange(RawData, AlignmentSkip() + 4, AlignmentSkip() + (arrLen + 1) * 4);
				ByteBuffer Buffer = ByteBuffer.wrap(tempArr2).order(ByteOrder.LITTLE_ENDIAN);
				StringBuilder sb = new StringBuilder();
				sb.append("[");
				for (int i = 0; i < arrLen; i++) {
					int tempInt = Buffer.getInt();
					String UnHashed = NAME_TABLE.get(tempInt);
					if (UnHashed != null) {
						UnHashed = "#\"" + UnHashed + "\"";
						sb.append(UnHashed);
					} else {
						sb.append(Integer.toString(tempInt));
					}
					if (i != arrLen - 1) {
						sb.append(", ");
					}
				}
				sb.append("]");
				DataString = sb.toString();
				return true;
			}
		}
		return false;
	}
	
	private boolean NameInArray(String[] arr) {
		DsonField CheckField;
		boolean match;
		for (int i = 0; i < arr.length; i++) {
			String[] names = arr[i].split("@");
			match = true;
			CheckField = this;
			for (int j = names.length - 1; j >= 0; j--) {
				if (CheckField == null || !(names[j].equals("*") || names[j].equals(CheckField.Name))) {
					match = false;
					break;
				}
				CheckField = CheckField.Parent;
			}
			if (match) {
				return true;
			}
		}
		return false;
	}


	private boolean ParseString() {
		// A string has a 4-byte int for the length, followed by a null-term'd string. So it's at least 5 bytes long
		if (AlignedSize() >= 5) {
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			int strlen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
			// We can't read a null-term string because some strings actually include the null character (like embedded files)
			// String str = DsonFile.ReadNullTermString(RawData, AlignmentSkip() + 4);
			if (AlignedSize() == 4 + strlen) {
				Type = FieldType.TYPE_String;
				byte[] tempArr2 = Arrays.copyOfRange(RawData, AlignmentSkip() + 4, AlignmentSkip() + 4 + strlen - 1);
				DataString = "\"" + new String(tempArr2) + "\"";
				return true;
			}
		}
		return false;
		
	}
	
	private int RawSize() {
		return RawData.length;
	}

	// When loading, IF THIS FIELD'S TYPE WERE ALIGNED
	private int AlignedSize() {
		return RawSize() - AlignmentSkip();
	}
	
	private int AlignmentSkip() {
		return (4 - (DataStartInFile % 4)) % 4;
	}
	
	// ONLY for Object type!!
	public void SetNumChildren(int num) {
		Children = new DsonField[num];
	}

	// only if there are empty Children entries!
	public void AddChild(DsonField Field) {
		for (int i = 0; i < Children.length; i++) {
			if (Children[i] == null) {
				Children[i] = Field;
				Field.Parent = this;
				return;
			}
		}
		assert(false);
	}
	
	public boolean HasAllChilds() {
		for (int i = 0; i < Children.length; i++) {
			if (Children[i] == null) {
				return false;
			}
		}
		return true;
	}
	

}
