package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

import de.robojumper.ddsavereader.file.DsonField.FieldType;
import de.robojumper.ddsavereader.file.DsonFile.Meta1Block.Meta1BlockEntry;


public class DsonFile {
	
	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static byte[] MAGICNR_HEADER = {0x01, (byte) 0xB1, 0x00, 0x00};
	
	HeaderBlock Header;
	Meta1Block Meta1;
	Meta2Block Meta2;
	// The first field that is being deserialized is always base_root
	List<DsonField> RootFields;
	
	// Embed files are strings that have the last null-terminating character included in the data size
	public DsonFile(byte[] File, boolean bIsEmbed) {
		ByteBuffer Buffer = ByteBuffer.wrap(File);
		Buffer.order(ByteOrder.LITTLE_ENDIAN);
		
		// Read Header
		Header = new HeaderBlock();
		byte[] FileMagicNumber = new byte[4];
		Buffer.get(FileMagicNumber);
		assert(Arrays.equals(FileMagicNumber, Header.MagicNumber));
		Buffer.get(Header.epsilon);
		Header.lengthOfHeader = Buffer.getInt();
		Header.zeroes = Buffer.getInt();
		Header.numObjects16 = Buffer.getInt();
		Header.numObjects = Buffer.getInt();
		Header.startOfMeta1 = Buffer.getInt();
		Header.zeroes2 = Buffer.getLong();
		Header.zeroes3 = Buffer.getLong();
		Header.delta = Buffer.getInt();
		Header.startOfMeta2 = Buffer.getInt();
		Header.zeroes4 = Buffer.getInt();
		Header.lengthOfData = Buffer.getInt();
		Header.startOfData = Buffer.getInt();
		// technically the header could be longer, but we don't know what data would be there
		// so just skip to the Meta1 block
		{
			Buffer.position((int) Header.startOfMeta1);
			byte[] Meta1Data = new byte[Header.startOfMeta2 - Header.startOfMeta1];
			Buffer.get(Meta1Data);
			Meta1 = new Meta1Block(Meta1Data);
		}
		{
			// the buffer really should be at startOfMeta2 now
			assert(Buffer.position() == Header.startOfMeta2);
			byte[] Meta2Data = new byte[Header.startOfData - Header.startOfMeta2];
			Buffer.get(Meta2Data);
			Meta2 = new Meta2Block(Meta2Data);
		}
		{
			byte[] Data = new byte[Header.lengthOfData];
			Buffer.get(Data);
			assert(Buffer.remaining() == 0);
			// parse the objects
			Stack<DsonField> FieldStack = new Stack<DsonField>();
			// For HierarchyHint
			Stack<Integer> HierarchyStack = new Stack<Integer>();
			// base_root starts at -1
			int runningObjIdx = -1;
			HierarchyStack.push(new Integer(runningObjIdx));
			RootFields = new ArrayList<DsonField>();
			// Is this the correct way to do it?
			// WARNING: Apparently, META2 is not necessarily ordered the same way as DATA
			// This may have serious implications on Field Hierarchy.
			// It seems to work, in case it breaks, this is what you're looking for
			// This should also be revisited when trying to get saving implemented 
			for (int i = 0; i < Meta2.Entries.length; i++) {
				DsonField Field = new DsonField();
				int off = Meta2.Entries[i].offset;
				Field.Name = ReadNullTermString(Data, off);
				Field.Meta1EntryIdx = Meta2.Entries[i].GetMyMeta1BlockEntryIdx();
				Field.Meta2EntryIdx = i;
				// In the data, strings are null-term'd!
				off += Field.Name.length() + 1;
				Field.DataStartInFile = off;
				
				int dataLen;
				// Meta2.Entries are not sorted that way! Broke for embedded unit files
				/*if (i < Meta2.Entries.length - 1) {
					
					dataLen = Meta2.Entries[i+1].offset - off;
				} else {
					dataLen = Data.length + 1 - off;
				}*/
				int nextOff = Meta2.FindSmallestOffsetLargerThan(Meta2.Entries[i].offset);
				if (nextOff > 0) {
					dataLen = nextOff - off;
				} else {
					dataLen = Data.length - off;
				}
				Field.RawData = Arrays.copyOfRange(Data, off, off + dataLen);
				int[] objectsizes = Meta2.Entries[i].GetObjectTypeInfo();
				if (objectsizes != null) {
					// we are an object type
					Field.Type = FieldType.TYPE_Object;
					Field.DataString = "";
					Field.SetNumChildren(objectsizes[0]);
					assert(Meta2.Entries[i].GetMyMeta1BlockEntry().HierarchyHint == HierarchyStack.peek().intValue());
					runningObjIdx++;
				} else {
					Field.GuessType();
				}
				// Add the field
				// If our stack is empty, the field needs to be of type object!
				// (At least I haven't seen it any other way, since all files began with base_root 
				if (FieldStack.isEmpty()) {
					assert(Field.Type == FieldType.TYPE_Object);
					RootFields.add(Field);
				} else {
					// We have a stack element, add Field as a child
					FieldStack.peek().AddChild(Field);
				}
				// If we have an object, push it to the stack
				if (Field.Type == FieldType.TYPE_Object) {
					FieldStack.push(Field);
					HierarchyStack.push(new Integer(runningObjIdx));
				}
				
				// Then check if the object on top of the stack has all its children. If so, pop it
				// In case an object was the last child of an object, we do this recursively
				while (!FieldStack.isEmpty() && FieldStack.peek().Type == FieldType.TYPE_Object && FieldStack.peek().HasAllChilds()) {
					FieldStack.pop();
					HierarchyStack.pop();
				}
			}
			// we really should not have any pending fields at this point
			assert(FieldStack.isEmpty());
			// runningObjIdx starts at -1
			assert(runningObjIdx + 1 == Header.numObjects);
		}
	}
	
