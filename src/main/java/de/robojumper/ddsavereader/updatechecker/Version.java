package de.robojumper.ddsavereader.updatechecker;

import java.util.regex.Pattern;

public final class Version implements Comparable<Version> {

    private String compareString;
    private boolean withSuffix;
    private String displayString;

    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");

    public Version(String version) {
        this.displayString = version;
        if (version.charAt(0) == 'v') {
            version = version.substring(1);
        }
        int i;
        if ((i = version.indexOf("-")) >= 0) {
            version = version.substring(0, i);
            withSuffix = true;
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Version doesn't match known pattern " + version);
        }
        this.compareString = version;
    }

    @Override
    public int compareTo(Version that) {
        if (that == null) {
            return 1; 
        }
        int result = compareRawVersion(that);
        if (this.withSuffix != that.withSuffix) {
            if (result >= 0 && this.withSuffix) {
                result = -1;
            } else if (result <= 0 && that.withSuffix) {
                result = 1;
            }
        }
        return result;
        
    }
    
    private int compareRawVersion(Version that) {
        String[] thisParts = this.compareString.split("\\.");
        String[] thatParts = that.compareString.split("\\.");
        int length = Math.max(thisParts.length, thatParts.length);
        for (int i = 0; i < length; i++) {
            int thisPart = i < thisParts.length ? Integer.parseInt(thisParts[i]) : 0;
            int thatPart = i < thatParts.length ? Integer.parseInt(thatParts[i]) : 0;
            if (thisPart < thatPart)
                return -1;
            if (thisPart > thatPart)
                return 1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that)
            return true;
        if (that == null)
            return false;
        if (this.getClass() != that.getClass())
            return false;
        return this.compareTo((Version) that) == 0;
    }

    @Override
    public String toString() {
        return displayString;
    }

}
