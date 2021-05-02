package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import de.robojumper.ddsavereader.file.DsonTypes.FieldType;
import de.robojumper.ddsavereader.file.DsonFile.Meta2Block.Meta2BlockEntry;

public class DsonFile {

    public enum UnhashBehavior {
        NONE, // Don't unhash, works in all cases
        UNHASH, // Simple unhash, useful for simply looking at the files
        POUNDUNHASH, // Unhash as ###string, useful combination: Reasonable safety against accidental
                        // collisions, still somewhat readable
    };

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static byte[] MAGICNR_HEADER = { 0x01, (byte) 0xB1, 0x00, 0x00 };

    HeaderBlock header;
    Meta1Block meta1;
    Meta2Block meta2;
    // The first field that is being deserialized is always base_root
    List<DsonField> rootFields;

    UnhashBehavior autoUnhashNames;

    // Embed files are strings that have the last null-terminating character
    // included in the data size
    public DsonFile(byte[] File, UnhashBehavior behavior) throws ParseException {
        this.autoUnhashNames = behavior;
        ByteBuffer buffer = ByteBuffer.wrap(File);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Read Header
        header = new HeaderBlock();
        byte[] fileMagicNumber = new byte[4];
        buffer.get(fileMagicNumber);
        if (!Arrays.equals(fileMagicNumber, header.MagicNumber)) {
            throw new ParseException("Not a Dson File", 0);
        }
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
        // technically the header could be longer, but we don't know what data would be
        // there
        // so just skip to the Meta1 block
        {
            if (buffer.position() != header.meta1Offset) {
                throw new ParseException("Header doesn't end at start of Meta1 Block", buffer.position());
            }
            byte[] Meta1Data = new byte[header.meta2Offset - header.meta1Offset];
            buffer.get(Meta1Data);
            meta1 = new Meta1Block(Meta1Data);
            if (header.numMeta1Entries != meta1.entries.length) {
                throw new ParseException("Wrong number of Meta1 Entries", buffer.position());
            }
        }
        {
            // the buffer really should be at startOfMeta2 now
            if (buffer.position() != header.meta2Offset) {
                throw new ParseException("Meta1 Block doesn't end at start of Meta2 Block", buffer.position());
            }
            byte[] Meta2Data = new byte[header.dataOffset - header.meta2Offset];
            buffer.get(Meta2Data);
            meta2 = new Meta2Block(Meta2Data);
            if (header.numMeta2Entries != meta2.entries.length) {
                throw new ParseException("Wrong number of Meta2 Entries", buffer.position());
            }
        }
        {
            byte[] Data = new byte[header.dataLength];
            buffer.get(Data);
            if (buffer.remaining() != 0) {
                throw new ParseException("Data not completely consumed", buffer.position());
            }
            // parse the objects
            Stack<DsonField> fieldStack = new Stack<DsonField>();
            // For parentIndex
            Stack<Integer> parentIdxStack = new Stack<Integer>();
            // base_root starts at -1
            int runningObjIdx = -1;
            parentIdxStack.push(Integer.valueOf(runningObjIdx));
            rootFields = new ArrayList<DsonField>();
            // Is this the correct way to do it?
            // WARNING: Apparently, META2 is not necessarily ordered the same way as DATA
            // This may have serious implications on Field Hierarchy.
            // It seems to work, in case it breaks, this is what you're looking for
            for (int i = 0; i < meta2.entries.length; i++) {
                Meta2BlockEntry meta2Entry = meta2.entries[i];
                DsonField field = new DsonField();
                int off = meta2Entry.offset;
                field.name = readName(Data, off, meta2Entry.getNameStringLength() - 1);
                if (DsonTypes.stringHash(field.name) != meta2Entry.nameHash) {
                    throw new ParseException(String.format("%d: Wrong name hash: Name %s, expected %d, is %d", off,
                            field.name, meta2Entry.nameHash, DsonTypes.stringHash(field.name)), off);
                }
                if (meta2Entry.isObject()) {
                    field.meta1EntryIdx = meta2Entry.getMeta1BlockEntryIdx();
                }
                field.meta2EntryIdx = i;
                // Must rely on header due to encoding
                off += meta2Entry.getNameStringLength();
                field.dataStartInFile = off;

                int dataLen;
                // Meta2.Entries are not sorted that way! Broke for embedded unit files
                /*
                 * if (i < Meta2.Entries.length - 1) {
                 * 
                 * dataLen = Meta2.Entries[i+1].offset - off; } else { dataLen = Data.length + 1
                 * - off; }
                 */
                int nextOff = meta2.findSmallestOffsetLargerThan(meta2.entries[i].offset);
                if (nextOff > 0) {
                    dataLen = nextOff - off;
                } else {
                    dataLen = Data.length - off;
                }
                field.rawData = Arrays.copyOfRange(Data, off, off + dataLen);
                if (meta2Entry.isObject()) {
                    // we are an object type
                    field.type = FieldType.TYPE_OBJECT;
                    field.setNumChildren(meta1.entries[meta2Entry.getMeta1BlockEntryIdx()].numDirectChildren);
                    if (meta1.entries[meta2Entry.getMeta1BlockEntryIdx()].parentIndex != parentIdxStack.peek()
                            .intValue()) {
                        throw new ParseException("Parent object not most recently parsed object", off);
                    }
                    runningObjIdx++;
                }
                // Add the field
                // If our stack is empty, the field needs to be of type object!
                // (At least I haven't seen it any other way, since all files began with
                // base_root
                if (fieldStack.isEmpty()) {
                    if (field.type != FieldType.TYPE_OBJECT) {
                        throw new ParseException("No top level object", off);
                    }
                    rootFields.add(field);
                } else {
                    // We have a stack element, add Field as a child
                    if (!fieldStack.peek().addChild(field)) {
                        throw new ParseException("Object didn't specify enough child fields", buffer.position());
                    }
                }
                // now guess the type that it knows about its parents
                if (field.type != FieldType.TYPE_OBJECT) {
                    try {
                        if (!field.guessType(behavior)) {
                            throw new ParseException(String.format("%d: Couldn't parse field %s", off, field.name),
                                    off);
                        }
                    } catch (Exception e) {
                        ParseException ex = new ParseException(
                                String.format("%d: Couldn't parse field %s", off, field.name), off);
                        ex.initCause(e);
                        throw ex;
                    }
                }

                // If we have an object, push it to the stack
                if (field.type == FieldType.TYPE_OBJECT) {
                    fieldStack.push(field);
                    parentIdxStack.push(Integer.valueOf(runningObjIdx));
                }

                // Then check if the object on top of the stack has all its children. If so, pop
                // it
                // In case an object was the last child of an object, we do this iteratively
                while (!fieldStack.isEmpty() && fieldStack.peek().type == FieldType.TYPE_OBJECT
                        && fieldStack.peek().hasAllChilds()) {
                    fieldStack.pop();
                    parentIdxStack.pop();
                }
            }
            // we really should not have any pending fields at this point
            if (!fieldStack.isEmpty()) {
                throw new ParseException("Fields without all children fields encountered", buffer.position());
            }
            if (runningObjIdx + 1 != header.numMeta1Entries) {
                throw new ParseException("Wrong number of objects", buffer.position());
            }
        }
    }

