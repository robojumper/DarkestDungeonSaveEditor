package de.robojumper.ddsavereader.updatechecker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.common.io.CharStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import de.robojumper.ddsavereader.BuildConfig;
import de.robojumper.ddsavereader.util.Helpers;

public class UpdateChecker {

    private static final File CACHED_LAST_RELEASE = new File(Helpers.DATA_DIR, "updatechecker.txt");
    private static final int UPDATE_CHECK_INTERVAL = 1000 * 60 * 60 * 1; // 1 hour

    public static class Release {
        public final Version version;
        public final String htmlUrl;

        Release(String version, String url) {
            this.version = new Version(version);
            this.htmlUrl = url;
        }
    }

    public static Release getLatestRelease() throws IOException {

        String content;
        long curr = System.currentTimeMillis();
        if (CACHED_LAST_RELEASE.exists() && CACHED_LAST_RELEASE.lastModified() + UPDATE_CHECK_INTERVAL > curr) {
            System.err.println(
                    "Using cached update check result, checked " + CACHED_LAST_RELEASE.lastModified() + ", curr " + curr
                            + ", will check in " + (CACHED_LAST_RELEASE.lastModified() + UPDATE_CHECK_INTERVAL - curr));
            content = new String(Files.readAllBytes(CACHED_LAST_RELEASE.toPath()), StandardCharsets.UTF_8);
        } else {
            System.err.println("Checking for new update");
            HttpURLConnection request = (HttpURLConnection) new URL(BuildConfig.UPDATE_URL).openConnection();
            request.connect();
            content = CharStreams.toString(new InputStreamReader((InputStream) request.getContent()));
            Files.write(CACHED_LAST_RELEASE.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        JsonElement rootObject = JsonParser.parseString(content);

        return new Release(rootObject.getAsJsonObject().get("tag_name").getAsString(),
                rootObject.getAsJsonObject().get("html_url").getAsString());
    }
}
