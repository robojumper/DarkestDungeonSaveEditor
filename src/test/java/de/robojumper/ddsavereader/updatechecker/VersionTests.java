package de.robojumper.ddsavereader.updatechecker;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import de.robojumper.ddsavereader.updatechecker.Version;

public class VersionTests {
    
    @Test
    public void testVersions() {
        assertEquals(new Version("v0.0.9"), new Version("0.0.9"));
        assertEquals(new Version("0.0.9-SNAPSHOT"), new Version("0.0.9-BETA"));
        assertTrue(new Version("0.0.9-SNAPSHOT").compareTo(new Version("0.0.9")) < 0);
        assertTrue(new Version("0.0.9-SNAPSHOT").compareTo(new Version("0.0.9-BETA")) == 0);
        assertTrue(new Version("0.0.9").compareTo(new Version("0.0.9-BETA")) > 0);
        assertTrue(new Version("1.0.0").compareTo(new Version("0.0.0")) > 0);
        assertTrue(new Version("0.0.0").compareTo(new Version("1.0.0")) < 0);
        assertTrue(new Version("1.1.0").compareTo(new Version("1.0.0")) > 0);
        assertTrue(new Version("0.0.0").compareTo(new Version("0.10.0")) < 0);
        assertTrue(new Version("0.0.23").compareTo(new Version("0.0.16")) > 0);
        assertTrue(new Version("0.0.173245").compareTo(new Version("1.1.0")) < 0);
    }
}
