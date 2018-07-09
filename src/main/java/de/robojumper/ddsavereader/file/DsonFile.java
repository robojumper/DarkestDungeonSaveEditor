package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

import de.robojumper.ddsavereader.file.DsonField.FieldType;
import de.robojumper.ddsavereader.file.DsonFile.Meta2Block.Meta2BlockEntry;


public class DsonFile {
	
	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static byte[] MAGICNR_HEADER = {0x01, (byte) 0xB1, 0x00, 0x00};
	
	HeaderBlock header;
	Meta1Block meta1;
	Meta2Block meta2;
	// The first field that is being deserialized is always base_root
	List<DsonField> rootFields;
	
	boolean autoUnhashNames;
	
	// Embed files are strings that have the last null-terminating character included in the data size
	public DsonFile(byte[] File, boolean autoUnhashNames) {
	    this.autoUnhashNames = autoUnhashNames;
		ByteBuffer buffer = ByteBuffer.wrap(File);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		
		// Read Header
		header = new HeaderBlock();
		byte[] fileMagicNumber = new byte[4];
		buffer.get(fileMagicNumber);
		assert(Arrays.equals(fileMagicNumber, header.MagicNumber));
		buffer.get(header.epsilon);
		header.headerLength = buffer.getInt();
		header.zeroes = buffer.getInt();
		header.meta1Size = buffer.getInt();
		header.numMeta1Entries = buffer.getInt();
		header.meta1Offset = buffer.getInt();
		header.zeroes2 = buffer.getLong();
		header.zeroes3 = buffer.getLong();
		header.numMeta2Entries = buffer.getInt();
		header.meta2Offset = buffer.getInt();
		header.zeroes4 = buffer.getInt();
		header.dataLength = buffer.getInt();
		header.dataOffset = buffer.getInt();
		// technically the header could be longer, but we don't know what data would be there
		// so just skip to the Meta1 block
		{
			buffer.position((int) header.meta1Offset);
			byte[] Meta1Data = new byte[header.meta2Offset - header.meta1Offset];
			buffer.get(Meta1Data);
			meta1 = new Meta1Block(Meta1Data);
		}
		{
			// the buffer really should be at startOfMeta2 now
			assert(buffer.position() == header.meta2Offset);
			byte[] Meta2Data = new byte[header.dataOffset - header.meta2Offset];
			buffer.get(Meta2Data);
			meta2 = new Meta2Block(Meta2Data);
			assert(header.numMeta2Entries == meta2.entries.length);;
		}
		{
			byte[] Data = new byte[header.dataLength];
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
			    Meta2BlockEntry meta2Entry = meta2.entries[i];
				DsonField field = new DsonField();
				int off = meta2Entry.offset;
				field.name = readNullTermString(Data, off);
				assert(field.name.length() == meta2Entry.getNameStringLength() - 1);
				assert(stringHash(field.name) == meta2Entry.nameHash);
				if (meta2Entry.isObject()) {
				    field.meta1EntryIdx = meta2Entry.getMeta1BlockEntryIdx();
				}
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
				if (meta2Entry.isObject()) {
					// we are an object type
					field.type = FieldType.TYPE_Object;
					field.setNumChildren(meta1.entries[meta2Entry.getMeta1BlockEntryIdx()].numDirectChildren);
					assert(meta1.entries[meta2Entry.getMeta1BlockEntryIdx()].hierarchyHint == hierarchyStack.peek().intValue());
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
					field.guessType(this.autoUnhashNames);
				}
				
				// If we have an object, push it to the stack
				if (field.type == FieldType.TYPE_Object) {
					fieldStack.push(field);
					hierarchyStack.push(new Integer(runningObjIdx));
				}

				// Then check if the object on top of the stack has all its children. If so, pop it
				// In case an object was the last child of an object, we do this iteratively
				while (!fieldStack.isEmpty() && fieldStack.peek().type == FieldType.TYPE_Object && fieldStack.peek().hasAllChilds()) {
					fieldStack.pop();
					hierarchyStack.pop();
				}
			}
			// we really should not have any pending fields at this point
			assert(fieldStack.isEmpty());
			// runningObjIdx starts at -1
			assert(runningObjIdx + 1 == header.numMeta1Entries);
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
	
	
	static class HeaderBlock {
		byte[] MagicNumber = MAGICNR_HEADER;
		byte[] epsilon = {0x00, 0x00, 0x00, 0x00};
		int headerLength; // = header size
		int zeroes = 0;
		int meta1Size; // = numMeta1Entries << 4
		int numMeta1Entries;
		int meta1Offset; // = header size
		long zeroes2 = 0;
		long zeroes3 = 0;
		int numMeta2Entries;
		int meta2Offset;
		int zeroes4 = 0;
	    int dataLength;
		int dataOffset; 
	}

	// The Meta1Block contains one entry for every Object field compressed in DATA.
	static class Meta1Block {
		// list of contents
		Meta1BlockEntry[] entries;
		
		Meta1Block() {
        }
		
		Meta1Block(byte[] data) {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(data.length % 0x10 == 0);
			// The Meta1 block should always have a size that is a multiple of 0x10
			entries = new Meta1BlockEntry[data.length / 0x10];
			for (int i = 0; buffer.remaining() != 0; i++) {
				entries[i] = new Meta1BlockEntry();
				entries[i].hierarchyHint = buffer.getInt();
				entries[i].meta2EntryIdx = buffer.getInt();
				entries[i].numDirectChildren = buffer.getInt();
				entries[i].numAllChildren = buffer.getInt();
			}
		}
		
		// data structure encapsulating a single entry in the Meta1Block
		static class Meta1BlockEntry {
			// index of this object in the data - 1, and all sibling objects have the same (i.e. inherit from the first) 
			int hierarchyHint;
			// index into Meta2Block.Entries
			int meta2EntryIdx;
			// number of direct children fields of this property
			int numDirectChildren;
			// number of all child fields
			int numAllChildren;
		}
	}
	
	
	static class Meta2Block {
		
		Meta2BlockEntry[] entries;
		
		Meta2Block() {
        }
		
		Meta2Block(byte[] data) {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			assert(data.length % 0x0C == 0);
			// The Meta2 block should always have a size that is a multiple of 0x0C
			entries = new Meta2BlockEntry[data.length / 0x0C];
			for (int i = 0; buffer.remaining() != 0; i++) {
				entries[i] = new Meta2BlockEntry();
				entries[i].nameHash = buffer.getInt();
				entries[i].offset = buffer.getInt();
				entries[i].fieldInfo = buffer.getInt();
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

		static class Meta2BlockEntry {
			// Hash of the Field Name
			int nameHash;
			// offset from start of data block
			int offset;
			// Bitmask
			// XXXX XXXX XXXX XXXX XXXX XXXX XXXX XXXX
			//                                       - 1 if object, 0 if not
			//                                      -  Unknown (Always 0?)
			//                           --- ---- --   Name string length, HOW LONG IS IT?
			//  --- ---- ---- ---- ---- -              Object index, HOW LONG IS IT?
			// -                                       Memory junk?
			int fieldInfo;
			
			boolean isObject() {
			    return (fieldInfo & 0b1) == 1;
			}
			
			int getMeta1BlockEntryIdx() {
			    return (fieldInfo & 0b1111111111111111111100000000000) >> 11;
			}
			
			int getNameStringLength() {
			    return (fieldInfo & 0b11111111100) >> 2;
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
			sb.append("Meta2_Unknown: 0x" + Integer.toHexString(meta2.entries[field.meta2EntryIdx].fieldInfo) + " ");
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
