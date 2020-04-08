package de.robojumper.ddsavereader.file;

import de.robojumper.ddsavereader.file.DsonFile.*;
import de.robojumper.ddsavereader.file.DsonFile.Meta1Block.Meta1BlockEntry;
import de.robojumper.ddsavereader.file.DsonFile.Meta2Block.Meta2BlockEntry;
import de.robojumper.ddsavereader.file.DsonTypes.FieldType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

public class DsonWriter {

    HeaderBlock header;
    ByteArrayOutputStream data;
    ArrayList<Meta1BlockEntry> meta1Entries;
    Deque<Integer> parentIdxStack;
    Deque<String> nameStack;
    ArrayList<Meta2BlockEntry> meta2Entries;

    public DsonWriter(String jsonData) throws IOException, ParseException, InterruptedException {
        this(new JsonFactory().createParser(jsonData));
    }

    public DsonWriter(byte[] data) throws IOException, ParseException, InterruptedException {
        this(new JsonFactory().createParser(data));
    }

    private DsonWriter(JsonParser reader) throws IOException, ParseException, InterruptedException {
        header = new HeaderBlock();
        data = new ByteArrayOutputStream();

        header.headerLength = 0x40;
        header.meta1Offset = 0x40;

        meta1Entries = new ArrayList<>();
        meta2Entries = new ArrayList<>();
        parentIdxStack = new ArrayDeque<>();
        nameStack = new ArrayDeque<>();
        parentIdxStack.push(-1);

        try {
            // If we already have a token, we were invoked for an inner object.
            // getCurrentToken() returns null if we start fresh, so we enter the right
            // condition
            if (reader.getCurrentToken() != JsonToken.START_OBJECT && reader.nextToken() != JsonToken.START_OBJECT) {
                throw new ParseException("Expected {", (int) reader.getCurrentLocation().getCharOffset());
            }

            while (true) {
                JsonToken t = reader.nextToken();
                if (t != JsonToken.FIELD_NAME) {
                    break;
                }
                writeField(reader.getCurrentName(), reader);
            }

            if (reader.getCurrentToken() != JsonToken.END_OBJECT) {
                throw new ParseException("Expected }", (int) reader.getCurrentLocation().getCharOffset());
            }
        } catch (JsonParseException e) {
            throw new ParseException(e.getMessage(), (int) reader.getCurrentLocation().getCharOffset());
        }

        header.numMeta1Entries = meta1Entries.size();
        header.meta1Size = header.numMeta1Entries << 4;
        header.numMeta2Entries = meta2Entries.size();
        header.meta2Offset = 0x40 + meta1Entries.size() * 0x10;
        header.dataOffset = 0x40 + meta1Entries.size() * 0x10 + meta2Entries.size() * 0x0C;
        header.dataLength = data.size();
        parentIdxStack.pop();
    }

    private void writeField(String name, JsonParser reader) throws IOException, ParseException, InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        Meta2BlockEntry e2 = new Meta2BlockEntry();
        e2.nameHash = DsonTypes.stringHash(name);
        byte[] nameb = name.getBytes(StandardCharsets.UTF_8);
        e2.fieldInfo = ((nameb.length + 1) & 0b111111111) << 2;
        meta2Entries.add(e2);

        e2.offset = data.size();
        data.write(nameb);
        data.write(0);

