package de.robojumper.ddsavereader;

import de.robojumper.ddsavereader.BuildConfig;
import de.robojumper.ddsavereader.spreadsheets.SpreadsheetsService;
import de.robojumper.ddsavereader.ui.MainWindow;
import de.robojumper.ddsavereader.util.ReadNames;

public class Main {
    public static void main(String... args) {
        if (args.length == 0) {
            MainWindow.main(args);
        } else {
            String[] restArgs = new String[args.length - 1];
            System.arraycopy(args, 1, restArgs, 0, args.length - 1);
            
            switch (args[0].toLowerCase()) {
            case "decode":
            case "dson2json":
                Dson2Json.main(restArgs);
                break;
            case "encode":
            case "json2dson":
                Json2Dson.main(restArgs);
                break;
            case "sheets":
            case "spreadsheets":
                System.out.println(BuildConfig.DISPLAY_NAME + "/" + BuildConfig.VERSION + ", " + BuildConfig.GITHUB_URL);
                SpreadsheetsService.main(restArgs);
                break;
            case "names":
                ReadNames.main(restArgs);
                break;
            default:
                System.err.println("Error: Unknown command " + args[0]);
                System.err.println("Commands: decode, encode, sheets, names");
            }
        }
    }
}
