package de.robojumper.ddsavereader.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.LinkedTreeMap;

import de.robojumper.ddsavereader.model.Estate.EstateData.InventoryObject.ItemEntry;
import de.robojumper.ddsavereader.model.Estate.EstateData.WalletEntry;

public class Estate extends AbstractFile {

    class EstateData {
        class WalletEntry {

            @SerializedName("amount")
            int amount;

            @SerializedName("type")
            String type;
        }

        @SerializedName("wallet")
        Map<Integer, WalletEntry> wallet = new HashMap<>();

        class InventoryObject {
            class ItemEntry {

                @SerializedName("id")
                String id;

                @SerializedName("type")
                String type;

                @SerializedName("amount")
                int amount;
            }

            @SerializedName("items")
            Map<Integer, ItemEntry> items = new HashMap<>();
        }

        @SerializedName("trinkets")
        InventoryObject trinkets = new InventoryObject();

        @SerializedName("endless_wave_highscore")
        int endlessHighscore;

        @SerializedName("estate_items")
        InventoryObject estateItems = new InventoryObject();
    }

    EstateData estateData = new EstateData();

    @Override
    public void update(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        o = o.getAsJsonObject("base_root");

        Gson g = SaveState.makeGson();
        this.estateData = g.fromJson(o, EstateData.class);
    }

    public Integer getResourceCount(String resource) {
        String normalizedResourceName = SaveState.normalizeQueryString(resource).replace("the_", "");
        if (normalizedResourceName.equals("blood")) {
            normalizedResourceName = "the_blood";
        }
        for (Entry<Integer, WalletEntry> e : estateData.wallet.entrySet()) {
            String normalizedEName = SaveState.normalizeQueryString(e.getValue().type);
            if (normalizedEName.equals(normalizedResourceName)) {
                return e.getValue().amount;
            }
        }
        for (Entry<Integer, ItemEntry> e : estateData.estateItems.items.entrySet()) {
            String normalizedEName = SaveState.normalizeQueryString(e.getValue().id);
            if (normalizedEName.equals(normalizedResourceName)) {
                return e.getValue().amount;
            }
        }
        return null;
    }

    public String buildResourceList() {
        return Stream.concat(
                estateData.wallet.entrySet().stream().filter(e -> e.getValue().amount > 0)
                        .map(e -> e.getValue().type + ": " + e.getValue().amount),
                estateData.estateItems.items.entrySet().stream().filter(e -> e.getValue().amount > 0)
                        .map(e -> e.getValue().id + ": " + e.getValue().amount))
                .collect(Collectors.joining(", "));
    }
    
    public Map<String, Integer> getResources() {
        Map<String, Integer> resources = new LinkedTreeMap<>();
        estateData.wallet.values().stream().forEach(e -> resources.put(e.type, e.amount));
        estateData.estateItems.items.values().stream().forEach(e -> resources.put(e.id, e.amount));
        return resources;
    }

}
