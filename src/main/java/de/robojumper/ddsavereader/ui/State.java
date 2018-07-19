package de.robojumper.ddsavereader.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class State {

    public enum Status {
        OK, WARNING, ERROR
    };
    
    private File saveDir, gameDir, modsDir = null;

    private List<Map.Entry<String, String>> files = new ArrayList<>();
    private boolean hasUnsavedChanges = false;

    private Set<String> names = new HashSet<>();

    public void setSaveDir(File dir) {
    }

    public void setGameDir(File dir) {
        if (!Objects.equals(dir, gameDir)) {
            this.gameDir = dir;
            rescanFiles();
        }
    }

    public void setModsDir(File dir) {
        if (!Objects.equals(dir, modsDir)) {
            this.modsDir = dir;
            rescanFiles();
        }
    }
    
    private void rescanFiles() {
        // TODO Auto-generated method stub
        
    }

    public boolean getHasUnsavedChanges() {
        return this.hasUnsavedChanges;
    }
}
