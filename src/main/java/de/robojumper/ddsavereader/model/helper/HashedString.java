package de.robojumper.ddsavereader.model.helper;

import java.io.IOException;
import java.util.Objects;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import de.robojumper.ddsavereader.file.DsonFile;

/**
 * Class that can hold both string and int. Relevant for deserialized
 * objects that can contain hashed strings as ints, but may fail to
 * resolve some hashes.
 * @author robojumper
 *
 */
public class HashedString {
    
    private final int hashValue;
    private final String str;
    
    public HashedString(int i) {
        this.str = null;
        hashValue = i;
    }
    
    public HashedString(String str) {
        this.str = str;
        this.hashValue = DsonFile.stringHash(str);
    }

    public static class HashedStringAdapter extends TypeAdapter<HashedString> {

        @Override
        public HashedString read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NUMBER) {
                return new HashedString(in.nextInt());
            } else if (in.peek() == JsonToken.STRING) {
                return new HashedString(in.nextString());
            }
            throw new JsonParseException("Value for HashedString must be an Integer or a String");
        }

        @Override
        public void write(JsonWriter out, HashedString in) throws IOException {
            if (in.str != null) {
                out.value(in.str);
            } else {
                out.value(in.hashValue);
            }
            
        }
    }
        
    public String getStringValue() {
        return this.str;
    }
    
    public int getIntValue() {
        return this.hashValue;
    }
    
    @Override
    public String toString() {
        return this.str != null ? this.str : Integer.toString(this.hashValue);
    }
    
    @Override
    public int hashCode() {
        return this.hashValue; 
    }
    
    @Override
    public boolean equals(Object other) {
        if (other != null && other instanceof HashedString) {
            HashedString s = (HashedString) other;
            if (this.str == null && s.str == null) {
                return this.hashValue == s.hashValue;
            } else {
                return Objects.equals(this.str, s.str);
            }
        }
        return false;
    }
}
