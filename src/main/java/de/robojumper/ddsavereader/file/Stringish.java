package de.robojumper.ddsavereader.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A stringish is a bunch of bytes that may or may not be UTF-8.
 * When encoded as a Java string, non-UTF-8 bytes are escaped using an \x escape sequence.
 * Note that JSON encoding is ANOTHER layer on top, this encoding step only handles non-UTF-8 bytes.
 */
public class Stringish {
    // https://stackoverflow.com/questions/31419860/java-utf-8-byte-length-of-a-single-code-point-surrogates-again
    static int getUtf8ByteCount(int codePoint) {
        return codePoint <= 0x7F ? 1
            : codePoint <= 0x7FF ? 2
            : codePoint <= 0xFFFF ? 3
            : 4;
    }

    // idk what I'm doing
    public static byte[] unescape(String escaped) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int length = escaped.length();
        byte[] utf8 = escaped.getBytes(StandardCharsets.UTF_8);
        int byteOffset = 0;
        for (int offset = 0; offset < length; ) {
            int codepoint = escaped.codePointAt(offset);
            int numBytes = getUtf8ByteCount(codepoint);
            int numChars = Character.charCount(codepoint);
            
            if (escaped.codePointAt(offset) != '\\') {
                buf.write(utf8, byteOffset, numBytes);
                byteOffset += numBytes;
                offset += numChars;
            } else if (offset + 1 == length) {
                throw new IllegalArgumentException(String.format("Bad string %s: incomplete escape sequence", escaped));
            } else {
                // code point is \
                offset += 1;
                byteOffset += 1;
                codepoint = escaped.codePointAt(offset);
                int numBytes2 = getUtf8ByteCount(codepoint);
                if (numBytes2 != 1) {
                    throw new IllegalArgumentException(String.format("Bad string %s: invalid escape sequence", escaped));
                }

                if (codepoint == '\\') {
                    buf.write('\\');
                    offset += 1;
                    byteOffset += 1;
                } else if (codepoint == 'x') {
                    offset += 1;
                    byteOffset += 1;
                    if (offset + 1 >= length) {
                        throw new IllegalArgumentException(String.format("Bad string %s: incomplete hex escape sequence", escaped));
                    }
                    String hexNum = escaped.substring(offset, offset + 2);
                    int num = Integer.parseInt(hexNum, 16);
                    assert num >= 0 && num <= 255;
                    buf.write((byte)num);
                    offset += 2;
                    byteOffset += 2;
                } else {
                    throw new IllegalArgumentException(String.format("Bad string %s: invalid escape sequence", escaped));
                }
            }
        }

        return buf.toByteArray();
    }

    public static String escape(byte[] bytes) {
        // TODO - this is really ugly and escapes ALL non-ASCII characters
        // but I don't want to manually parse UTF-8
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes) {
            if (b > 0) {
                if (b == '\\') {
                    buf.append((char)b);
                }
                buf.append((char)b);
            } else {
                buf.append(String.format("\\x%02X", b));
            }
        }

        return buf.toString();
    }
}
