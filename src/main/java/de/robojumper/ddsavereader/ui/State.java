package de.robojumper.ddsavereader.ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.swing.Icon;

import de.fuerstenau.buildconfig.BuildConfig;
import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;
import de.robojumper.ddsavereader.file.DsonTypes;
import de.robojumper.ddsavereader.file.DsonWriter;
import de.robojumper.ddsavereader.util.Helpers;
import de.robojumper.ddsavereader.util.ReadNames;

public class State {

    private static final File SETTINGS_FILE = new File(Helpers.DATA_DIR, "uisettings.properties");
    private static final File BACKUP_DIR = new File(Helpers.DATA_DIR, "/backups");

    public enum Status {
        OK(Resources.OK_ICON), WARNING(Resources.WARNING_ICON), ERROR(Resources.ERROR_ICON);

        public final Icon icon;

        Status(Icon icon) {
            this.icon = icon;
        }
    };

    public class SaveFile {
        boolean changed() {
            return !Objects.equals(contents, originalContents);
        }

        boolean canSave() {
            return canSave;
        }

        int[] getErrorLine() {
            int[] ret = new int[2];
            for (int i = Math.max(errorPos - 1, 0); i < contents.length(); i++) {
                if (contents.charAt(i) == '\n') {
                    ret[1] = i;
                    break;
                }
            }
            for (int i = Math.min(errorPos - 1, contents.length() - 1); i >= 0; i--) {
                if (contents.charAt(i) == '\n') {
                    ret[0] = i;
                    break;
                }
            }
            return ret;
        }

        String name;
        String contents;
        String originalContents;
        int errorPos;
        String errorReason;

        private boolean canSave = true;
    };

    private String saveDir = "", gameDir = "", modsDir = "";
    private String profileString;
    private Status saveStatus = Status.ERROR, gameStatus = Status.WARNING, modsStatus = Status.WARNING;

    private Map<String, SaveFile> files = new TreeMap<>();

    private Set<String> names = new HashSet<>();

    private String lastSheetID = "";

    public void init() {
        try {
            Properties prop = new Properties();
            if (!SETTINGS_FILE.exists()) {
                SETTINGS_FILE.getParentFile().mkdirs();
                SETTINGS_FILE.createNewFile();
            }
            prop.load(new FileInputStream(SETTINGS_FILE));
            setGameDir((String) prop.getOrDefault("gameDir", ""), false);
            setModsDir((String) prop.getOrDefault("modsDir", ""), true);
            setSaveDir((String) prop.getOrDefault("saveDir", ""));
            lastSheetID = (String) prop.getOrDefault("sheetId", "");
        } catch (IOException | ClassCastException e) {
            return;
        }
    }

    public void save() {
        try {
            Properties prop = new Properties();
            if (!SETTINGS_FILE.exists()) {
                SETTINGS_FILE.getParentFile().mkdirs();
                SETTINGS_FILE.createNewFile();
            }
            Helpers.hideDataDir();
            prop.setProperty("saveDir", saveDir);
            prop.setProperty("gameDir", gameDir);
            prop.setProperty("modsDir", modsDir);
            prop.setProperty("sheetId", lastSheetID);
            prop.store(new FileOutputStream(SETTINGS_FILE), BuildConfig.DISPLAY_NAME + "/" + BuildConfig.VERSION);
        } catch (IOException e) {
            return;
        }
    }

    public void setLastSheetID(String sheetID) {
        this.lastSheetID = sheetID;
    }

    public String getLastSheetID() {
        return lastSheetID;
    }

    public void setSaveDir(String dir) {
        if (!Objects.equals(dir, saveDir)) {
            this.saveDir = dir;
            if (new File(saveDir).exists() && saveDir.matches(".*profile_[0-9]*/?")) {
                profileString = Paths.get(saveDir).toFile().getName();
                new File(BACKUP_DIR, profileString).mkdirs();
                saveStatus = Status.OK;
                loadFiles();
            } else {
                saveStatus = Status.ERROR;
                profileString = null;
            }
        }
    }

    public void loadFiles() {
        File dir = new File(saveDir);
        files.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        System.out.println(fmt.format(new Date()) + " Start Loading Files");
        Arrays.stream(dir.listFiles()).parallel().forEach(f -> {
            if (Helpers.isSaveFileName(f.getName())) {
                String content;
                try {
                    content = new DsonFile(Files.readAllBytes(f.toPath()), UnhashBehavior.POUNDUNHASH).toString()
                            + "\n";
                } catch (Exception e) {
                    content = "Error reading: " + e.getMessage();
                }
                SaveFile file = new SaveFile();
                file.name = f.getName();
                file.contents = content;
                file.originalContents = content;
                synchronized (files) {
                    files.put(f.getName(), file);
                }
            }
        });
        System.out.println(fmt.format(new Date()) + " End Loading Files");
    }

    public void saveChanges() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        System.out.println(fmt.format(new Date()) + " Start Saving Files");
        files.entrySet().parallelStream().filter(f -> f.getValue().changed() && f.getValue().canSave()).forEach(f -> {
            try {
                Files.write(Paths.get(saveDir, f.getKey()), new DsonWriter(f.getValue().contents).bytes(),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParseException e) {
                System.err.println("ERROR!!! canSave() returns true but saving fails for " + f.getKey());
                e.printStackTrace();
            }
        });
        System.out.println(fmt.format(new Date()) + " End Saving Files");
    }

    public void setGameDir(String dir) {
        setGameDir(dir, true);
    }

