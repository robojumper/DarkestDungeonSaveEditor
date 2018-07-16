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
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DsonWriter {

    HeaderBlock header;
    ByteArrayOutputStream data;
    ArrayList<Meta1BlockEntry> meta1Entries;
    // Stack over Deque for random access .get
    Stack<Integer> hierarchyHintStack;
    Stack<String> nameStack;
    ArrayList<Meta2BlockEntry> meta2Entries;

    public DsonWriter(String jsonData) throws IOException, ParseException {
        this(new JsonParser().parse(jsonData).getAsJsonObject());
    }

    public DsonWriter(byte[] data) throws IOException, ParseException {
        this(new JsonParser().parse(new String(data)).getAsJsonObject());
    }

    public DsonWriter(JsonObject o) throws IOException, ParseException {
        header = new HeaderBlock();
        data = new ByteArrayOutputStream();

        header.headerLength = 0x40;
        header.meta1Offset = 0x40;

        meta1Entries = new ArrayList<>();
        meta2Entries = new ArrayList<>();
        hierarchyHintStack = new Stack<>();
        nameStack = new Stack<>();
        hierarchyHintStack.push(-1);

        for (Entry<String, JsonElement> e : o.entrySet()) {
            writeField(e);
        }

        header.numMeta1Entries = meta1Entries.size();
        header.meta1Size = header.numMeta1Entries << 4;
        header.numMeta2Entries = meta2Entries.size();
        header.meta2Offset = 0x40 + meta1Entries.size() * 0x10;
        header.dataOffset = 0x40 + meta1Entries.size() * 0x10 + meta2Entries.size() * 0x0C;
        header.dataLength = data.size();
        hierarchyHintStack.pop();
    }

    // TODO: Switch to manual parsing in order to give better error locations
    private void writeField(Entry<String, JsonElement> field) throws IOException, ParseException {
        String name = field.getKey();
        JsonElement elem = field.getValue();
        Meta2BlockEntry e2 = new Meta2BlockEntry();
        e2.nameHash = DsonFile.stringHash(name);
        e2.fieldInfo = ((name.length() + 1) & 0b111111111) << 2;
        meta2Entries.add(e2);

        e2.offset = data.size();
        data.write(name.getBytes(StandardCharsets.UTF_8));
        data.write(0);
        
        Function<Integer, String> nameMapper = (i) -> i == 0 ? name : (i <= nameStack.size() ? nameStack.get(nameStack.size() - i) : null);
        try {
            if (elem.isJsonObject()) {
                // Objects with the name raw_data or static_save are embedded files
                if (!name.equals("raw_data") && !name.equals("static_save")) {
                    Meta1BlockEntry e1 = new Meta1BlockEntry();
                    e1.meta2EntryIdx = meta2Entries.size() - 1;
                    e2.fieldInfo |= 0b1 | ((meta1Entries.size() & 0b11111111111111111111) << 11);
                    e1.hierarchyHint = hierarchyHintStack.peek();
                    e1.numDirectChildren = elem.getAsJsonObject().entrySet().size();
                    meta1Entries.add(e1);
                    int prevNumChilds = meta2Entries.size();
                    hierarchyHintStack.push(meta1Entries.size() - 1);
                    nameStack.push(name);
                    for (Entry<String, JsonElement> childElem : elem.getAsJsonObject().entrySet()) {
                        writeField(childElem);
                    }
                    nameStack.pop();
                    hierarchyHintStack.pop();
                    e1.numAllChildren = meta2Entries.size() - prevNumChilds;
                } else {
                    // Write an actual embedded file as a string
                    DsonWriter d = new DsonWriter(elem.getAsJsonObject());
                    align();
                    byte[] embedData = d.bytes();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(embedData.length).array());
                    data.write(embedData);
                }
            } else {
                // Now for the tricky part: Not an object, now we need to determine the type
                // Same as in DsonField, we first check the hardcoded types
                if (DsonTypes.isA(FieldType.TYPE_FLOATARRAY, nameMapper)) {
                    align();
                    JsonArray arr = elem.getAsJsonArray();
                    for (JsonElement s : arr) {
                        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(s.getAsFloat()).array());
                    }
                } else if (DsonTypes.isA(FieldType.TYPE_INTVECTOR, nameMapper)) {
                    align();
                    JsonArray arr = elem.getAsJsonArray();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arr.size()).array());
                    for (JsonElement s : arr) {
                        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(s.getAsInt()).array());
                    }
                } else if (DsonTypes.isA(FieldType.TYPE_STRINGVECTOR, nameMapper)) {
                    align();
                    JsonArray arr = elem.getAsJsonArray();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arr.size()).array());
                    for (JsonElement s : arr) {
                        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(s.getAsString().length() + 1).array());
                        data.write(s.getAsString().getBytes(StandardCharsets.UTF_8));
                        data.write(0);
                    }
                } else if (DsonTypes.isA(FieldType.TYPE_FLOAT, nameMapper)) {
                    align();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(elem.getAsFloat()).array());
                } else if (DsonTypes.isA(FieldType.TYPE_CHAR, nameMapper)) {
                    data.write(elem.getAsString().getBytes(StandardCharsets.UTF_8)[0]);
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    align();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(elem.getAsInt()).array());
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    align();
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(elem.getAsString().length() + 1)
                            .array());
                    data.write(elem.getAsString().getBytes(StandardCharsets.UTF_8));
                    data.write(0);
                } else if (elem.isJsonArray() && elem.getAsJsonArray().size() == 2) {
                    align();
                    JsonArray arr = elem.getAsJsonArray();
                    for (JsonElement s : arr) {
                        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(s.getAsBoolean() ? 1 : 0)
                                .array());
                    }
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isBoolean()) {
                    data.write(elem.getAsBoolean() ? 0x01 : 0x00);
                } else {
                    throw new ParseException("Field " + name + " cannot be written", 0);
                }
            }
        } catch (ClassCastException | IllegalStateException e) {
            throw new ParseException("Error writing " + field, 0);
        }

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
            buffer.putInt(e1.hierarchyHint);
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
