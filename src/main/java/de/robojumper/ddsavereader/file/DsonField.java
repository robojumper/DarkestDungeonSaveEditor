package de.robojumper.ddsavereader.file;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Iterator;

import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;
import de.robojumper.ddsavereader.file.DsonTypes.FieldType;

// Dson for Darkest Json
public class DsonField {

    static final String STR_TRUE = "true";
    static final String STR_FALSE = "false";

    public FieldType type = FieldType.TYPE_UNKNOWN;
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

    // If external code has not determined this field to be TYPE_Object, guess the
    // type
    public boolean guessType(UnhashBehavior behavior) throws ParseException {
        if (parseHardcodedType(behavior)) {
            return true;
        } else if (rawData.length == 1) {
            if (rawData[0] >= 0x20 && rawData[0] <= 0x7E) {
                type = FieldType.TYPE_CHAR;
                dataString = "\"" + Character.toString((char) rawData[0]) + "\"";
            } else {
                type = FieldType.TYPE_BOOL;
                dataString = rawData[0] == 0x00 ? STR_FALSE : STR_TRUE;
            }
        } else if (alignedSize() == 8 && (rawData[alignmentSkip() + 0] == 0x00 || rawData[alignmentSkip() + 0] == 0x01)
                && (rawData[alignmentSkip() + 4] == 0x00 || rawData[alignmentSkip() + 4] == 0x01)) {
            type = FieldType.TYPE_TWOBOOL;
            dataString = "[" + (rawData[alignmentSkip() + 0] == 0x00 ? STR_FALSE : STR_TRUE) + ", "
                    + (rawData[alignmentSkip() + 4] == 0x00 ? STR_FALSE : STR_TRUE) + "]";
        } else if (alignedSize() == 4) {
            type = FieldType.TYPE_INT;
            byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
            int tempInt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
            dataString = Integer.toString(tempInt);
            if (behavior == UnhashBehavior.UNHASH || behavior == UnhashBehavior.POUNDUNHASH) {
                String unHashed = DsonTypes.NAME_TABLE.get(tempInt);
                if (unHashed != null) {
                    hashedValue = dataString;
                    dataString = (behavior == UnhashBehavior.POUNDUNHASH) ? ("\"###" + unHashed + "\"")
                            : ("\"" + unHashed + "\"");
                }
            }
        } else if (parseString()) {
            // Some strings are actually embedded files
            if (dataString.length() >= 6) {
                byte[] unquoteData = Arrays.copyOfRange(rawData, alignmentSkip() + 4, rawData.length);
                byte[] tempHeader = Arrays.copyOfRange(unquoteData, 0, 4);
                if (Arrays.equals(tempHeader, DsonFile.MAGICNR_HEADER)) {
                    type = FieldType.TYPE_FILE;
                    embeddedFile = new DsonFile(unquoteData, behavior);
                    dataString = "MUST REBUILD MANUALLY WITH CORRECT INDENTATION";
                    return true;
                }
            }
            dataString = dataString.replaceAll("\n", "\\\\n");
        } else {
            return false;
        }

        return true;
    }

    private boolean parseHardcodedType(UnhashBehavior behavior) {
        return parseFloatArray() || parseIntVector(behavior) || parseStringVector() || parseFloat() || parseTwoInt();
    }

