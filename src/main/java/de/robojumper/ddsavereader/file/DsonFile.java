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
	
	HeaderBlock header;
	Meta1Block meta1;
	Meta2Block meta2;
	// The first field that is being deserialized is always base_root
	List<DsonField> rootFields;
	
	public DsonFile(byte[] File) {
	    this(File, false);
	}
	
	// Embed files are strings that have the last null-terminating character included in the data size
	public DsonFile(byte[] File, boolean bIsEmbed) {
		ByteBuffer buffer = ByteBuffer.wrap(File);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		
		// Read Header
		header = new HeaderBlock();
		byte[] fileMagicNumber = new byte[4];
		buffer.get(fileMagicNumber);
		assert(Arrays.equals(fileMagicNumber, header.MagicNumber));
		buffer.get(header.epsilon);
		header.lengthOfHeader = buffer.getInt();
		header.zeroes = buffer.getInt();
		header.numObjects16 = buffer.getInt();
		header.numObjects = buffer.getInt();
		header.startOfMeta1 = buffer.getInt();
		header.zeroes2 = buffer.getLong();
		header.zeroes3 = buffer.getLong();
		header.numMeta2Entries = buffer.getInt();
		header.startOfMeta2 = buffer.getInt();
		header.zeroes4 = buffer.getInt();
		header.lengthOfData = buffer.getInt();
		header.startOfData = buffer.getInt();
		// technically the header could be longer, but we don't know what data would be there
		// so just skip to the Meta1 block
		{
			buffer.position((int) header.startOfMeta1);
			byte[] Meta1Data = new byte[header.startOfMeta2 - header.startOfMeta1];
			buffer.get(Meta1Data);
			meta1 = new Meta1Block(Meta1Data);
		}
		{
			// the buffer really should be at startOfMeta2 now
			assert(buffer.position() == header.startOfMeta2);
			byte[] Meta2Data = new byte[header.startOfData - header.startOfMeta2];
			buffer.get(Meta2Data);
			meta2 = new Meta2Block(Meta2Data);
			assert(header.numMeta2Entries == meta2.entries.length);;
		}
		{
			byte[] Data = new byte[header.lengthOfData];
			buffer.get(Data);
			assert(buffer.remaining() == 0);
			// parse the objects
			Stack<DsonField> fieldStack = new Stack<DsonField>();
			// For HierarchyHint
			Stack<Integer> hierarchyStack = new Stack<Integer>();
			// base_root starts at -1
			int runningObjIdx = -1;
			hierarchyStack.push(new Integer(runningObjIdx));
			rootFields = new ArrayList<DsonField>();
			// Is this the correct way to do it?
			// WARNING: Apparently, META2 is not necessarily ordered the same way as DATA
			// This may have serious implications on Field Hierarchy.
			// It seems to work, in case it breaks, this is what you're looking for
			// This should also be revisited when trying to get saving implemented 
			for (int i = 0; i < meta2.entries.length; i++) {
				DsonField field = new DsonField();
				int off = meta2.entries[i].offset;
				field.name = readNullTermString(Data, off);
				field.meta1EntryIdx = meta2.entries[i].getMyMeta1BlockEntryIdx();
				field.meta2EntryIdx = i;
				// In the data, strings are null-term'd!
				off += field.name.length() + 1;
				field.dataStartInFile = off;
				
				int dataLen;
				// Meta2.Entries are not sorted that way! Broke for embedded unit files
				/*if (i < Meta2.Entries.length - 1) {
					
					dataLen = Meta2.Entries[i+1].offset - off;
				} else {
					dataLen = Data.length + 1 - off;
				}*/
				int nextOff = meta2.findSmallestOffsetLargerThan(meta2.entries[i].offset);
				if (nextOff > 0) {
					dataLen = nextOff - off;
				} else {
					dataLen = Data.length - off;
				}
				field.rawData = Arrays.copyOfRange(Data, off, off + dataLen);
				int[] objectsizes = meta2.entries[i].getObjectTypeInfo();
				if (objectsizes != null) {
					// we are an object type
					field.type = FieldType.TYPE_Object;
					field.dataString = "";
					field.setNumChildren(objectsizes[0]);
					assert(meta2.entries[i].getMyMeta1BlockEntry().hierarchyHint == hierarchyStack.peek().intValue());
					runningObjIdx++;
				}
				// Add the field
				// If our stack is empty, the field needs to be of type object!
				// (At least I haven't seen it any other way, since all files began with base_root 
				if (fieldStack.isEmpty()) {
					assert(field.type == FieldType.TYPE_Object);
					rootFields.add(field);
				} else {
					// We have a stack element, add Field as a child
					fieldStack.peek().addChild(field);
				}
				// now guess the type that it knows about its parents
				if (field.type != FieldType.TYPE_Object) {
					field.guessType();
				}
				
				// If we have an object, push it to the stack
				if (field.type == FieldType.TYPE_Object) {
					fieldStack.push(field);
					hierarchyStack.push(new Integer(runningObjIdx));
				}

				// Then check if the object on top of the stack has all its children. If so, pop it
				// In case an object was the last child of an object, we do this recursively
				while (!fieldStack.isEmpty() && fieldStack.peek().type == FieldType.TYPE_Object && fieldStack.peek().hasAllChilds()) {
					fieldStack.pop();
					hierarchyStack.pop();
				}
			}
			// we really should not have any pending fields at this point
			assert(fieldStack.isEmpty());
			// runningObjIdx starts at -1
			assert(runningObjIdx + 1 == header.numObjects);
		}
	}
	
	static String readNullTermString(byte[] data, int start) {
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
		int numMeta2Entries;
		int startOfMeta2;
		int zeroes4;
		int startOfData;
		int lengthOfData;
	}

	// The Meta1Block contains one entry for every Object field compressed in DATA.
	class Meta1Block {
		// list of contents
		Meta1BlockEntry[] entries;
		
		Meta1Block(byte[] data) {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(data.length % 0x10 == 0);
			// The Meta1 block should always have a size that is a multiple of 0x10
			entries = new Meta1BlockEntry[data.length / 0x10];
			for (int i = 0; buffer.remaining() != 0; i++) {
				entries[i] = new Meta1BlockEntry();
				entries[i].hierarchyHint = buffer.getInt();
				entries[i].idx = buffer.getInt();
				entries[i].numDirectChildren = buffer.getInt();
				entries[i].numAllChildren = buffer.getInt();
			}
		}
		
		// data structure encapsulating a single entry in the Meta1Block
		class Meta1BlockEntry {
			// index of this object in the data - 1, and all sibling objects have the same (i.e. inherit from the first) 
			int hierarchyHint;
			// index into Meta2Block.Entries
			int idx;
			// number of direct children fields of this property
			int numDirectChildren;
			// number of all child fields
			int numAllChildren;
		}
	}
	
	
	class Meta2Block {
		
		Meta2BlockEntry[] entries;
		
		Meta2Block(byte[] data) {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(data.length % 0x0C == 0);
			// The Meta2 block should always have a size that is a multiple of 0x10
			entries = new Meta2BlockEntry[data.length / 0x0C];
			for (int i = 0; buffer.remaining() != 0; i++) {
				entries[i] = new Meta2BlockEntry();
				buffer.get(entries[i].nameIdent);
				entries[i].offset = buffer.getInt();
				buffer.get(entries[i].typeIdent);
			}
		}
		
		public int findSmallestOffsetLargerThan(int off) {
			int bestIdx = -1;
			int bestOffset = -1;
			for (int i = 0; i < entries.length; i++) {
				if (entries[i].offset > off && ( bestIdx == - 1 || entries[i].offset < bestOffset)) {
					bestIdx = i;
					bestOffset = entries[i].offset;
					break;
				}
			}
			return bestOffset;
		}

		class Meta2BlockEntry {
			// Hash of the Field Name
			byte[] nameIdent = new byte[4];
			// offset from start of data block
			int offset;
			// unknown. TypeIdent might be misleading
			byte[] typeIdent = new byte[4];
			
			
			Meta1BlockEntry getMyMeta1BlockEntry() {
				int idx = getMyMeta1BlockEntryIdx();
				if (idx < 0) {
					return null;
				} else {
					return meta1.entries[idx];
				}
			}
			
			int getMyMeta1BlockEntryIdx() {
				for (int i = 0; i < meta1.entries.length; i++) {
					// For some reason I thought Meta1 only includes one version for every type hash. Commented out
//					if (Arrays.equals(NameIdent, Entries[Meta1.Entries[i].idx].NameIdent)) {
					if (entries[meta1.entries[i].idx] == this) {
						return i; 
					}
				}
				return -1;
			}
			
			// If this is an object type, the return value has a length of two (2),
			// where the first entry is the number of direct children and the second one the number of all
			// otherwise, return null
			int[] getObjectTypeInfo() {
				Meta1BlockEntry entry;
				entry = getMyMeta1BlockEntry();
				if (entry != null) {
					int[] ret = new int[2];
					ret[0] = entry.numDirectChildren;
					ret[1] = entry.numAllChildren;
					return ret;
				}
				return null;
			}
		}
	}

	// If bDebug is true, note that this is not valid JSON, but should be after removing all comments
	// Comments contain debug info that might come in handy. This debug info is just unknown hex fields
	public String getJSonString(int indent, boolean debug) {
		StringBuilder sb = new StringBuilder();
		if (debug) {
			//sb.append("// HEADER: ");
		}
		sb.append(indt(indent)+"{\n");
		indent++;
		
		for (int i = 0; i < rootFields.size(); i++) {
			writeField(sb, rootFields.get(i), indent, debug);
			if (i != rootFields.size() - 1) {
				sb.append(",");
			}
			sb.append("\n");
		}
		indent--;
		sb.append(indt(indent)+"}\n");
		return sb.toString();
	}
	
	@Override
	public String toString() {
	    return getJSonString(0, false);
	}
	
	// TODO: Properly handle Types
	private void writeField(StringBuilder sb, DsonField field, int indent, boolean debug) { 

		if (debug) {
			sb.append(indt(indent) + "// INFO ");
			// every field has a Meta2Index
			sb.append("Meta2_Unknown: 0x" + LEBytesToHexStr(meta2.entries[field.meta2EntryIdx].typeIdent) + " ");
			sb.append(field.getExtraComments());
			sb.append("\n");
		}
		
		sb.append(indt(indent) + "\"" + field.name + "\" : ");
		if (field.type == FieldType.TYPE_Object) {
			writeObject(sb, field, indent, debug);
		} else if (field.type == FieldType.TYPE_File) {
			// HACK: rebuild string for indent, debug
			sb.append(field.embeddedFile.getJSonString(indent, debug));
		} else {
			sb.append(field.dataString);
		}
		
	}
	
	private void writeObject(StringBuilder sb, DsonField field, int indent, boolean debug) {
		if (field.children.length > 0) {
			sb.append("{\n");
			indent++;
			for (int i = 0; i < field.children.length; i++) {
				writeField(sb, field.children[i], indent, debug);
				if (i != field.children.length - 1) {
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
	public static int stringHash(String str) {
		int hash = 0;
		for (int i = 0; i < str.length(); i++) {
			hash = hash * 53 + str.charAt(i);
		}
		return hash;
	}
}
