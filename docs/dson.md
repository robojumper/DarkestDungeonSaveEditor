# Darkest Dungeon Save Format Documentation

This document describes the Darkest Dungeon Save Format in detail.

## Basics

Unless specified otherwise, all values are read in using little-endian order.  
Types correspond to the types in Java, where `int` is a four-byte 2-complement integer, `float` is a four-byte IEEE 754 float and `long` is an eight-byte 2-complement integer.  
Strings are encoded with UTF-8 and null-terminated.  

### String Hashes

In order to fit in predefined structs without extra memory allocation, Darkest Dungeon hashes most of its game data strings (like hero classes, upgrade trees, ...).  
A java implementation of the string hash function follows:

```java
public static int stringHash(String str) {
    int hash = 0;
    byte[] arr = str.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < arr.length; i++) {
        hash = hash * 53 + Byte.toUnsignedInt(arr[i]);
    }
    return hash;
}
```

## Header

Total size: 64 bytes.

**Size (bytes)**|**Type**|**Name**|**Description**|**Value**
-------|-------|-------|-------|-------
4|Raw|magicNr|Magic Nr|01 B1 00 00 
4|Raw|revision|Game Build|00 00 {uint16 LE bytes}
4|int|headerLength|Length of the Header block|64
4|int|zeroes|Unknown|0
4|int|meta1Size|Size of the Meta1 Block|numMeta1Entries << 4
4|int|numMeta1Entries|Number of entries in Meta1 = Number of objects in file| 
4|int|meta1Offset|Total offset of Meta1 Block|headerLength = 64
8|long|zeroes2|Unknown|0
8|long|zeroes3|Unknown|0
4|int|numMeta2Entries|Number of entries in Meta2 = number of fields in file| 
4|int|meta2Offset|Total offset of Meta2 Block|
4|int|zeroes4|Unknown|0 
4|int|dataLength|Length of the data block| 
4|int|dataOffset|Total offset of the data block| 

Note: The revision field contains two zero bytes and the least significant two bytes of the build found in svn_revision.txt, i.e. build `24149` is `00 00 55 5E`, and build `100000` would be `00 00 A0 86`.

## Meta1 Block

The Meta1 Block contains one entry for every object in the save file.

**Count**|**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----|-----
numMeta1Entries|16|meta1BlockEntry|entries|numMeta1Entries entries 

### Meta1 Block Entry

**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----
4|int|parentIndex|Parent object index into Meta1; `-1` for root object| 
4|int|meta2EntryIdx|Index into Meta2Block entries| 
4|int|numDirectChildren|Number of direct children fields of this object| 
4|int|numAllChildren|Number of all (direct and indirect) children fields| 

## Meta2 Block

The Meta2 Block contains one entry for every field in the save file.

**Count**|**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----|-----
numMeta2Entries|12|meta2BlockEntry|entries|numMeta2Entries entries 

### Meta2 Block Entry

**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----
4|int|nameHash|Hash of the field name
4|int|offset|Relative offset from start of data block
4|int|fieldInfo|Field info

FieldInfo contains several types of information:

`(fieldInfo & 0b1)` as a boolean. If set, this is an object, if not set, this is a "primitive" field.  
`(fieldInfo & 0b11111111100) >> 2` as an integer is the length of the field name including the `\0` character.  
`(fieldInfo & 0b1111111111111111111100000000000) >> 11` as an integer is the index into the Meta1Block entries if this is an object.

## Data

**Count**|**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----|-----
numMeta2Entries|??|Field|fields|fields in canonical order 

Note: The size of a field is not specified. A heuristic that worked for this application was to find the next biggest offset in Meta2.  
The next biggest offset is not necessarily the offset of the next Meta2 Entry. I assume that the data size isn't needed when the runtime already knows the data size of each field.

### Field

**Size (bytes)**|**Type**|**Name**|**Description**
-----|-----|-----|-----
??|String|name|field name including `\0` character.
0-3|Raw|alignment|Optional empty bits for alignment depending on type
??|Raw|data|field data

Field data may be 4-byte aligned, depending on the type.

### Field Data Types

**Type**|**Aligned**|**Size (bytes)**|**Description**
-----|-----|-----|-----
Object|N/A|0|Object name
Bool|No|1|Boolean value. `value = false if (data == 0x00) else true`
Char|No|1|ASCII Char in upgrades.json
TwoBool|Yes|8|Two 4-byte integers interpreted as booleans.
String|Yes|4+?|4-byte integer string length, then \<string length\> null-terminated char sequence
File|Yes|4+?|See string, but an embedded save file
Int|Yes|4|4-byte Integer
Float|Yes|4|4-byte Float
IntVector|Yes|4+(4*n)|4-byte integer count, then [count] 4-byte integers
StringVector|Yes|4+((?\_n)*n)|4-byte count, then [count] string length + null-terminated string
FloatArray|Yes|4*n|Arbitrary number of 4-byte floats
TwoInt|Yes|8|Two 4-byte integers

Notes:

* Files are Strings that can be deserialized as another Dson File. They can be identified with their Magic Number (see Header).
* Types can generally not be inferred. DsonField.java and DsonTypes.java contain an approach to efficiently identify the field type nonetheless. This approach hard-codes `FloatArray`, `StringVector`, `IntVector` and `Float` field names, and identifies the other types using a heuristic involving the data size.
* Some files contain duplicate fields within the same object. This implementation ignores them, resulting in a different file size
  when re-encoded.

The object structure is defined by the order of the fields in data. Beginning with a root object, fields are read in. When an object is encountered, this object is pushed onto the stack. Parsed fields are added to the object on top of the object stack until the object on top of the stack has all its child fields, then, the elements which have all of their child fields are popped from the stack. This is similar to most other structured data formats with the exception that there is no "end object" token.