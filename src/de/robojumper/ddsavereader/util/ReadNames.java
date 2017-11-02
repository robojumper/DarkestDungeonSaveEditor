package de.robojumper.ddsavereader.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


// "Commandlet" that compiles a list of Names to use for the Hash Table from the game files (and mods)
// No output file, just pipe it to the output file
public class ReadNames {

	static final Set<String> NAMES = new HashSet<String>();
	// args is a list of game or mod root directories
	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++) {
			File RootDir = new File(args[i]);
			if (RootDir.isDirectory()) {
				// parse monster list
				FindInfoFiles(RootDir);
				// parse upgrade trees
				ParseUpgradeTrees(RootDir);
				// dungeon types, quests
				ParseMissions(RootDir);
				// buildings, activities
				ParseBuildings(RootDir);
				// events
				ParseTownEvents(RootDir);
			}
		}
		Iterator<String> it = NAMES.iterator();
		while (it.hasNext()) {
			String str = it.next();
			System.out.println(str);
		}
	}
	
	


	private static void ParseUpgradeTrees(File directory) {
		try {
			Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".upgrades.json")) {
						// add file name, this catches buildings etc.
						String FileName = file.toFile().getName();
						if (FileName.indexOf(".") > 0) {
							FileName = FileName.substring(0, FileName.indexOf("."));
						}
						NAMES.add(FileName);
						// parse trees
						try {
							String JsonString = new String(Files.readAllBytes(file));
							JsonParser parser = new JsonParser();
							JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
							JsonArray arrTrees = rootObject.getAsJsonArray("trees");
							if (arrTrees != null) {
								for (int i = 0; i < arrTrees.size(); i++) {
									String id = arrTrees.get(i).getAsJsonObject().get("id").getAsString();
									NAMES.add(id);
								}
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static void ParseMissions(File directory) {
		try {
			Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					// dungeons
					if (file.toString().endsWith(".dungeon.json")) {
						String FileName = file.toFile().getName();
						if (FileName.indexOf(".") > 0) {
							FileName = FileName.substring(0, FileName.indexOf("."));
						}
						NAMES.add(FileName);
					} else if (file.toString().endsWith(".types.json")) {
						// parse quest goals and types
						try {
							String JsonString = new String(Files.readAllBytes(file));
							JsonParser parser = new JsonParser();
							JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
							JsonArray arrGoals = rootObject.getAsJsonArray("goals");
							if (arrGoals != null) {
								for (int i = 0; i < arrGoals.size(); i++) {
									String id = arrGoals.get(i).getAsJsonObject().get("id").getAsString();
									NAMES.add(id);
								}
							}
							JsonArray arrTypes = rootObject.getAsJsonArray("types");
							if (arrTypes != null) {
								for (int i = 0; i < arrTypes.size(); i++) {
									String id = arrTypes.get(i).getAsJsonObject().get("id").getAsString();
									NAMES.add(id);
								}
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	// find all "*.info.darkest" file, this file name without extension are ususally important IDs
	private static void FindInfoFiles(File MonsterDir) {
		try {
			Files.walkFileTree(MonsterDir.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".info.darkest")) {
						String FileName = file.toFile().getName();
						if (FileName.indexOf(".") > 0) {
							FileName = FileName.substring(0, FileName.indexOf("."));
						}
						NAMES.add(FileName);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static void ParseBuildings(File directory) {
		try {
			Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".building.json")) {
						// add file name
						String FileName = file.toFile().getName();
						if (FileName.indexOf(".") > 0) {
							FileName = FileName.substring(0, FileName.indexOf("."));
						}
						NAMES.add(FileName);
						// parse activities
						try {
							String JsonString = new String(Files.readAllBytes(file));
							JsonParser parser = new JsonParser();
							JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
							JsonObject dataObject = rootObject.getAsJsonObject("data");
							if (dataObject != null) {
								JsonObject activitiesObject = dataObject.getAsJsonObject("activities");
								if (activitiesObject != null) {
									for (Entry<String, JsonElement> elem : activitiesObject.entrySet()) {
										NAMES.add(elem.getKey());
									}
								}
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static void ParseTownEvents(File directory) {
		try {
			Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".events.json")) {
						// add file name
						String FileName = file.toFile().getName();
						if (FileName.indexOf(".") > 0) {
							FileName = FileName.substring(0, FileName.indexOf("."));
						}
						NAMES.add(FileName);
						// parse activities
						try {
							String JsonString = new String(Files.readAllBytes(file));
							JsonParser parser = new JsonParser();
							JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
							JsonArray arrEvents = rootObject.getAsJsonArray("events");
							if (arrEvents != null) {
								for (int i = 0; i < arrEvents.size(); i++) {
									String id = arrEvents.get(i).getAsJsonObject().get("id").getAsString();
									NAMES.add(id);
								}
							}
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
