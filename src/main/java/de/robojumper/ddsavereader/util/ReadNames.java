package de.robojumper.ddsavereader.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

	static final Set<String> NAMES = new HashSet<String>();
	static final Map<String, List<Parser>> PARSERS = new HashMap<String, List<Parser>>();
	
	static {
		// Info files (Heroes, Monsters)
		putParser(".info.darkest", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addBaseName(filePath);
			}
		}));
		
		// Upgrades
		putParser(".upgrades.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addBaseName(filePath);
				addSimpleJSONArrayEntryIDs(file, "trees", "id");
			}
		}));
		
		// Camping skills
		// Camping skills do NOT have corresponding upgrade trees,
		// even though they appear in persist.upgrades.json
		// the actual saved hashed tree name is "soldierclass.skill", even though skills may be shared
		putParser(".camping_skills.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				String JsonString = new String(file);
				JsonParser parser = new JsonParser();
				JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
				JsonArray arrArray = rootObject.getAsJsonArray("skills");
				if (arrArray != null) {
					for (int i = 0; i < arrArray.size(); i++) {
						String id = arrArray.get(i).getAsJsonObject().get("id").getAsString();
						JsonArray classes = arrArray.get(i).getAsJsonObject().get("hero_classes").getAsJsonArray();
						if (classes != null) {
							for (JsonElement elem : classes) {
								NAMES.add(elem.getAsString() + "." + id);
							}
						}
					}
				}
			}
		}));
		
		// Dungeon types
		putParser(".dungeon.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addBaseName(filePath);
			}
		}));

		// Dungeon types
		putParser(".types.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addSimpleJSONArrayEntryIDs(file, "types", "id");
				addSimpleJSONArrayEntryIDs(file, "goals", "id");
			}
		}));
		
		// Buildings and activities
		putParser(".building.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addBaseName(filePath);
				String JsonString = new String(file);
				JsonParser parser = new JsonParser();
				JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
				JsonObject dataObject = rootObject.getAsJsonObject("data");
				if (dataObject != null) {
					JsonArray activitiesArray = dataObject.getAsJsonArray("activities");
					if (activitiesArray != null) {
						for (JsonElement elem : activitiesArray) {
						    NAMES.add(elem.getAsJsonObject().get("id").getAsString());
						}
					}
				}
			}
		}));

		// Town events 
		putParser(".events.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addBaseName(filePath);
				addSimpleJSONArrayEntryIDs(file, "events", "id");
			}
		}));
		
		// Inventory Items
		Parser inventoryParser = new Parser() {
			// example: inventory_item:	.type "heirloom" .id "portrait"	.base_stack_limit 3	.purchase_gold_value 0 .sell_gold_value 0
			final Pattern ITEMSPATTERN = Pattern.compile("inventory_item:\\s?\\.type\\s?\"([a-z_]*)\"\\s*\\.id\\s*\"([a-z_]*)\".*"); 
			
			@Override
			public void parseFile(Path filePath, byte[] file) {
				// split lines
				String[] lines = new String(file).split("\\r?\\n");
				for (String str : lines) {
					Matcher m = ITEMSPATTERN.matcher(str);
					if (m.matches()) {
						// zero is the entire string, not included in groupCount
						for (int i = 1; i <= m.groupCount(); i++) {
							String group = m.group(i);
							if (!group.equals("")) {
								NAMES.add(group);
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
			public void parseFile(Path filePath, byte[] file) {
				addSimpleJSONArrayEntryIDs(file, "entries", "id");
			}
		}));
		
		// Curios
		// TODO: Some Quest curios aren't caught for some reason. Where are they declared / defined??
		putParser("curio_props.csv", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				// csv file -- just read the first column
				String[] lines = new String(file).split("\\r?\\n");
				for (String str : lines) {
					String propName = str.substring(0, str.indexOf(","));
					if (!propName.equals("")) {
						NAMES.add(propName);
					}
				}
			}
		}));
		
		// Obstacles 
		putParser("obstacle_definitions.json", (new Parser() {
			@Override
			public void parseFile(Path filePath, byte[] file) {
				addSimpleJSONArrayEntryIDs(file, "props", "name");
			}
		}));

        // Tutorial event 
        putParser(".png", (new Parser() {
            final Pattern PATHPATTERN = Pattern.compile(".*tutorial_popup\\.([a-z_]*)\\.png");
            @Override
            public void parseFile(Path filePath) {
                Matcher m = PATHPATTERN.matcher(filePath.toString());
                if (m.matches()) {
                    // zero is the entire string, not included in groupCount
                    for (int i = 1; i <= m.groupCount(); i++) {
                        String group = m.group(i);
                        if (!group.equals("")) {
                            NAMES.add(group);
                        }
                    }
                }
            }
            @Override
            public void parseFile(Path filePath, byte[] file) {
            }
        }));

		// Stat database, hardcoded
		NAMES.add("MONSTER_ENCOUNTERED");
		NAMES.add("AMBUSHED");
		NAMES.add("CURIO_INVESTIGATED");
		NAMES.add("TRAIT_APPLIED");
		NAMES.add("DEATHS_DOOR_APPLIED");
		NAMES.add("ROOM_VISITED");
		NAMES.add("BATTLE_COMPLETED");
		NAMES.add("HALLWAY_STEP_COMPLETED");
		NAMES.add("MONSTER_DEFEATED");
		NAMES.add("UNDEFINED");
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
	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++) {
			File RootDir = new File(args[i]);
			if (RootDir.isDirectory()) {
				try {
					Files.walkFileTree(RootDir.toPath(), new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
							String filename = file.toString();
							// for all parsers that want to handle this file
							for (Entry<String, List<Parser>> entry : PARSERS.entrySet()) {
								if (filename.endsWith(entry.getKey())) {
									try {
										for (Parser parser : entry.getValue()) {
										    parser.parseFile(file);
										}
									} catch (IOException e) {
										// TODO Auto-generated catch block
										e.printStackTrace();
									}
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
		Iterator<String> it = NAMES.iterator();
		while (it.hasNext()) {
			String str = it.next();
			System.out.println(str);
		}
	}
	
	
    interface Parser {
        default void parseFile(Path filePath) throws IOException {
            parseFile(filePath, Files.readAllBytes(filePath));
        }
        void parseFile(Path filePath, byte[] file);
    }
    
    // utility functions
    static void addBaseName(Path filePath) {
    	addBaseNameToSet(filePath, NAMES);
    }
    
    static void addBaseNameToSet(Path filePath, Set<String> Set) {
    	String FileName = filePath.toFile().getName();
		if (FileName.indexOf(".") > 0) {
			FileName = FileName.substring(0, FileName.indexOf("."));
		}
		Set.add(FileName);
    }
    
    // assuming a JSON file where the root object has an array <arrayName> of objects each with a string variable <idString>
    // add that ID string
    static void addSimpleJSONArrayEntryIDs(byte[] data, String arrayName, String idString) {
    	addSimpleJSONArrayEntryIDsToSet(data, arrayName, idString, NAMES);
    }
    
    static void addSimpleJSONArrayEntryIDsToSet(byte[] data, String arrayName, String idString, Set<String> Set) {
    	String JsonString = new String(data);
		JsonParser parser = new JsonParser();
		JsonObject rootObject = parser.parse(JsonString).getAsJsonObject();
		JsonArray arrArray = rootObject.getAsJsonArray(arrayName);
		if (arrArray != null) {
			for (int i = 0; i < arrArray.size(); i++) {
				String id = arrArray.get(i).getAsJsonObject().get(idString).getAsString();
				Set.add(id);
			}
		}
    }
}