    static String readName(byte[] data, int start, int len) throws ParseException {
        // Field names can be UTF-8
        byte[] str = Arrays.copyOfRange(data, start, start + len);
        String name = new String(str, StandardCharsets.UTF_8);
        if (!Arrays.equals(name.getBytes(StandardCharsets.UTF_8), str) || data[start + len] != 0) {
            throw new ParseException(
                    String.format("%d: Wrong name length: Name %s, expected %d but has null bytes in wrong place",
                            start, name, len),
                    start);
        }
        return name;
    }

    static class HeaderBlock {
        byte[] MagicNumber = MAGICNR_HEADER;
        byte[] epsilon = { 0x00, 0x00, 0x00, 0x00 };
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

        Meta1Block(byte[] data) throws ParseException {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (data.length % 0x10 != 0) {
                throw new ParseException("Meta1 has wrong number of bytes", buffer.position());
            }
            // The Meta1 block should always have a size that is a multiple of 0x10
            entries = new Meta1BlockEntry[data.length / 0x10];
            for (int i = 0; buffer.remaining() != 0; i++) {
                entries[i] = new Meta1BlockEntry();
                entries[i].parentIndex = buffer.getInt();
                entries[i].meta2EntryIdx = buffer.getInt();
                entries[i].numDirectChildren = buffer.getInt();
                entries[i].numAllChildren = buffer.getInt();
            }
        }

