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
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.swing.Icon;
import javax.swing.SwingWorker;

import de.robojumper.ddsavereader.BuildConfig;
import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;
import de.robojumper.ddsavereader.file.DsonWriter;
import de.robojumper.ddsavereader.util.Helpers;

/* The UI State class. This class is not internally synchronized, and any 
 * access must happen on the EDT (swing event dispatch thread).
 */
public class State {

    private static final File SETTINGS_FILE = new File(Helpers.DATA_DIR, "uisettings.properties");
    private static final File BACKUP_DIR = new File(Helpers.DATA_DIR, "/backups");

    public enum Status {
        OK(Resources.OK_ICON), WARNING(Resources.WARNING_ICON), ERROR(Resources.ERROR_ICON),
        PENDING(Resources.WARNING_ICON);

        public final Icon icon;

        Status(Icon icon) {
            this.icon = icon;
        }
    };

    public enum Saveability {
        YES, NO, PENDING,
    }

    public class SaveFile {
        boolean changed() {
            return !Objects.equals(contents, originalContents);
        }

        boolean canSave() {
            return saveability == Saveability.YES;
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

        SwingWorker<CheckResult, Object> worker;

        private Saveability saveability = Saveability.YES;
    };

    private String saveDir = "", gameDir = "", modsDir = "";
    private String profileString;
    private Status saveStatus = Status.ERROR, gameStatus = Status.WARNING, modsStatus = Status.WARNING;
    private boolean sawGameDataPopup;

    private Map<String, SaveFile> files = new TreeMap<>();

    private String lastSheetID = "";

    private Consumer<String> saveStatusChangeCB;

    public void init(Consumer<String> saveStatusChangeCB) {
        try {
            Properties prop = new Properties();
            if (!SETTINGS_FILE.exists()) {
                SETTINGS_FILE.getParentFile().mkdirs();
                SETTINGS_FILE.createNewFile();
            }
            prop.load(new FileInputStream(SETTINGS_FILE));
            setGameDir((String) prop.getOrDefault("gameDir", ""));
            setModsDir((String) prop.getOrDefault("modsDir", ""));
            setSaveDir((String) prop.getOrDefault("saveDir", ""));
            lastSheetID = (String) prop.getOrDefault("sheetId", "");
            sawGameDataPopup = Boolean.parseBoolean((String) prop.getOrDefault("sawGameDataPopup", ""));
            this.saveStatusChangeCB = saveStatusChangeCB;
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
            prop.setProperty("sawGameDataPopup", ((Boolean) sawGameDataPopup).toString());
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
            if (new File(saveDir).exists()) {
                profileString = Paths.get(saveDir).toFile().getName();
                new File(BACKUP_DIR, profileString).mkdirs();
                saveStatus = saveDir.matches(".*profile_[0-9]*/?") ? Status.OK : Status.WARNING;
                loadFiles();
            } else {
                saveStatus = Status.ERROR;
                profileString = null;
            }
        }
    }

    public void loadFiles() {
        files.values().stream().forEach(s -> {
            if (s.worker != null) {
                SwingWorker<CheckResult, Object> w = s.worker;
                s.worker = null;
                w.cancel(true);
            }
        });
        files.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        System.err.println(fmt.format(new Date()) + " Start Loading Files");
        File dir = new File(saveDir);
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
        System.err.println(fmt.format(new Date()) + " End Loading Files");
    }

    public void saveChanges() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        System.err.println(fmt.format(new Date()) + " Start Saving Files");
        files.entrySet().parallelStream().filter(f -> f.getValue().changed() && f.getValue().canSave()).forEach(f -> {
            try {
                Files.write(Paths.get(saveDir, f.getKey()), new DsonWriter(f.getValue().contents).bytes(),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParseException e) {
                System.err.println("ERROR!!! canSave() returns true but saving fails for " + f.getKey());
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        System.err.println(fmt.format(new Date()) + " End Saving Files");
    }

    public void setGameDir(String dir) {
        if (!Objects.equals(dir, gameDir)) {
            this.gameDir = dir;
        }
    }

    public void setModsDir(String dir) {
        if (!Objects.equals(dir, modsDir)) {
            this.modsDir = dir;
        }
    }

    public void changeFile(String file, String contents) {
        if (!files.containsKey(file)) {
            throw new RuntimeException();
        }
        SaveFile f = files.get(file);
        /*
        * if (f.contents.equals(contents)) { return; }
        */

        f.contents = contents;
        if (f.worker != null) {
            SwingWorker<CheckResult, Object> w = f.worker;
            f.worker = null;
            w.cancel(true);
        }
        if (f.changed()) {
            f.worker = new CheckInBackground(file, f.contents);
            f.worker.execute();
            f.saveability = Saveability.PENDING;
        } else {
            f.saveability = Saveability.YES;
        }
        f.errorPos = 0;
        f.errorReason = "";
    }

    static class CheckResult {
        String file;
        boolean success;
        int errorPos;
        String errorReason;

        public CheckResult(String f, boolean s, int p, String r) {
            this.file = f;
            this.success = s;
            this.errorPos = p;
            this.errorReason = r;
        }
    }

    class CheckInBackground extends SwingWorker<CheckResult, Object> {
        String contents, file;

        public CheckInBackground(String f, String c) {
            this.file = f;
            this.contents = c;
        }

        private CheckResult check() {
            try {
                new DsonWriter(contents).bytes();
                return new CheckResult(file, true, 0, "");
            } catch (InterruptedException e) {
                return null;
            } catch (Exception e) {
                int errorPos = 0;
                if (e instanceof ParseException) {
                    errorPos = ((ParseException) e).getErrorOffset();
                }
                return new CheckResult(file, false, errorPos, e.getMessage().split("\n")[0]);
            }
        }

        @Override
        public CheckResult doInBackground() {
            return check();
        }

        @Override
        protected void done() {
            /*
            * There are two ways for `done` to be called:
            * 1) The computation completes, and Swing enqueues the `done` call onto the event dispatch queue.
            *    Thus, the we are on the EDT now and there are no synchronization issues.
            * 2) The `cancel` method is called. This can immediately call `done`. As a result,
            *    `cancel` must only be called on the EDT. This corresponds to the requirement that
            *    users of this class must only access this on the EDT.
            */
            CheckResult result = null;
            try {
                result = get();
            } catch (InterruptedException | ExecutionException | CancellationException e) {
            }
            if (result == null)
                return;

            SaveFile file = files.get(result.file);
            if (file == null || file.worker != this)
                return;

            file.worker = null;
            if (result.success) {
                file.saveability = Saveability.YES;
            } else {
                file.saveability = Saveability.NO;
                file.errorPos = result.errorPos;
                file.errorReason = result.errorReason;
            }

            saveStatusChangeCB.accept(result.file);
        }
    }

    public boolean canSave() {
        return files.values().stream().filter(s -> s.changed() && !s.canSave()).count() == 0 && anyChanges();
    }

    public boolean isBusy() {
        return files.values().stream().anyMatch(s -> s.saveability == Saveability.PENDING);
    }

    public boolean anyChanges() {
        return files.values().stream().filter(s -> s.changed()).count() > 0;
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

    public boolean sawGameDataPopup() {
        return sawGameDataPopup;
    }

    public void setSawGameDataPopup(boolean sawGameDataPopup) {
        this.sawGameDataPopup = sawGameDataPopup;
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
        System.err.println(f);
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
            System.err.println(e.getMessage());
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
