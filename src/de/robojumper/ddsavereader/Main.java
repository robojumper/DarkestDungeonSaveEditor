package de.robojumper.ddsavereader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import de.robojumper.ddsavereader.file.DsonFile;

public class Main {

	public static void main(String[] args) {
		String arg;
		int i = 0;
		boolean debug = false;
		String outfile = "", infile = "";

		while (i < args.length && args[i].startsWith("-")) {
			arg = args[i++];

			if (arg.equals("-d") || arg.equals("--debug")) {
				debug = true;
			}

			if (arg.equals("-o") || arg.equals("--output")) {
				if (i < args.length) {
					outfile = args[i++];
				} else {
					System.err.println("-output requires a filename");
				}
			}
		}
		
		if (i == args.length - 1) {
			infile = args[i++];
		} else {
			System.err.println("Usage: ParseCmdLine [--debug, -d] [--output, -o outfile] filename");
		}

		String OutResult = null;
		try {
			byte[] FileData = Files.readAllBytes(Paths.get(infile));
			DsonFile File = new DsonFile(FileData, false);
			OutResult = File.GetJSonString(0, debug);
		} catch (IOException e) {
			System.err.println("Could not read " + infile);
			System.err.println(e.getMessage());
			System.exit(1);
		}
		
		if (!outfile.equals("")) {
			try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outfile), StandardCharsets.UTF_8)) {
				writer.write(OutResult, 0, OutResult.length());
			} catch (IOException e) {
				System.err.println("Could not read " + infile);
				System.err.println(e.getMessage());
				System.exit(1);
			}
		}
	}
}
