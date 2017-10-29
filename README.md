# Darkest Dungeon Save Reader
DSON (Darkest JSON) to JSON converter.

There are still a few unknown variables in the file, in the code they're named with greek letters.


## Usage

    java -jar DDSaveReader.jar [-d] -o <outputfile> <inputfile>

`-d` dumps all metadata without known purpose as comments into the JSON file at the appropriate place.
This might come in handy when trying to find a pattern in them.

## Download

[Releases Page](https://github.com/robojumper/DarkestDungeonSaveReader/releases/)

## Plans

* Figure out unknown variables
* Expand to edit / save functionality
