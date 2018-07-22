package de.robojumper.ddsavereader.updatechecker;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import de.fuerstenau.buildconfig.BuildConfig;

public class UpdateChecker {
    public static class Release {
        public final Version version;
        public final String htmlUrl;

        Release(String version, String url) {
            this.version = new Version(version);
            this.htmlUrl = url;
        }
    }

    public static Release getLatestRelease() throws IOException {

        HttpURLConnection request = (HttpURLConnection) new URL(BuildConfig.UPDATE_URL).openConnection();
        request.connect();

        JsonParser parser = new JsonParser();
        JsonElement rootObject = parser.parse(new InputStreamReader((InputStream) request.getContent()));

        return new Release(rootObject.getAsJsonObject().get("tag_name").getAsString(),
                rootObject.getAsJsonObject().get("html_url").getAsString());
    }
}
