package de.robojumper.ddsavereader.util;

public class Helpers {

	public static boolean isSaveFileName(String fileName) {
		return fileName.matches(".*persist\\..*\\.json") || fileName.matches("novelty_tracker\\.json");
	}
}
