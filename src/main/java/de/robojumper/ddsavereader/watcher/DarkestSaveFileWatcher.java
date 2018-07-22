package de.robojumper.ddsavereader.watcher;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.ParseException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import de.robojumper.ddsavereader.file.DsonFile;
import de.robojumper.ddsavereader.file.DsonFile.UnhashBehavior;
import de.robojumper.ddsavereader.util.Helpers;

public class DarkestSaveFileWatcher implements Runnable {
    
    private BiConsumer<String, DsonParseResult> callback;
    private Path saveDir;
    private WatchService watcher;
    private WatchKey k;
    
    private volatile boolean wantsStop = false;
    private volatile boolean stopped = false;

    public DarkestSaveFileWatcher(BiConsumer<String, DsonParseResult> callback, String saveDir) throws IOException {
        this.callback = callback;
        this.saveDir =  Paths.get(saveDir);
    }

    /**
     * Watches a save directory for changes and updates the savestate model
     * 
     * @param state
     *            Save state model
     * @param saveDir
     *            Directory to the saves
     * @throws IOException
     *             If the directory cannot be watched
     */
    public void watchSaveFiles() throws IOException {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            k = saveDir.register(watcher, ENTRY_MODIFY, ENTRY_CREATE);
            new Thread(this).start();
        } catch (IOException e) {

        }
    }
    
    public void stop() {
        this.wantsStop = true;
    }
    
    public boolean isRunning() {
        return !stopped;
    }
    
    static void tryHandleFile(Path file, BiConsumer<String, DsonParseResult> callback, Path saveDir) {
        try {
            if (Files.isRegularFile(file) && Helpers.isSaveFileName(file.getFileName().toString()) && file.getParent().equals(saveDir)) {
                System.out.println("Reading " + file.getFileName().toString());
                // Open file with read option only to allow for file deletion and
                // modifications from other programs.
                try (InputStream is = Files.newInputStream(file, StandardOpenOption.READ)) {

                    BufferedInputStream stream = new BufferedInputStream(is);

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[1024];
                    while ((nRead = stream.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }

                    buffer.flush();
                    stream.close();
                    byte[] byteArray = buffer.toByteArray();
                    DsonParseResult result = null;
                    try {
                        // Don't unhash names as the Save State will do that
                        DsonFile f = new DsonFile(byteArray, UnhashBehavior.NONE);
                        String jsonString = f.toString() + "\n";
                        result = new DsonParseResult(jsonString, false);
                    } catch (ParseException e) {
                        result = new DsonParseResult(file.getFileName().toString() + ":" + e.getErrorOffset() + " - " + e.getMessage(), true);
                    } catch (Exception e) {
                    	result = new DsonParseResult(file.getFileName().toString() + ":" + e.getMessage(), true);
                    }
                    callback.accept(file.getFileName().toString(), result);
                } catch (NoSuchFileException e) {
                    System.err.println("Couldn't read/parse " + file.getFileName().toString());
                }
            } else {
                //System.err.println("Couldn't read/parse " + file.getFileName().toString());
            }
        } catch (Exception e) {
            System.err.println("Couldn't read/parse " + file.getFileName().toString());
        }
    }

    @Override
    public void run() {
        mainLoop();
        this.stopped = true;
    }
    
    private void mainLoop() {
        try (Stream<Path> paths = Files.walk(saveDir)) {
            paths.forEach(p -> tryHandleFile(p, callback, saveDir));
        } catch (IOException e) {
            e.printStackTrace();
        }
        while (!wantsStop) {
            // wait for key to be signaled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // This key is registered only
                // for ENTRY_MODIFY events,
                // but an OVERFLOW event can
                // occur regardless if events
                // are lost or discarded.
                if (kind == OVERFLOW) {
                    continue;
                }

                // The filename is the
                // context of the event.
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();

                // Resolve the filename against the directory.
                // If the filename is "test" and the directory is "foo",
                // the resolved name is "test/foo".
                Path child = saveDir.resolve(filename);
                tryHandleFile(child, callback, saveDir);
            }

            // Reset the key -- this step is critical if you want to
            // receive further watch events. If the key is no longer valid,
            // the directory is inaccessible so exit the loop.
            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
        try {
            k.cancel();
            watcher.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static class DsonParseResult {
        public final String data;   
        public final boolean encounteredError;
        
        
        public DsonParseResult(String data, boolean encounteredError) {
            this.data = data;
            this.encounteredError = encounteredError;
        }
    }
}
