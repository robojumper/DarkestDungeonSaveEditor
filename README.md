# Darkest Dungeon Save Reader
DSON (Darkest JSON) to JSON converter.

There are still a few unknown variables in the file, in the code they're named with greek letters.


## Usage

    java -jar DDSaveReader.jar [-d] [-n, --names namefile] -o <outputfile> <inputfile>

`-d` dumps all metadata without known purpose as comments into the JSON file at the appropriate place.
This might come in handy when trying to find a pattern in them.

`-n` provides a Name File, a newline separated list of strings that are recognized as hashed values.
Darkest Dungeon hashes some strings using their own hash algorithm, which can make reading some files rather complicated for you.

A list can be compiled by running

    java -cp DDSaveReader.jar de.robojumper.ddsavereader.util.ReadNames [dir1] [dir2] [dir3] [...]

`dir1`, ... are directories that contain Darkest Dungeon game data. These are usually the game root directory, but can also be mods.  
There is no output file parameter, just pipe it to a file (append ` > names.txt`).

## Download

[Releases Page](https://github.com/robojumper/DarkestDungeonSaveReader/releases/)

## Plans

* Figure out unknown variables
* Expand to edit / save functionality

## Attribution

This application uses the [Google GSON Library](https://github.com/google/gson) 2.8.2, licensed under the [Apache License 2.0](https://github.com/robojumper/DarkestDungeonSaveReader/blob/master/Licenses/APACHEv2.0.txt).
