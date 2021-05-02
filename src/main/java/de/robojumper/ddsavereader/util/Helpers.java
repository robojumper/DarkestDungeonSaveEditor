package de.robojumper.ddsavereader.util;

import de.robojumper.ddsavereader.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class Helpers {

    public static final File DATA_DIR = new File(System.getProperty("user.home"), BuildConfig.DATA_DIR);

    public static boolean isSaveFileName(String fileName) {
        return fileName.matches(".*persist\\..*\\.json") || fileName.matches("novelty_tracker\\.json");
    }

    public static void hideDataDir() {
        try {
            Files.setAttribute(Helpers.DATA_DIR.toPath(), "dos:hidden", true);
        } catch (IllegalArgumentException | ClassCastException | IOException e) {
            System.out.println(e.getMessage());
        }
    }

}
