package de.robojumper.ddsavereader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import de.robojumper.ddsavereader.BuildConfig;
import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonTypes;
import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;

public class Dson2Json {

	public static void main(String[] args) {
		String arg;
		int i = 0;
		boolean debug = false;
		String outfile = "", infile = "", namefile = "";

		while (i < args.length && args[i].startsWith("-")) {
			arg = args[i++];

			if (arg.equals("-d") || arg.equals("--debug")) {
				debug = true;
			}

			if (arg.equals("-o") || arg.equals("--output")) {
				if (i < args.length) {
					outfile = args[i++];
				} else {
					System.err.println("--output requires a filename");
				}
			}
			
			if (arg.equals("-n") || arg.equals("--names")) {
				if (i < args.length) {
					namefile = args[i++];
				} else {
					System.err.println("--names requires a filename");
				}
			}
		}
		
		if (i == args.length - 1) {
			infile = args[i++];
		} else {
			System.err.println("Usage: java -jar " + BuildConfig.JAR_NAME + ".jar decode [--debug, -d] [--names, -n <namefile>] [--output, -o <outfile>] filename");
			System.exit(1);
		}
		
		// for now, read in the names from a specified text file
		// This could be read in from game data!
		if (!namefile.equals("")) {
			try (BufferedReader br = new BufferedReader(new FileReader(Paths.get(namefile).toFile()))) {
			    String line;
			    while ((line = br.readLine()) != null) {
			    	if (!line.equals("")) {
			    		DsonTypes.offerName(line);
			    	}
			    }
			} catch (IOException e) {
				System.err.println("Could not read " + namefile);
				System.err.println(e.getMessage());
				System.exit(1);
			}
		}

		String OutResult = null;
		try {
			byte[] FileData = Files.readAllBytes(Paths.get(infile));
			DsonFile File = new DsonFile(FileData, UnhashBehavior.POUNDUNHASH);
			OutResult = File.getJSonString(0, debug);
		} catch (Exception e) {
			System.err.println("Could not read " + infile);
			System.err.println(e.getMessage());
			System.exit(1);
		}
		
		if (!outfile.equals("")) {
			try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outfile), StandardCharsets.UTF_8)) {
				writer.write(OutResult, 0, OutResult.length());
				writer.write("\n");
			} catch (IOException e) {
				System.err.println("Could not read " + outfile);
				System.err.println(e.getMessage());
				System.exit(1);
			}
		}
	}
}
