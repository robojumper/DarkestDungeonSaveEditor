package de.robojumper.ddsavereader.spreadsheets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;

import de.robojumper.ddsavereader.model.CampaignLog.BaseRTTI;
import de.robojumper.ddsavereader.model.CampaignLog.Chapter;
import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonTypes;
import de.robojumper.ddsavereader.model.Hero;
import de.robojumper.ddsavereader.model.SaveState;
import de.robojumper.ddsavereader.watcher.DarkestSaveFileWatcher;

public class SpreadsheetsService {
    private static final String APPLICATION_NAME = "robojumper-DarkestDungeonSpreadsheets/1.0";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    // Directory to store user credentials.
    private static final File CREDENTIALS_FOLDER = new File(System.getProperty("user.home"), ".store/ddspreadsheets");

    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);
    private static final String CLIENT_SECRET_DIR = "/client_secret.json";

    private static final int INFO_SHEET_ID = 543000;
    private static final int ROSTER_SHEET_ID = 543100;
    private static final int LOG_SHEET_ID = 543200;
    private static final int ESTATE_SHEET_ID = 543300;
    private static final int PARTY_SHEET_ID = 543400;

    private static final String COLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * Creates an authorized Credential object.
     * 
     * @param HTTP_TRANSPORT
     *            The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException
     *             If there is no client_secret.
     */
    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        // Load client secrets.
        GoogleClientSecrets clientSecrets = null;
        try (InputStream in = new FileInputStream("." + CLIENT_SECRET_DIR)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        } catch (FileNotFoundException e) {
            InputStream in = SpreadsheetsService.class.getResourceAsStream(CLIENT_SECRET_DIR);
            if (in != null) {
                clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
            } else {
                System.out.println("Client ID + Secrets File couldn't be found");
                System.exit(1);
            }
        }

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY,
                clientSecrets, SCOPES).setDataStoreFactory(new FileDataStoreFactory(CREDENTIALS_FOLDER))
                        .setAccessType("offline").build();
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    public static void main(String... args) throws IOException, GeneralSecurityException {
        System.out.println(Arrays.stream(args).collect(Collectors.joining("\n")));

        if (args.length != 3) {
            System.out.println(
                    "Usage: java -cp DDSaveReader.jar de.robojumper.ddsavereader.spreadsheets.SpreadsheetsService SpreadsheetID SaveDir NameList");
        }

        final String spreadsheetId = args[0];

        try (BufferedReader br = new BufferedReader(new FileReader(Paths.get(args[2]).toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.equals("")) {
                    DsonTypes.NAME_TABLE.put(DsonFile.stringHash(line), line);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read " + args[2]);
        }

        // Build a new authorized API client service.
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

        Sheets service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME).build();

        final SaveState state = new SaveState();
        DarkestSaveFileWatcher watcher = new DarkestSaveFileWatcher(state, args[1]);
        watcher.watchSaveFiles();

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        final Runnable sheetUpdater = new Runnable() {

            @Override
            public void run() {
                try {
                    System.out.println("Requesting spreadsheet " + spreadsheetId);
                    Spreadsheet spreadSheet = service.spreadsheets().get(spreadsheetId).execute();
                    System.out.println("Got spreadsheet");
                    List<Request> requests = new ArrayList<>();

                    if (!hasSheet(spreadSheet, "Info")) {
                        requests.add(new Request().setAddSheet(new AddSheetRequest()
                                .setProperties(new SheetProperties().setTitle("Info").setSheetId(INFO_SHEET_ID))));
                    }
                    if (!hasSheet(spreadSheet, "Roster")) {
                        addSheetSetupRequests(requests, ROSTER_SHEET_ID, "Roster");
                    }
                    if (!hasSheet(spreadSheet, "Log")) {
                        addSheetSetupRequests(requests, LOG_SHEET_ID, "Log");
                    }
                    if (!hasSheet(spreadSheet, "Estate")) {
                        addSheetSetupRequests(requests, ESTATE_SHEET_ID, "Estate");
                    }
                    if (!hasSheet(spreadSheet, "Party")) {
                        requests.add(new Request().setAddSheet(new AddSheetRequest()
                                .setProperties(new SheetProperties().setTitle("Party").setSheetId(PARTY_SHEET_ID)
                                        .setGridProperties(new GridProperties().setFrozenColumnCount(0)))));
                        requests.add(new Request().setRepeatCell(new RepeatCellRequest()
                                .setRange(new GridRange().setSheetId(PARTY_SHEET_ID).setStartColumnIndex(0)
                                        .setEndColumnIndex(1))
                                .setCell(new CellData().setUserEnteredFormat(
                                        new CellFormat().setTextFormat(new TextFormat().setBold(Boolean.TRUE))))
                                .setFields("*")));
                    }

                    requests.add(new Request().setUnmergeCells(new UnmergeCellsRequest()
                            .setRange(new GridRange().setSheetId(PARTY_SHEET_ID).setStartRowIndex(1))));

                    List<ValueRange> data = new ArrayList<ValueRange>();

                    // Info
                    {
                        data.add(new ValueRange().setRange("Info!A1").setValues(
                                Collections.singletonList(Collections.singletonList("Save Data, last updated at "
                                        + new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date())))));
                    }
                    synchronized (state) {
                        // Roster
                        {
                            List<List<Object>> rosterTable = makeRosterTable(state);
                            int cols = rosterTable.stream().map(l -> l.size()).max(Integer::compare).get();
                            int rows = rosterTable.size();

                            data.add(new ValueRange().setRange("Roster!A1:" + COLS.charAt(cols) + rows)
                                    .setValues(rosterTable));
                        }

                        // Log
                        {
                            List<Chapter> chapters = state.getCampaignLog().getChapters();
                            List<List<Object>> rows = new ArrayList<>();

                            for (Chapter c : chapters) {
                                boolean first = true;
                                for (BaseRTTI e : c.events) {
                                    List<Object> row = new ArrayList<>();
                                    if (first) {
                                        row.add(c.chapterIndex);
                                        first = false;
                                    } else {
                                        row.add("");
                                    }
                                    row.addAll(e.getCells());
                                    rows.add(row);
                                }
                                rows.add(new ArrayList<>());
                            }

                            rows.add(0, Arrays.asList("Week", "Type"));
                            rows.add(1, new ArrayList<>());

                            int numCols = rows.stream().map(l -> l.size()).max(Integer::compare).get();
                            int numRows = rows.size();

                            data.add(new ValueRange().setRange("Log!A1:" + COLS.charAt(numCols) + numRows)
                                    .setValues(rows));
                        }

                        // Estate
                        {
                            Map<String, Integer> resources = state.getEstate().getResources();
                            List<List<Object>> rows = new ArrayList<>();

                            for (Map.Entry<String, Integer> e : resources.entrySet()) {
                                rows.add(Arrays.asList(e.getKey(), e.getValue()));
                            }

                            rows.add(0, Arrays.asList("Resource", "Amount"));
                            rows.add(1, new ArrayList<>());

                            int numCols = rows.stream().map(l -> l.size()).max(Integer::compare).get();
                            int numRows = rows.size();

                            data.add(new ValueRange().setRange("Estate!A1:" + COLS.charAt(numCols) + numRows)
                                    .setValues(rows));
                        }

                        // Party
                        {
                            // First of all, clear all cell borders
                            requests.add(new Request().setUpdateBorders(new UpdateBordersRequest()
                                    .setRange(new GridRange().setSheetId(PARTY_SHEET_ID).setStartRowIndex(0))
                                    .setBottom(new Border().setStyle("NONE")).setTop(new Border().setStyle("NONE"))
                                    .setLeft(new Border().setStyle("NONE")).setRight(new Border().setStyle("NONE"))
                                    .setInnerHorizontal(new Border().setStyle("NONE"))
                                    .setInnerVertical(new Border().setStyle("NONE"))));

                            List<Hero> party = state.getRoster().getParty();

                            List<List<Object>> headerRows = new ArrayList<>(21);
                            headerRows.add(Arrays.asList("Name"));
                            headerRows.add(Arrays.asList("Class"));
                            headerRows.add(Arrays.asList("XP"));
                            headerRows.add(Arrays.asList(""));
                            headerRows.add(Arrays.asList("Skills"));
                            while (headerRows.size() < 12) {
                                headerRows.add(Arrays.asList(""));
                            }
                            headerRows.add(Arrays.asList("Camping Skills"));
                            while (headerRows.size() < 16) {
                                headerRows.add(Arrays.asList(""));
                            }
                            headerRows.add(Arrays.asList("Trinkets"));
                            while (headerRows.size() < 19) {
                                headerRows.add(Arrays.asList(""));
                            }
                            headerRows.add(Arrays.asList("Quests"));
                            headerRows.add(Arrays.asList("Kills"));

                            data.add(new ValueRange().setRange("Party!A1:A21").setValues(headerRows));

                            for (int i = 0; i < party.size(); i++) {
                                Hero h = party.get(i);
                                if (h != null) {
                                    List<List<Object>> rows = new ArrayList<>(21); // Name + level + XP + space +
                                                                                   // 7skills + space + 3camping + space
                                                                                   // + 2trinkets + space + quests +
                                                                                   // kills
                                    rows.add(Arrays.asList(h.getName()));
                                    rows.add(Arrays.asList("Level " + state.getCampaignLog().getHeroLevel(h.getID())
                                            + " " + h.getHeroClass()));
                                    rows.add(Arrays.asList(h.getXP() + " XP"));
                                    rows.add(Arrays.asList(""));
                                    h.getSkills().stream().forEach(s -> rows.add(Arrays.asList(s)));
                                    while (rows.size() < 11) {
                                        rows.add(Arrays.asList(""));
                                    }
                                    rows.add(Arrays.asList(""));
                                    h.getCampingSkills().stream().forEach(s -> rows.add(Arrays.asList(s)));
                                    while (rows.size() < 15) {
                                        rows.add(Arrays.asList(""));
                                    }
                                    rows.add(Arrays.asList(""));
                                    h.getTrinkets().stream().forEach(s -> rows.add(Arrays.asList(s)));
                                    while (rows.size() < 18) {
                                        rows.add(Arrays.asList(""));
                                    }
                                    rows.add(Arrays.asList(""));
                                    rows.add(Arrays
                                            .asList(state.getCampaignLog().getHeroMissionCount(h.getID()) + " Quests"));
                                    rows.add(Arrays.asList(h.getKills() + " Kills"));

                                    int startCol = (i * 2) + 1;
                                    int endCol = (i * 2) + 3;
                                    int startRow = 0;
                                    int endRow = rows.size();

                                    String range = "Party!" + COLS.charAt(startCol) + (startRow + 1) + ":"
                                            + COLS.charAt(endCol) + (endRow + 1);
                                    data.add(new ValueRange().setRange(range).setValues(rows));

                                    GridRange g = new GridRange().setSheetId(PARTY_SHEET_ID).setStartRowIndex(0)
                                            .setEndRowIndex(endRow).setStartColumnIndex(startCol)
                                            .setEndColumnIndex(endCol);
                                    // Then, merge some cells and give them a border

                                    requests.add(new Request().setMergeCells(
                                            new MergeCellsRequest().setRange(g).setMergeType("MERGE_ROWS")));

                                    requests.add(new Request().setUpdateBorders(new UpdateBordersRequest().setRange(g)
                                            .setLeft(new Border().setStyle("SOLID"))
                                            .setTop(new Border().setStyle("SOLID"))
                                            .setRight(new Border().setStyle("SOLID"))
                                            .setBottom(new Border().setStyle("SOLID"))));
                                }
                            }
                        }
                    }

                    if (requests.size() > 0) {
                        BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest().setRequests(requests);
                        service.spreadsheets().batchUpdate(spreadsheetId, body).execute();
                    }

                    service.spreadsheets().values().clear(spreadsheetId, "Party", new ClearValuesRequest()).execute();

                    BatchUpdateValuesRequest body = new BatchUpdateValuesRequest().setValueInputOption("RAW")
                            .setData(data);
                    service.spreadsheets().values().batchUpdate(spreadsheetId, body).execute();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private boolean hasSheet(Spreadsheet spreadSheet, String sheetName) {
                return spreadSheet.getSheets().stream().filter(s -> s.getProperties().getTitle().equals(sheetName))
                        .findAny().isPresent();
            }

            private List<List<Object>> makeRosterTable(SaveState state) {
                List<List<Object>> rows = new ArrayList<>();

                for (Hero h : state.getRoster().getHeroes()) {
                    rows.add(Arrays.asList(h.getID(), h.getName(), h.getStatus().toString(), h.getHeroClass(),
                            h.getXP(), h.getKills(), state.getCampaignLog().getHeroLevel(h.getID()), h.getQuirks()));
                }
                rows.sort((a, b) -> Integer.compare((Integer) a.get(0), (Integer) b.get(0)));
                rows.add(0, Arrays.asList("ID", "Name", "Status", "Class", "XP", "Kills", "Level", "Quirks"));
                return rows;
            }

            private void addSheetSetupRequests(List<Request> requests, int ID, String title) {
                requests.add(new Request().setAddSheet(new AddSheetRequest().setProperties(new SheetProperties()
                        .setTitle(title).setSheetId(ID).setGridProperties(new GridProperties().setFrozenRowCount(1)))));

                requests.add(new Request().setRepeatCell(new RepeatCellRequest()
                        .setRange(new GridRange().setSheetId(ID).setStartRowIndex(0).setEndRowIndex(1))
                        .setCell(new CellData().setUserEnteredFormat(
                                new CellFormat().setTextFormat(new TextFormat().setBold(Boolean.TRUE))))
                        .setFields("*")));
            }
        };

        scheduler.scheduleAtFixedRate(sheetUpdater, 3, 120, TimeUnit.SECONDS);
    }

}