        // data structure encapsulating a single entry in the Meta1Block
        static class Meta1BlockEntry {
            // Index of the parent object into Meta1 entries array
            int parentIndex;
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

        Meta2Block(byte[] data) throws ParseException {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (data.length % 0x0C != 0) {
                throw new ParseException("Meta2 has wrong number of bytes", buffer.position());
            }
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
                if (entries[i].offset > off && (bestIdx == -1 || entries[i].offset < bestOffset)) {
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
            // - 1 if object, 0 if not
            // - Unknown (Always 0?)
            // --- ---- -- Name string length, HOW LONG IS IT?
            // --- ---- ---- ---- ---- - Object index, HOW LONG IS IT?
            // - Memory junk?
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

    // If bDebug is true, note that this is not valid JSON, but should be after
    // removing all comments
    // Comments contain debug info that might come in handy. This debug info is just
    // unknown hex fields
    public String getJSonString(int indent, boolean debug) {
        StringBuilder sb = new StringBuilder();
        if (debug) {
            // sb.append("// HEADER: ");
        }
        sb.append("{\n");
        indent++;

        for (int i = 0; i < rootFields.size(); i++) {
            writeField(sb, rootFields.get(i), indent, debug);
            if (i != rootFields.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        indent--;
        sb.append(indt(indent) + "}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return getJSonString(0, false);
    }

    // Whether this File has duplicate fields that will get lost when converting to
    // string
    // This doesn't seem to be causing any issues, but is important for test
    // coverage because
    // files with duplicate fields will re-encode to a different size.
    public boolean hasDuplicateFields() {
        Set<String> fields = new HashSet<>();
        for (int i = 0; i < rootFields.size(); i++) {
            if (!fields.add(rootFields.get(i).name)) {
                return true;
            }
            if (hasDuplicateFields(rootFields.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDuplicateFields(DsonField field) {
        Set<String> fields = new HashSet<>();
        if (field.type == FieldType.TYPE_OBJECT) {
            for (int i = 0; i < field.children.length; i++) {
                if (!fields.add(field.children[i].name)) {
                    return true;
                }
                if (hasDuplicateFields(field.children[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeField(StringBuilder sb, DsonField field, int indent, boolean debug) {

        if (debug) {
            sb.append(indt(indent) + "// INFO ");
            // every field has a Meta2Index
            sb.append("Meta2_Unknown: 0x" + Integer.toHexString(meta2.entries[field.meta2EntryIdx].fieldInfo) + " ");
            sb.append(field.getExtraComments());
            sb.append("\n");
        }

        sb.append(indt(indent) + "\"" + field.name + "\" : ");
        if (field.type == FieldType.TYPE_OBJECT) {
            writeObject(sb, field, indent, debug);
        } else if (field.type == FieldType.TYPE_FILE) {
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
            Set<String> emittedFields = new HashSet<>();
            for (int i = 0; i < field.children.length; i++) {
                // DD has a quirk in a few files where fields wind up twice (serialized twice?)
                // This is not valid JSON and removing it doesn't cause any issues, so let's
                // just remove it here
                if (!emittedFields.contains(field.children[i].name)) {
                    writeField(sb, field.children[i], indent, debug);
                    emittedFields.add(field.children[i].name);
                    sb.append(',');
                    sb.append('\n');
                }
            }
            sb.deleteCharAt(sb.length() - 2);
            indent--;
            sb.append(indt(indent) + "}");
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
            // int v = bytes[i] & 0xFF;
            int v = bytes[bytes.length - 1 - i] & 0xFF;
            hexChars[i * 2] = hexArray[v >>> 4];
            hexChars[i * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String indt(int num) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < num; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }
}
