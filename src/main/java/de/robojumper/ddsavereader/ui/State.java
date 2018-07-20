package de.robojumper.ddsavereader.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.Icon;

import de.fuerstenau.buildconfig.BuildConfig;
import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;
import de.robojumper.ddsavereader.file.DsonTypes;
import de.robojumper.ddsavereader.file.DsonWriter;
import de.robojumper.ddsavereader.util.Helpers;
import de.robojumper.ddsavereader.util.ReadNames;

public class State {

    private static final File SETTINGS_FILE = new File(System.getProperty("user.home"),
            ".store/ddsavereader/uisettings.properties");

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

        String name;
        String contents;
        String originalContents;

        private boolean canSave = true;
    };

    private String saveDir = "", gameDir = "", modsDir = "";
    private Status saveStatus = Status.ERROR, gameStatus = Status.WARNING, modsStatus = Status.WARNING;

    private Map<String, SaveFile> files = new LinkedHashMap<>();

    private Set<String> names = new HashSet<>();

    private Consumer<Void> fileSaveStatusReceiver = s -> {
    };

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
            prop.setProperty("saveDir", saveDir);
            prop.setProperty("gameDir", gameDir);
            prop.setProperty("modsDir", modsDir);
            prop.store(new FileOutputStream(SETTINGS_FILE), BuildConfig.DISPLAY_NAME + "/" + BuildConfig.VERSION);
        } catch (IOException e) {
            return;
        }
    }

    public void setSaveDir(String dir) {
        if (!Objects.equals(dir, saveDir)) {
            this.saveDir = dir;
            if (new File(saveDir, "backup").exists()) {
                saveStatus = Status.OK;
                loadFiles();
            } else {
                saveStatus = Status.ERROR;
            }
        }
    }

    public void setFileSaveStatusReceiver(Consumer<Void> c) {
        fileSaveStatusReceiver = c;
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
                    content = new DsonFile(Files.readAllBytes(f.toPath()), UnhashBehavior.POUNDUNHASH).toString();
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
                System.out.println(e.getMessage());
                f.canSave = false;
            }
        } else {
            f.canSave = true;
        }
        fileSaveStatusReceiver.accept(null);
    }

    public boolean canSave() {
        return files.values().stream().filter(s -> s.changed() && !s.canSave()).count() == 0;
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

    public Collection<SaveFile> saveFiles() {
        return files.values();
    }

    public SaveFile getSaveFile(String fileName) {
        if (!files.containsKey(fileName)) {
            throw new RuntimeException();
        }
        return files.get(fileName);
    }

}
