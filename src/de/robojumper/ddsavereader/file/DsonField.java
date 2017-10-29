package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.util.Arrays;

// Dson for Darkest Json
public class DsonField {
	
	// TODO: Float, IntArray
	// Float has the same pattern as Int. Use a heuristic that maybe parses it as a float if it results in a "nicer" number?
	// Empty IntArray is an aligned 00 00 00 00, which is parsed as INT 0.
	// Non-empty int arrays are Unknown
	
	// Alternatively, harcode
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
		TYPE_Unknown
	};
	
	public FieldType Type = FieldType.TYPE_Unknown;
	
	
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
		
		if (RawData.length == 1) { 
			if (RawData[0] == 0x00 || RawData[0] == 0x01) {
				Type = FieldType.TYPE_Bool;
				DataString = RawData[0] == 0x00 ? "False" : "True";
			} else {
				Type = FieldType.TYPE_Char;
				DataString = "'" + Character.toString((char)RawData[0]) + "'";
			}
		} else if (AlignedSize() == 8 && 
				RawData[AlignmentSkip() + 0] == RawData[AlignmentSkip() + 4] && 
				(RawData[AlignmentSkip() + 0] == 0x00 || RawData[AlignmentSkip() + 0] == 0x01)) {
			Type = FieldType.TYPE_TwoBool;
			DataString = RawData[0 + AlignmentSkip()] == 0x00 ? "False" : "True";
		} else if (AlignedSize() == 4) {
			Type = FieldType.TYPE_Int;
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			int tempInt = ByteBuffer.wrap(tempArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
			DataString = Integer.toString(tempInt);
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
	
	private boolean ParseString() {
		// A string has a 4-byte int for the length, followed by a null-term'd string. So it's at least 5 bytes long
		if (AlignedSize() >= 5) {
			byte[] tempArr = Arrays.copyOfRange(RawData, AlignmentSkip(), AlignmentSkip() + 4);
			int strlen = ByteBuffer.wrap(tempArr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
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