    private boolean parseTwoInt() {
        if (DsonTypes.isA(FieldType.TYPE_TWOINT, this::nameIterator)) {
            if (alignedSize() == 8) {
                type = FieldType.TYPE_TWOINT;
                byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 8);
                ByteBuffer buf = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN);
                dataString = "[" + Integer.toString(buf.getInt()) + ", " + Integer.toString(buf.getInt()) + "]";
                return true;
            }
        }
        return false;
    }

    private boolean parseFloat() {
        if (DsonTypes.isA(FieldType.TYPE_FLOAT, this::nameIterator)) {
            if (alignedSize() == 4) {
                type = FieldType.TYPE_FLOAT;
                byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
                float tempFlt = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                dataString = Float.toString(tempFlt);
                return true;
            }
        }
        return false;
    }

    private boolean parseStringVector() {
        if (DsonTypes.isA(FieldType.TYPE_STRINGVECTOR, this::nameIterator)) {
            type = FieldType.TYPE_STRINGVECTOR;
            byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
            int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
            // read the rest
            byte[] strings = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + alignedSize());
            ByteBuffer bf = ByteBuffer.wrap(strings).order(ByteOrder.LITTLE_ENDIAN);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < arrLen; i++) {
                int strlen = bf.getInt();
                byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4 + bf.position(),
                        alignmentSkip() + 4 + bf.position() + strlen - 1);
                sb.append("\"" + new String(tempArr2, StandardCharsets.UTF_8).replaceAll("\n", "\\\\n") + "\"");
                bf.position(bf.position() + strlen);
                if (i < arrLen - 1) {
                    // Skip for alignment, but only if we have things following
                    bf.position(bf.position() + ((4 - (bf.position() % 4)) % 4));
                    sb.append(", ");
                }
            }
            sb.append("]");
            dataString = sb.toString();
            return true;
        }
        return false;
    }

    private boolean parseIntVector(UnhashBehavior behavior) {
        if (DsonTypes.isA(FieldType.TYPE_INTVECTOR, this::nameIterator)) {
            byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
            int arrLen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (alignedSize() == (arrLen + 1) * 4) {
                type = FieldType.TYPE_INTVECTOR;
                byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + (arrLen + 1) * 4);

                ByteBuffer buffer = ByteBuffer.wrap(tempArr2).order(ByteOrder.LITTLE_ENDIAN);
                StringBuilder sb = new StringBuilder();
                StringBuilder hsb = new StringBuilder();

                sb.append("[");
                hsb.append("[");

                boolean foundHashed = false;

                for (int i = 0; i < arrLen; i++) {
                    int tempInt = buffer.getInt();
                    String unHashed;
                    if ((behavior == UnhashBehavior.UNHASH || behavior == UnhashBehavior.POUNDUNHASH)
                            && (unHashed = DsonTypes.NAME_TABLE.get(tempInt)) != null) {
                        unHashed = (behavior == UnhashBehavior.POUNDUNHASH) ? ("\"###" + unHashed + "\"")
                                : ("\"" + unHashed + "\"");
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

    private boolean parseFloatArray() {
        if (DsonTypes.isA(FieldType.TYPE_FLOATARRAY, this::nameIterator)) {
            type = FieldType.TYPE_FLOATARRAY;
            byte[] floats = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + alignedSize());
            ByteBuffer bf = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN);
            StringBuilder sb = new StringBuilder();
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

    private boolean parseString() {
        // A string has a 4-byte int for the length, followed by a null-term'd string.
        // So it's at least 5 bytes long
        if (alignedSize() >= 5) {
            byte[] tempArr = Arrays.copyOfRange(rawData, alignmentSkip(), alignmentSkip() + 4);
            int strlen = ByteBuffer.wrap(tempArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
            // We can't read a null-term string because some strings actually include the
            // null character (like embedded files)
            // String str = DsonFile.ReadNullTermString(RawData, AlignmentSkip() + 4);
            if (alignedSize() == 4 + strlen) {
                type = FieldType.TYPE_STRING;
                byte[] tempArr2 = Arrays.copyOfRange(rawData, alignmentSkip() + 4, alignmentSkip() + 4 + strlen - 1);
                dataString = "\"" + new String(tempArr2, StandardCharsets.UTF_8) + "\"";
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
    public boolean addChild(DsonField Field) {
        for (int i = 0; i < children.length; i++) {
            if (children[i] == null) {
                children[i] = Field;
                Field.parent = this;
                return true;
            }
        }
        return false;
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

        if (type == FieldType.TYPE_UNKNOWN) {
            sb.append(", Raw Data: ");
            sb.append(DsonFile.LEBytesToHexStr(rawData));
        }
        return sb.toString();
    }

    private Iterator<String> nameIterator() {
        return new Iterator<String>() {
            private DsonField field = DsonField.this;

            @Override
            public boolean hasNext() {
                return field != null;
            }

            @Override
            public String next() {
                String f = field.name;
                field = field.parent;
                return f;
            }
        };
    }
}
