package de.robojumper.ddsavereader.file;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class StringishTests {
    @Test
    public void testUnescapeEscape() {
        assertEquals(Stringish.escape(new byte[] {'b', 'l', 'a'}), "bla");
        assertEquals(Stringish.unescape("bla"), new byte[] {'b', 'l', 'a'});

        assertEquals(Stringish.escape(new byte[] {'r', 'o', 'o', (byte)0x80}), "roo\\x80");
        assertEquals(Stringish.unescape("roo\\x80"), new byte[] {'r', 'o', 'o', (byte)0x80});

        assertEquals(Stringish.escape(new byte[] {'r', 'o', 'o', (byte)0x81, 'k', 'i', 'n', 'd'}), "roo\\x81kind");
        assertEquals(Stringish.unescape("roo\\x81kind"), new byte[] {'r', 'o', 'o', (byte)0x81, 'k', 'i', 'n', 'd'});

        assertEquals(Stringish.escape(new byte[] {'\n'}), "\n");
        assertEquals(Stringish.unescape("\n"), new byte[] {'\n'});

        assertEquals(Stringish.escape(new byte[] {'\\'}), "\\\\");
        assertEquals(Stringish.unescape("\\\\"), new byte[] {'\\'});

        assertEquals(Stringish.escape(new byte[] {'P', (byte)0xC3, (byte)0xA9, 'r', 'i', 'c', 'a', 'r', 'd'}), "P\\xC3\\xA9ricard");
        assertEquals(Stringish.unescape("P\\xC3\\xA9ricard"), new byte[] {'P', (byte)0xC3, (byte)0xA9, 'r', 'i', 'c', 'a', 'r', 'd'});
        assertEquals(Stringish.unescape("Péricard"), new byte[] {'P', (byte)0xC3, (byte)0xA9, 'r', 'i', 'c', 'a', 'r', 'd'});
    }
}