        try {
            reader.nextToken();
            if (reader.getCurrentToken() == JsonToken.START_OBJECT) {
                if (!name.equals("raw_data") && !name.equals("static_save")) {
                    Meta1BlockEntry e1 = new Meta1BlockEntry();
                    e1.meta2EntryIdx = meta2Entries.size() - 1;
                    e2.fieldInfo |= 0b1 | ((meta1Entries.size() & 0b11111111111111111111) << 11);
                    e1.parentIndex = parentIdxStack.peek();
                    meta1Entries.add(e1);
                    int prevNumChilds = meta2Entries.size();
                    parentIdxStack.push(meta1Entries.size() - 1);
                    nameStack.push(name);
                    int numDirectChildren = 0;
                    while (true) {
                        JsonToken childToken = reader.nextToken();
                        if (childToken != JsonToken.FIELD_NAME) {
                            break;
                        }
                        writeField(reader.getCurrentName(), reader);
                        numDirectChildren += 1;
                    }

                    if (reader.getCurrentToken() != JsonToken.END_OBJECT) {
                        throw new ParseException("Expected }", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    e1.numDirectChildren = numDirectChildren;

                    nameStack.pop();
                    parentIdxStack.pop();
                    e1.numAllChildren = meta2Entries.size() - prevNumChilds;
                } else {
                    // Write an actual embedded file as a string
                    DsonWriter d = new DsonWriter(reader);
                    align();
                    byte[] embedData = d.bytes();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(embedData.length).array());
                    data.write(embedData);
                }
            } else {
                // Now for the tricky part: Not an object, now we need to determine the type
                // Same as in DsonField, we first check the hardcoded types
                // In order to easily use the nameStack's iterator, we temporarily push the
                // field name
                nameStack.push(name);
                if (DsonTypes.isA(FieldType.TYPE_FLOATARRAY, nameStack::iterator)) {
                    align();
                    if (reader.getCurrentToken() != JsonToken.START_ARRAY) {
                        throw new ParseException("Expected [", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    while (reader.nextToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                        data.write(floatBytes(reader.getFloatValue()));
                    }
                    if (reader.getCurrentToken() != JsonToken.END_ARRAY) {
                        throw new ParseException("Expected number or ]",
                                (int) reader.getCurrentLocation().getCharOffset());
                    }
                } else if (DsonTypes.isA(FieldType.TYPE_INTVECTOR, nameStack::iterator)) {
                    align();
                    if (reader.getCurrentToken() != JsonToken.START_ARRAY) {
                        throw new ParseException("Expected [", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    ByteArrayOutputStream vecData = new ByteArrayOutputStream();
                    int numElem = 0;
                    while (reader.nextToken() == JsonToken.VALUE_NUMBER_INT
                            || reader.getCurrentToken() == JsonToken.VALUE_STRING) {
                        if (reader.getCurrentToken() == JsonToken.VALUE_STRING) {
                            if (!reader.getValueAsString().startsWith("###")) {
                                throw new ParseException("Expected hashed string (###)",
                                        (int) reader.getCurrentLocation().getCharOffset());
                            }
                            vecData.write(stringBytes(reader.getValueAsString()));
                        } else {
                            vecData.write(intBytes(reader.getIntValue()));
                        }
                        numElem += 1;
                    }
                    if (reader.getCurrentToken() != JsonToken.END_ARRAY) {
                        throw new ParseException("Expected integer, hashed string or ]",
                                (int) reader.getCurrentLocation().getCharOffset());
                    }
                    data.write(intBytes(numElem));
                    data.write(vecData.toByteArray());
                } else if (DsonTypes.isA(FieldType.TYPE_STRINGVECTOR, nameStack::iterator)) {
                    align();
                    if (reader.getCurrentToken() != JsonToken.START_ARRAY) {
                        throw new ParseException("Expected [", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    ByteArrayOutputStream vecData = new ByteArrayOutputStream();
                    int numElem = 0;
                    while (reader.nextToken() == JsonToken.VALUE_STRING) {
                        numElem += 1;
                        vecData.write(new byte[(4 - (vecData.size() % 4)) % 4]);
                        vecData.write(stringBytes(reader.getValueAsString()));
                    }
                    if (reader.getCurrentToken() != JsonToken.END_ARRAY) {
                        throw new ParseException("Expected string or ]",
                                (int) reader.getCurrentLocation().getCharOffset());
                    }
                    data.write(intBytes(numElem));
                    data.write(vecData.toByteArray());
                } else if (DsonTypes.isA(FieldType.TYPE_FLOAT, nameStack::iterator)) {
                    align();
                    if (reader.getCurrentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                        throw new ParseException("Expected number", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    data.write(floatBytes(reader.getFloatValue()));
                } else if (DsonTypes.isA(FieldType.TYPE_TWOINT, nameStack::iterator)) {
                    align();
                    if (reader.getCurrentToken() != JsonToken.START_ARRAY) {
                        throw new ParseException("Expected [", (int) reader.getCurrentLocation().getCharOffset());
                    }
                    for (int i = 0; i < 2; i++) {
                        if (reader.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                            throw new ParseException("Expected int", (int) reader.getCurrentLocation().getCharOffset());
                        }
                        data.write(intBytes(reader.getIntValue()));
                    }
                    if (reader.nextToken() != JsonToken.END_ARRAY) {
                        throw new ParseException("Expected ]", (int) reader.getCurrentLocation().getCharOffset());
                    }
                } else if (DsonTypes.isA(FieldType.TYPE_CHAR, nameStack::iterator)) {
                    if (reader.getCurrentToken() != JsonToken.VALUE_STRING) {
                        throw new ParseException(
                                name + ": Expected character, got " + reader.getCurrentToken().asString(),
                                (int) reader.getCurrentLocation().getCharOffset());
                    }
                    data.write(reader.getValueAsString().getBytes(StandardCharsets.UTF_8)[0]);
                } else if (reader.getCurrentToken() == JsonToken.VALUE_NUMBER_INT) {
                    align();
                    data.write(intBytes(reader.getIntValue()));
                } else if (reader.getCurrentToken() == JsonToken.VALUE_STRING) {
                    align();
                    data.write(stringBytes(reader.getValueAsString()));
                } else if (reader.getCurrentToken() == JsonToken.START_ARRAY) {
                    align();
                    for (int i = 0; i < 2; i++) {
                        if (reader.nextToken() == JsonToken.VALUE_TRUE
                                || reader.getCurrentToken() == JsonToken.VALUE_FALSE) {
                            data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(reader.getBooleanValue() ? 1 : 0).array());
                        } else {
                            throw new ParseException(
                                    "Field type not identified, only expecting\"true\" or \"false\" in arrays",
                                    (int) reader.getCurrentLocation().getCharOffset());
                        }
                    }
                    if (reader.nextToken() != JsonToken.END_ARRAY) {
                        throw new ParseException("Expected ]", (int) reader.getCurrentLocation().getCharOffset());
                    }
                } else if (reader.getCurrentToken() == JsonToken.VALUE_TRUE
                        || reader.getCurrentToken() == JsonToken.VALUE_FALSE) {
                    data.write(reader.getBooleanValue() ? 0x01 : 0x00);
                } else {
                    throw new ParseException("Field " + name + " not identified",
                            (int) reader.getCurrentLocation().getCharOffset());
                }
                nameStack.pop();
            }
        } catch (ClassCastException | IllegalStateException e) {
            throw new ParseException("Error writing " + name, (int) reader.getCurrentLocation().getCharOffset());
        }
    }

    private byte[] floatBytes(float f) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array();
    }

    private byte[] stringBytes(String s) {
        if (s.startsWith("###")) {
            int hash = DsonTypes.stringHash(s.substring(3));
            return intBytes(hash);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(5 + bytes.length).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length + 1)
                    .put(bytes).put((byte) 0).array();
        }
    }

    private byte[] intBytes(int i) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array();
    }

    public byte[] bytes() {
        ByteBuffer buffer = ByteBuffer
                .allocate(0x40 + meta1Entries.size() * 0x10 + meta2Entries.size() * 0x0C + data.size())
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header.MagicNumber);
        buffer.put(header.epsilon);
        buffer.putInt(header.headerLength);
        buffer.putInt(header.zeroes);
        buffer.putInt(header.meta1Size);
        buffer.putInt(header.numMeta1Entries);
        buffer.putInt(header.meta1Offset);
        buffer.putLong(header.zeroes2);
        buffer.putLong(header.zeroes3);
        buffer.putInt(header.numMeta2Entries);
        buffer.putInt(header.meta2Offset);
        buffer.putInt(header.zeroes4);
        buffer.putInt(header.dataLength);
        buffer.putInt(header.dataOffset);

        for (int i = 0; i < meta1Entries.size(); i++) {
            Meta1BlockEntry e1 = meta1Entries.get(i);
            buffer.putInt(e1.parentIndex);
            buffer.putInt(e1.meta2EntryIdx);
            buffer.putInt(e1.numDirectChildren);
            buffer.putInt(e1.numAllChildren);
        }

        for (int i = 0; i < meta2Entries.size(); i++) {
            Meta2BlockEntry e2 = meta2Entries.get(i);
            buffer.putInt(e2.nameHash);
            buffer.putInt(e2.offset);
            buffer.putInt(e2.fieldInfo);
        }
        buffer.put(data.toByteArray());
        return buffer.array();
    }

    private void align() throws IOException {
        data.write(new byte[(4 - (data.size() % 4)) % 4]);
    }
}