	static String ReadNullTermString(byte[] data, int start) {
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < data.length; i++) {
		    char c = (char)data[i];
		    if (c == '\0')
		    	break;
		    sb.append(c);
		}
		return sb.toString();
	}
	
	
	class HeaderBlock {
		byte[] MagicNumber = MAGICNR_HEADER;
		byte[] epsilon = {0x00, 0x00, 0x00, 0x00};
		int lengthOfHeader; // always 0x40?
		int zeroes;
		int numObjects16, numObjects; // numObjects16 = numObjects << 4
		int startOfMeta1; // always 0x40?
		long zeroes2;
		long zeroes3;
		int delta;
		int startOfMeta2;
		int zeroes4;
		int startOfData;
		int lengthOfData;
	}

	// The Meta1Block contains one entry for every Object field compressed in DATA.
	class Meta1Block {
		// list of contents
		Meta1BlockEntry[] Entries;
		
		Meta1Block(byte[] Data) {
			ByteBuffer Buffer = ByteBuffer.wrap(Data);
			Buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(Data.length % 0x10 == 0);
			// The Meta1 block should always have a size that is a multiple of 0x10
			Entries = new Meta1BlockEntry[Data.length / 0x10];
			for (int i = 0; Buffer.remaining() != 0; i++) {
				Entries[i] = new Meta1BlockEntry();
				Entries[i].HierarchyHint = Buffer.getInt();
				Entries[i].idx = Buffer.getInt();
				Entries[i].NumDirectChildren = Buffer.getInt();
				Entries[i].NumAllChildren = Buffer.getInt();
			}
		}
		
		// data structure encapsulating a single entry in the Meta1Block
		class Meta1BlockEntry {
			// index of this object in the data - 1, and all sibling objects have the same (i.e. inherit from the first) 
			int HierarchyHint;
			// index into Meta2Block.Entries
			int idx;
			// number of direct children fields of this property
			int NumDirectChildren;
			// number of all child fields
			int NumAllChildren;
		}
	}
	
	
	class Meta2Block {
		
		Meta2BlockEntry[] Entries;
		
		Meta2Block(byte[] Data) {
			ByteBuffer Buffer = ByteBuffer.wrap(Data);
			Buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(Data.length % 0x0C == 0);
			// The Meta2 block should always have a size that is a multiple of 0x10
			Entries = new Meta2BlockEntry[Data.length / 0x0C];
			for (int i = 0; Buffer.remaining() != 0; i++) {
				Entries[i] = new Meta2BlockEntry();
				Buffer.get(Entries[i].NameIdent);
				Entries[i].offset = Buffer.getInt();
				Buffer.get(Entries[i].TypeIdent);
			}
		}
		
		public int FindSmallestOffsetLargerThan(int off) {
			int bestIdx = -1;
			int bestOffset = -1;
			for (int i = 0; i < Entries.length; i++) {
				if (Entries[i].offset > off && ( bestIdx == - 1 || Entries[i].offset < bestOffset)) {
					bestIdx = i;
					bestOffset = Entries[i].offset;
					break;
				}
			}
			return bestOffset;
		}

		class Meta2BlockEntry {
			// Hash of the Field Name
			byte[] NameIdent = new byte[4];
			// offset from start of data block
			int offset;
			// unknown. TypeIdent might be misleading
			byte[] TypeIdent = new byte[4];
			
			
			Meta1BlockEntry GetMyMeta1BlockEntry() {
				int idx = GetMyMeta1BlockEntryIdx();
				if (idx < 0) {
					return null;
				} else {
					return Meta1.Entries[idx];
				}
			}
			
			int GetMyMeta1BlockEntryIdx() {
				for (int i = 0; i < Meta1.Entries.length; i++) {
					// For some reason I thought Meta1 only includes one version for every type hash. Commented out
//					if (Arrays.equals(NameIdent, Entries[Meta1.Entries[i].idx].NameIdent)) {
					if (Entries[Meta1.Entries[i].idx] == this) {
						return i; 
					}
				}
				return -1;
			}
			
			// If this is an object type, the return value has a length of two (2),
			// where the first entry is the number of direct children and the second one the number of all
			// otherwise, return null
			int[] GetObjectTypeInfo() {
				Meta1BlockEntry Entry;
				Entry = GetMyMeta1BlockEntry();
				if (Entry != null) {	
					int[] ret = new int[2];
					ret[0] = Entry.NumDirectChildren;
					ret[1] = Entry.NumAllChildren;
					return ret;
				}
				return null;
			}
		}
	}

	// If bDebug is true, note that this is not valid JSON, but should be after removing all comments
	// Comments contain debug info that might come in handy. This debug info is just unknown hex fields
	public String GetJSonString(int indent, boolean debug) {
		StringBuilder sb = new StringBuilder();
		if (debug) {
			sb.append("// HEADER: ");
			sb.append("gamma: " + Header.numObjects16 + " (gamma >> 4: " + Header.numObjects + ")");
		}
		sb.append(indt(indent)+"{\n");
		indent++;
		
		for (int i = 0; i < RootFields.size(); i++) {
			WriteField(sb, RootFields.get(i), indent, debug);
			if (i != RootFields.size() - 1) {
				sb.append(",");
			}
			sb.append("\n");
		}
		indent--;
		sb.append(indt(indent)+"}\n");
		return sb.toString();
	}
	
	// TODO: Properly handle Types
	private void WriteField(StringBuilder sb, DsonField Field, int indent, boolean debug) { 

		if (debug) {
			sb.append(indt(indent) + "// INFO ");
			// every field has a Meta2Index
			sb.append("Meta2_TypeHash: 0x" + LEBytesToHexStr(Meta2.Entries[Field.Meta2EntryIdx].TypeIdent) + " ");
			sb.append("\n");	
		}
		
		sb.append(indt(indent) + "\"" + Field.Name + "\" : ");
		if (Field.Type == FieldType.TYPE_Object) {
			WriteObject(sb, Field, indent, debug);
		} else if (Field.Type == FieldType.TYPE_File) {
			// HACK: rebuild string for indent, debug
			sb.append(Field.EmbeddedFile.GetJSonString(indent, debug));
		} else {
			sb.append(Field.DataString);
		}
		
	}
	
	private void WriteObject(StringBuilder sb, DsonField Field, int indent, boolean debug) {
		if (Field.Children.length > 0) {
			sb.append("{\n");
			indent++;
			for (int i = 0; i < Field.Children.length; i++) {
				WriteField(sb, Field.Children[i], indent, debug);
				if (i != Field.Children.length - 1) {
					sb.append(",");
				}
				sb.append("\n");
			}			
			indent--;
			sb.append(indt(indent)+"}");
		} else {
			// save a line or two on empty objects
			sb.append("{ }");
		}
	}
	
	// adapted from https://stackoverflow.com/a/9855338
	// returns the hex representation of a Little-Endian byte array
	static String LEBytesToHexStr(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			//int v = bytes[i] & 0xFF;
			int v = bytes[bytes.length - 1 - i] & 0xFF;
	        hexChars[i * 2] = hexArray[v >>> 4];
	        hexChars[i * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}
	
	public static String indt(int num){
	    StringBuilder sb = new StringBuilder();
	    for(int i = 0; i < num; i++){
	        sb.append("    ");
	    }
	    return sb.toString();
	}
	
	// Seems to be correct
	static int StringHash(String str) {
		int hash = 0;
		for (int i = 0; i < str.length(); i++) {
			hash = hash * 53 + str.charAt(i);
		}
		return hash;
	}
}
