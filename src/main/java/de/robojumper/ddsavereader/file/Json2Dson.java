package de.robojumper.ddsavereader.file;

import de.robojumper.ddsavereader.file.DsonFile.*;
import de.robojumper.ddsavereader.file.DsonFile.Meta1Block.Meta1BlockEntry;
import de.robojumper.ddsavereader.file.DsonFile.Meta2Block.Meta2BlockEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map.Entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Json2Dson {

    HeaderBlock header;
    ByteArrayOutputStream data;
    ArrayList<Meta1BlockEntry> meta1Entries;
    Deque<Integer> hierarchyHintStack;
    Deque<String> nameStack;
    ArrayList<Meta2BlockEntry> meta2Entries;

    public static void main(String[] args) {
        String arg;
        int i = 0;
        String outfile = "", infile = "";

        while (i < args.length && args[i].startsWith("-")) {
            arg = args[i++];

            if (arg.equals("-o") || arg.equals("--output")) {
                if (i < args.length) {
                    outfile = args[i++];
                } else {
                    System.err.println("--output requires a filename");
                }
            }
        }

        if (i == args.length - 1) {
            infile = args[i++];
        } else {
            System.err.println(
                    "Usage: java -cp DDSaveReader.jar de.robojumper.ddsavereader.file.Json2Dson [--output, -o outfile] filename");
            System.exit(1);
        }

        byte[] OutResult = null;
        try {
            byte[] FileData = Files.readAllBytes(Paths.get(infile));
            Json2Dson d = new Json2Dson(FileData);
            OutResult = d.bytes();
        } catch (IOException e) {
            System.err.println("Could not read " + infile);
            System.err.println(e.getMessage());
            System.exit(1);
        }

        if (!outfile.equals("")) {
            try {
                Files.write(Paths.get(outfile), OutResult);
            } catch (IOException e) {
                System.err.println("Could not read " + outfile);
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
    }

    public Json2Dson(String jsonData) throws IOException {
        this(new JsonParser().parse(jsonData).getAsJsonObject());
    }

    public Json2Dson(byte[] data) throws IOException {
        this(new JsonParser().parse(new String(data)).getAsJsonObject());
    }

    public Json2Dson(JsonObject o) throws IOException {
        header = new HeaderBlock();
        data = new ByteArrayOutputStream();

        header.headerLength = 0x40;
        header.meta1Offset = 0x40;

        meta1Entries = new ArrayList<>();
        meta2Entries = new ArrayList<>();
        hierarchyHintStack = new ArrayDeque<>();
        nameStack = new ArrayDeque<>();
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

    private void writeField(Entry<String, JsonElement> field) throws IOException {
        String name = field.getKey();
        JsonElement elem = field.getValue();
        Meta2BlockEntry e2 = new Meta2BlockEntry();
        e2.nameHash = DsonFile.stringHash(name);
        e2.fieldInfo = ((name.length() + 1) & 0b111111111) << 2;
        meta2Entries.add(e2);

        e2.offset = data.size();
        data.write(name.getBytes(StandardCharsets.UTF_8));
        data.write(0);

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
                Json2Dson d = new Json2Dson(elem.getAsJsonObject());
                align();
                byte[] embedData = d.bytes();
                data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(embedData.length).array());
                data.write(embedData);
            }
        } else {
            // Now for the tricky part: Not an object, now we need to determine the type
            // Same as in DsonField, we first check the hardcoded types
            if (nameInArray(name, DsonTypes.FLOATARRAY_FIELD_NAMES)) {
                align();
                JsonArray arr = elem.getAsJsonArray();
                for (JsonElement s : arr) {
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(s.getAsFloat()).array());
                }
            } else if (nameInArray(name, DsonTypes.INTVECTOR_FIELD_NAMES)) {
                align();
                JsonArray arr = elem.getAsJsonArray();
                data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arr.size()).array());
                for (JsonElement s : arr) {
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(s.getAsInt()).array());
                }
            } else if (nameInArray(name, DsonTypes.STRINGVECTOR_FIELD_NAMES)) {
                align();
                JsonArray arr = elem.getAsJsonArray();
                data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arr.size()).array());
                for (JsonElement s : arr) {
                    data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(s.getAsString().length() + 1).array());
                    data.write(s.getAsString().getBytes(StandardCharsets.UTF_8));
                    data.write(0);
                }
            } else if (nameInArray(name, DsonTypes.FLOAT_FIELD_NAMES)) {
                align();
                data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(elem.getAsFloat()).array());
            } else if (nameInArray(name, DsonTypes.CHAR_FIELD_NAMES)) {
                data.write(elem.getAsString().getBytes(StandardCharsets.UTF_8)[0]);
            } else if (nameInArray(name, DsonTypes.FLOAT_FIELD_NAMES)) {
                align();
                data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(elem.getAsFloat()).array());
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
            }
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

    private boolean nameInArray(String name, String[][] arr) {
        String checkString;
        boolean match;
        Object[] names = nameStack.toArray();
        for (int i = 0; i < arr.length; i++) {
            match = true;
            checkString = name;
            for (int j = arr[i].length - 1; j >= 0; j--) {
                if (checkString == null || !(arr[i][j].equals("*") || arr[i][j].equals(checkString))) {
                    match = false;
                    break;
                }
                int stackIndex = arr[i].length - 1 - j;
                if (stackIndex < names.length) {
                    checkString = (String) names[stackIndex];
                } else {
                    checkString = null;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
