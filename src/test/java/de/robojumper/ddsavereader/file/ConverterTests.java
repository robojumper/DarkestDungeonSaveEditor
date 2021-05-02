package de.robojumper.ddsavereader.file;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.io.ByteStreams;

import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;

/**
 * Class that performs round-trip tests on provided save files. Decodes,
 * encodes, decodes again. A file must encode to the same size that it decoded
 * from (save for `persist.progression.json`, which has a duplicate key), and
 * that file must decode to the exact same text that the original file decoded
 * to. We can't check for binary equality for encoded files, as the game has a
 * tendency to write garbage bits / bits I don't know the purpose for to the
 * save file.
 */
public class ConverterTests {

    @BeforeClass
    public void readNames() throws IOException {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(ConverterTests.class.getClassLoader().getResourceAsStream("./names.txt")));
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.equals("")) {
                DsonTypes.offerName(line);
            }
        }
    }

    public void testCorrectConversion(String folderName) throws ParseException, IOException {
        List<byte[]> files = new ArrayList<>();
        Set<Integer> dupeFieldFiles = new HashSet<>();
        List<byte[]> decodedFiles = new ArrayList<>();
        List<byte[]> reEncodedFiles = new ArrayList<>();

        System.out.println("Folder " + folderName);

        List<String> fileList = getResourceFiles(folderName);

        for (String s : fileList) {
            files.add(ByteStreams.toByteArray(
                    ConverterTests.class.getClassLoader().getResourceAsStream("./" + folderName + "/" + s)));
        }

        // Every file must decode without throwing exceptions
        for (int i = 0; i < files.size(); i++) {
            try {
                DsonFile file = new DsonFile(files.get(i), UnhashBehavior.POUNDUNHASH);
                if (file.hasDuplicateFields()) {
                    dupeFieldFiles.add(i);
                }
                decodedFiles.add(file.getJSonString(0, false).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                fail(fileList.get(i) + " doesn't decode", e);
            }
        }

        // Every file must re-encode without throwing exceptions
        for (int i = 0; i < decodedFiles.size(); i++) {
            try {
                reEncodedFiles.add(new DsonWriter(decodedFiles.get(i)).bytes());
            } catch (Exception e) {
                fail(fileList.get(i) + " doesn't re-endode", e);
            }
            // Files with duplicate fields will not have the same size anyway.
            // Filter them out here
            if (!dupeFieldFiles.contains(i)) {
                assertEquals(reEncodedFiles.get(i).length, files.get(i).length,
                        fileList.get(i) + " encodes to different number of bytes");
            }
        }

        // Every file must re-decode to the same bytes
        for (int i = 0; i < reEncodedFiles.size(); i++) {
            String jsonString = new DsonFile(reEncodedFiles.get(i), UnhashBehavior.POUNDUNHASH).getJSonString(0, false);
            assertEquals(jsonString.getBytes(StandardCharsets.UTF_8), decodedFiles.get(i),
                    fileList.get(i) + " re-decodes differently");
        }
    }

    @Test
    public void testMyProfile() throws ParseException, IOException {
        testCorrectConversion("profile1");
    }

    @Test
    public void testSwitchProfile() throws ParseException, IOException {
        testCorrectConversion("profileSwitch");
    }

    @Test
    public void testRedditProfile() throws ParseException, IOException {
        testCorrectConversion("profileReddit");
    }
    
    @Test
    public void testModLimit() throws ParseException, IOException {
        testCorrectConversion("modlimit");
    }


    @Test
    public void testOtherFiles() throws ParseException, IOException {
        testCorrectConversion("otherFiles");
        testCorrectConversion("backgroundNames");
        testCorrectConversion("skillCooldownValues");
        testCorrectConversion("nonAsciiField");
        testCorrectConversion("backerHeroes");
        testCorrectConversion("quirk_monster_class_ids");
        testCorrectConversion("dead_hero_entries");
        testCorrectConversion("networkFiles");
		testCorrectConversion("valid_additional_mash_entry_indexes");
    }

    private List<String> getResourceFiles(String path) throws IOException {
        List<String> filenames = new ArrayList<>();

        try (InputStream in = getResourceAsStream(path);
                BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String resource;

            while ((resource = br.readLine()) != null) {
                filenames.add(resource);
            }
        }

        return filenames;
    }

    private InputStream getResourceAsStream(String resource) {
        final InputStream in = getContextClassLoader().getResourceAsStream(resource);

        return in == null ? getClass().getResourceAsStream(resource) : in;
    }

    private ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