    protected void setGameDir(String dir, boolean rescan) {
        if (!Objects.equals(dir, gameDir)) {
            this.gameDir = dir;
            if (rescan) {
                rescanNames();
            }
        }
    }

    public void setModsDir(String dir) {
        setModsDir(dir, true);
    }

    protected void setModsDir(String dir, boolean rescan) {
        if (!Objects.equals(dir, modsDir)) {
            this.modsDir = dir;
            if (rescan) {
                rescanNames();
            }
        }
    }

    public void changeFile(String file, String contents) {
        if (!files.containsKey(file)) {
            throw new RuntimeException();
        }
        SaveFile f = files.get(file);
        f.contents = contents;
        if (f.changed()) {
            try {
                new DsonWriter(f.contents).bytes();
                f.canSave = true;
            } catch (Exception e) {
                if (e instanceof ParseException) {
                    f.errorPos = ((ParseException) e).getErrorOffset();
                }
                f.errorReason = e.getMessage().split("\n")[0];
                f.canSave = false;
            }
        } else {
            f.canSave = true;
        }
    }

    public boolean canSave() {
        return files.values().stream().filter(s -> s.changed() && !s.canSave()).count() == 0
                && files.values().stream().filter(s -> s.changed()).count() > 0;
    }

    private void rescanNames() {
        ArrayList<String> paths = new ArrayList<>();
        if (!gameDir.equals("")) {
            if (new File(gameDir, "svn_revision.txt").exists()) {
                gameStatus = Status.OK;
                paths.add(gameDir);
            }
        }

        if (!modsDir.equals("")) {
            if (Paths.get(modsDir).endsWith("262060")) {
                modsStatus = Status.OK;
                paths.add(modsDir);
            }
        }
        System.out.println("Reading Names...");
        this.names = ReadNames.collectNames(paths);
        DsonTypes.NAME_TABLE.clear();
        DsonTypes.offerNames(names);
        System.out.println("Done");
    }

    public int getNumUnsavedChanges() {
        return (int) files.values().stream().filter(f -> f.changed()).count();
    }

    public Status getSaveStatus() {
        return saveStatus;
    }

    public Status getGameStatus() {
        return gameStatus;
    }

    public Status getModsStatus() {
        return modsStatus;
    }

    public String getSaveDir() {
        return saveDir;
    }

    public String getGameDir() {
        return gameDir;
    }

    public String getModsDir() {
        return modsDir;
    }

    public Collection<SaveFile> getSaveFiles() {
        return files.values();
    }

    public SaveFile getSaveFile(String fileName) {
        if (!files.containsKey(fileName)) {
            throw new RuntimeException();
        }
        return files.get(fileName);
    }

    public boolean hasBackup(String name) {
        return Paths.get(BACKUP_DIR.getAbsolutePath(), profileString, name + ".zip").toFile().exists();
    }

    public boolean hasAnyBackups() {
        if (profileString != null) {
            return new File(BACKUP_DIR, profileString).list((dir, name) -> name.endsWith(".zip")).length > 0;
        }
        return false;
    }

    public boolean makeBackup(String name) {
        // Most simple Box type I could find
        AtomicBoolean success = new AtomicBoolean(true);
        try (FileOutputStream fos = new FileOutputStream(
                Paths.get(BACKUP_DIR.getAbsolutePath(), profileString, name + ".zip").toFile());
                ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {

            Arrays.stream(new File(saveDir).listFiles()).forEach(f -> {
                if (Helpers.isSaveFileName(f.getName())) {
                    try {
                        ZipEntry z = new ZipEntry(f.getName());
                        zos.putNextEntry(z);
                        zos.write(Files.readAllBytes(f.toPath()));
                        zos.closeEntry();
                    } catch (IOException e) {
                        e.printStackTrace();
                        success.set(false);
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            success.set(false);
        }
        return success.get();
    }

    public Collection<String> getBackupNames() {
        return Arrays.stream(new File(BACKUP_DIR, profileString).listFiles())
                .filter((f) -> f.getName().endsWith(".zip"))
                .sorted((a, b) -> (int) (b.lastModified() - a.lastModified()))
                .map(s -> s.getName().replaceAll("\\.zip", "")).collect(Collectors.toList());
    }

    public void restoreBackup(String result) {
        File f = Paths.get(BACKUP_DIR.getAbsolutePath(), profileString, result + ".zip").toFile();
        System.out.println(f);
        try (ZipFile zipFile = new ZipFile(f)) {

            clear(new File(saveDir), false);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    InputStream stream = zipFile.getInputStream(entry);
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = stream.read(bytes)) >= 0) {
                        os.write(bytes, 0, length);
                    }
                    Files.write(Paths.get(saveDir, entry.getName()), os.toByteArray(), StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    public String getBackupPath() {
        if (profileString != null) {
            return Paths.get(BACKUP_DIR.getAbsolutePath(), profileString).toString();
        } else {
            return BACKUP_DIR.getAbsolutePath();
        }
    }

    private void clear(File f, boolean deleteSelf) {
        if (f.exists()) {
            if (f.isDirectory()) {
                String[] strFiles = f.list();

                for (String strFilename : strFiles) {
                    File fileToDelete = new File(f, strFilename);
                    clear(fileToDelete);
                }
                if (f.list().length == 0 && deleteSelf) {
                    f.delete();
                }

            } else {
                f.delete();
            }
        }
    }

    private void clear(File f) {
        clear(f, true);
    }
}
