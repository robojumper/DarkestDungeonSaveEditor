package de.robojumper.ddsavereader.file;

import org.testng.annotations.*;

import com.google.common.io.ByteStreams;

import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.Json2Dson;

import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class ConverterTests {

    private List<byte[]> files;

    @BeforeClass
    public void setUp() throws IOException {
        files = new ArrayList<>();
        String[] fileList = { "novelty_tracker.json", "persist.campaign_log.json", "persist.campaign_mash.json",
                "persist.curio_tracker.json", "persist.estate.json", "persist.game_knowledge.json", "persist.game.json",
                "persist.journal.json", "persist.narration.json",
                /* "persist.progression.json", */ // progression has a quirk where slay_a_squiffy_with_jester appears
                                                  // twice. Just skip that one, since it would require us to validate
                                                  // Json in DsonFile
                "persist.quest.json", "persist.roster.json", "persist.town_event.json", "persist.town.json",
                "persist.upgrades.json", };

        for (String s : fileList) {
            files.add(ByteStreams
                    .toByteArray(ConverterTests.class.getClassLoader().getResourceAsStream("./profile1/" + s)));
        }
    }

    @Test
    public void testCorrectConversion() throws ParseException, IOException {
        List<byte[]> decodedFiles = new ArrayList<>();
        List<byte[]> reEncodedFiles = new ArrayList<>();

        // Every file must decode without throwing exceptions
        for (int i = 0; i < files.size(); i++) {
            String file = new DsonFile(files.get(i), false).getJSonString(0, false);
            decodedFiles.add(file.getBytes(StandardCharsets.UTF_8));
        }

        // Every file must re-encode without throwing exceptions
        for (int i = 0; i < decodedFiles.size(); i++) {
            reEncodedFiles.add(new Json2Dson(decodedFiles.get(i)).bytes());
        }

        // Every file must re-decode to the same bytes
        for (int i = 0; i < reEncodedFiles.size(); i++) {
            String jsonString = new DsonFile(reEncodedFiles.get(i), false).getJSonString(0, false);
            assertEquals(jsonString.getBytes(StandardCharsets.UTF_8), decodedFiles.get(i));
        }
    }
}
