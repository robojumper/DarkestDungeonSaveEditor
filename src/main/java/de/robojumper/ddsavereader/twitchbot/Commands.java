package de.robojumper.ddsavereader.twitchbot;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robojumper.ddsavereader.model.Hero;
import de.robojumper.ddsavereader.model.SaveState;
import de.robojumper.ddsavereader.model.Hero.HeroStatus;

public class Commands {
    
    interface Command {
        public String buildResponse(SaveState s, String command, String params);
    }
    
    public static final Pattern COMMAND_PATTERN = Pattern.compile("!dd([a-zA-Z0-9]+)\\s*(.*)");
    private final static Map<String, Command> COMMANDS = new HashMap<>();
    
    static {
        COMMANDS.put("status", (state, command, params) -> { 
            Integer heroID = state.getRoster().getHeroID(params);
            Hero h = state.getRoster().getHero(heroID);
            if (h != null) {
                StringBuilder response = new StringBuilder();
                response.append(h.getName());
                response.append(": ");
                response.append(h.getStatus().toString());
                if (h.getStatus() == HeroStatus.STAFFED) {
                    String activity = state.getTown().getHeroActivity(heroID);
                    if (activity != null) {
                        response.append(" (");
                        response.append(activity);
                        response.append(")");
                    }
                }
                return response.toString();
            }
            return null;
        });
        
        COMMANDS.put("kills", (state, command, params) -> { 
            Integer heroID = state.getRoster().getHeroID(params);
            Hero h = state.getRoster().getHero(heroID);
            if (h != null) {
                int kills = h.getKills();
                if (kills > 300 ) {
                    return h.getName() + ": over 300 confirmed kills (" + kills + ")";
                } else {
                    return h.getName() + ": " + h.getKills() + " confirmed kills";
                }
            }
            return null;
        });
        
        COMMANDS.put("quirks", (state, command, params) -> { 
            Hero h = state.getRoster().getHero(params);
            if (h != null) {
                return h.getName() + ": " + h.getQuirks();
            }
            return null;
        });
        
        Command resourceCommand = (state, command, params) -> { 
            if (!params.equals("")) {
                Integer count = state.getEstate().getResourceCount(params);
                if (count != null) {
                    return params + ": " + count;
                }
            } else {
                return state.getEstate().buildResourceList();
            }
            return null;
        }; 
        COMMANDS.put("resource", resourceCommand);
        COMMANDS.put("resources", resourceCommand);
        
        COMMANDS.put("dismissed", (state, command, params) -> { 
            return state.getRoster().getNumDismissedHeroes() + " dismissed Heroes.";
        });
        
        Command weekCommand = (state, command, params) -> { 
            return "Week " + state.getCampaignLog().getNumWeeks();
        };
        COMMANDS.put("week", weekCommand);
        COMMANDS.put("weeks", weekCommand);
        
        COMMANDS.put("districts", (state, command, params) -> { 
            return state.getTown().buildDistricts();
        });
        
    }

    public static String buildResponse(SaveState s, String content) {
        if (content.length() > 1) {
            Matcher m = COMMAND_PATTERN.matcher(content); 
            if (m.matches()) {
                String command = m.group(1).toLowerCase();
                String params = m.group(2).toLowerCase();

                Command c = COMMANDS.get(command);
                if (c != null) {
                    synchronized (s) {
                        return c.buildResponse(s, command, params);
                    }
                }

            }
        }
        return null;
    }
}
