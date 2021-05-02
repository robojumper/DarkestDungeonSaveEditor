package de.robojumper.ddsavereader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;

import de.robojumper.ddsavereader.BuildConfig;
import de.robojumper.ddsavereader.file.DsonWriter;

public class Json2Dson {

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
                    "Usage: java -jar " + BuildConfig.JAR_NAME + ".jar encode [--output, -o outfile] filename");
            System.exit(1);
        }

        byte[] OutResult = null;
        try {
            byte[] FileData = Files.readAllBytes(Paths.get(infile));
            DsonWriter d = new DsonWriter(FileData);
            OutResult = d.bytes();
        } catch (IOException | ParseException | InterruptedException e) {
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
}
