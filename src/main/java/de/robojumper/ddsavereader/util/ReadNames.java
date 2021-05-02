package de.robojumper.ddsavereader.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


// "Commandlet" that compiles a list of Names to use for the Hash Table from the game files (and mods)
// No output file, just pipe it to the output file
public class ReadNames {

	static final Set<String> HARDCODED_NAMES = new HashSet<>();
	static final Map<String, List<Parser>> PARSERS = new HashMap<>();
	
	static {
		// Info files (Heroes, Monsters)
		putParser(".info.darkest", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addBaseName(filePath, names);
			}
		}));
		
		// Upgrades
		putParser(".upgrades.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addBaseName(filePath, names);
				Set<String> ids = new HashSet<>();
				addSimpleJSONArrayEntryIDs(file, "trees", "id", ids);
				for (String id : ids) {
					names.add(id);
					// For backer file, strip class name
					String[] splits = id.split("\\.");
					if (splits.length == 2) {
						names.add(splits[1]);
					}
				}
			}
		}));
		
		// Camping skills
		// Camping skills do NOT have corresponding upgrade trees,
		// even though they appear in persist.upgrades.json
		// the actual saved hashed tree name is "soldierclass.skill", even though skills may be shared
		// (Though the backer file has the skills in pure form???)
		putParser(".camping_skills.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				String JsonString = new String(file);
				JsonObject rootObject = JsonParser.parseString(JsonString).getAsJsonObject();
				JsonArray arrArray = rootObject.getAsJsonArray("skills");
				if (arrArray != null) {
					for (int i = 0; i < arrArray.size(); i++) {
						String id = arrArray.get(i).getAsJsonObject().get("id").getAsString();
						names.add(id);
						JsonArray classes = arrArray.get(i).getAsJsonObject().get("hero_classes").getAsJsonArray();
						if (classes != null) {
							for (JsonElement elem : classes) {
								names.add(elem.getAsString() + "." + id);
							}
						}
					}
				}
			}
		}));
		
		// Dungeon types
		putParser(".dungeon.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addBaseName(filePath, names);
			}
		}));

		// Quest types
		putParser(".types.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addSimpleJSONArrayEntryIDs(file, "types", "id", names);
				addSimpleJSONArrayEntryIDs(file, "goals", "id", names);
			}
		}));

		// Quirks
		putParser("quirk_library.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addSimpleJSONArrayEntryIDs(file, "quirks", "id", names);
			}
		}));
		
		// Buildings and activities
		putParser(".building.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addBaseName(filePath, names);
				String jsonString = new String(file);
				JsonObject rootObject = JsonParser.parseString(jsonString).getAsJsonObject();
				JsonObject dataObject = rootObject.getAsJsonObject("data");
				if (dataObject != null) {
					JsonArray activitiesArray = dataObject.getAsJsonArray("activities");
					if (activitiesArray != null) {
						for (JsonElement elem : activitiesArray) {
							names.add(elem.getAsJsonObject().get("id").getAsString());
						}
					}
				}
			}
		}));

		// Town events 
		putParser(".events.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addBaseName(filePath, names);
				addSimpleJSONArrayEntryIDs(file, "events", "id", names);
			}
		}));
		
		// Inventory Items
		Parser inventoryParser = new Parser() {
			// example: inventory_item:	.type "heirloom" .id "portrait"	.base_stack_limit 3	.purchase_gold_value 0 .sell_gold_value 0
			final Pattern ITEMSPATTERN = Pattern.compile("inventory_item:\\s?\\.type\\s?\"([a-z_]*)\"\\s*\\.id\\s*\"([a-z_]*)\".*"); 
			
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				// split lines
				String[] lines = new String(file).split("\\r?\\n");
				for (String str : lines) {
					Matcher m = ITEMSPATTERN.matcher(str);
					if (m.matches()) {
						// zero is the entire string, not included in groupCount
						for (int i = 1; i <= m.groupCount(); i++) {
							String group = m.group(i);
							if (!group.equals("")) {
								names.add(group);
							}
						}
					}
				}
			}
		};
		putParser(".inventory.items.darkest", inventoryParser);
		putParser(".inventory.system_configs.darkest", inventoryParser);
		
		// Town events 
		putParser(".trinkets.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addSimpleJSONArrayEntryIDs(file, "entries", "id", names);
			}
		}));
		
		// Curios
		// TODO: Some Quest curios aren't caught for some reason. Where are they declared / defined??
		putParser("curio_props.csv", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				// csv file -- just read the first column
				String[] lines = new String(file).split("\\r?\\n");
				for (String str : lines) {
					String propName = str.substring(0, str.indexOf(","));
					if (!propName.equals("")) {
						names.add(propName);
					}
				}
			}
		}));
		
		// Obstacles 
		putParser("obstacle_definitions.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addSimpleJSONArrayEntryIDs(file, "props", "name", names);
			}
		}));
		
		// Obstacles 
		putParser("quest.plot_quests.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
				addSimpleJSONArrayEntryIDs(file, "plot_quests", "id", names);
			}
		}));

		// Tutorial event 
		putParser(".png", (new Parser() {
			final Pattern PATHPATTERN = Pattern.compile(".*tutorial_popup\\.([a-z_]*)\\.png");
			@Override
			public void parseFile(Path filePath, Set<String> names) {
				Matcher m = PATHPATTERN.matcher(filePath.toString());
				if (m.matches()) {
					// zero is the entire string, not included in groupCount
					for (int i = 1; i <= m.groupCount(); i++) {
						String group = m.group(i);
						if (!group.equals("")) {
							names.add(group);
						}
					}
				}
			}
			@Override
			public void parseFile(Path filePath, byte[] file, Set<String> names) {
			}
		}));

		// Stat database, hardcoded
		HARDCODED_NAMES.add("MONSTER_ENCOUNTERED");
		HARDCODED_NAMES.add("AMBUSHED");
		HARDCODED_NAMES.add("CURIO_INVESTIGATED");
		HARDCODED_NAMES.add("TRAIT_APPLIED");
		HARDCODED_NAMES.add("DEATHS_DOOR_APPLIED");
		HARDCODED_NAMES.add("ROOM_VISITED");
		HARDCODED_NAMES.add("BATTLE_COMPLETED");
		HARDCODED_NAMES.add("HALLWAY_STEP_COMPLETED");
		HARDCODED_NAMES.add("MONSTER_DEFEATED");
		HARDCODED_NAMES.add("UNDEFINED");
	}
	
	static void putParser(String extension, Parser Parser) {
		List<Parser> l = PARSERS.get(extension);
		if (l == null) {
			PARSERS.put(extension, l = new ArrayList<Parser>());
		}
		if (!l.contains(Parser)) {
			l.add(Parser);
		}
	}
	
	// args is a list of game or mod root directories
	public static Set<String> collectNames(Collection<String> paths) {
		final Set<String> names = new HashSet<String>();
		for (String path : paths) {
			File rootDir = new File(path);
			if (rootDir.isDirectory()) {
				try {
					Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
							String filename = file.toString();
							// for all parsers that want to handle this file
							for (Entry<String, List<Parser>> entry : PARSERS.entrySet()) {
								if (filename.endsWith(entry.getKey())) {
									try {
										for (Parser parser : entry.getValue()) {
											parser.parseFile(file, names);
										}
									} catch (Exception e) {
										System.err.println("Error opening/parsing " + filename.toString());
										e.printStackTrace();
									}
								}
							}
							return FileVisitResult.CONTINUE;
						}
					});
				} catch (IOException e) {
					System.err.println("Error scanning " + rootDir.toString());
					e.printStackTrace();
				}
			}
		}
		return names;
	}
	
	public static void main(String[] args) {
		Set<String> names = collectNames(Arrays.asList(args));
		for (String str : names) {
			System.out.println(str);
		}
	}
	
	interface Parser {
		default void parseFile(Path filePath, Set<String> names) throws IOException {
			parseFile(filePath, Files.readAllBytes(filePath), names);
		}
		void parseFile(Path filePath, byte[] file, Set<String> names);
	}
	
	// utility functions
	static void addBaseName(Path filePath, Set<String> Set) {
		String FileName = filePath.toFile().getName();
		if (FileName.indexOf(".") > 0) {
			FileName = FileName.substring(0, FileName.indexOf("."));
		}
		Set.add(FileName);
	}
	
	// assuming a JSON file where the root object has an array <arrayName> of objects each with a string variable <idString>
	// add that ID string
	static void addSimpleJSONArrayEntryIDs(byte[] data, String arrayName, String idString, Set<String> Set) {
		String jsonString = new String(data);
		JsonObject rootObject = JsonParser.parseString(jsonString).getAsJsonObject();
		JsonArray arrArray = rootObject.getAsJsonArray(arrayName);
		if (arrArray != null) {
			for (int i = 0; i < arrArray.size(); i++) {
				String id = arrArray.get(i).getAsJsonObject().get(idString).getAsString();
				Set.add(id);
			}
		}
	}
}
