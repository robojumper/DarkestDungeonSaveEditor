# Darkest Dungeon Save Reader
DSON (Darkest JSON) to JSON converter.

There are still a few unknown variables in the file.

## Motivation & Fundamentals

Darkest Dungeon uses a proprietary save fomat. The files have a `.json` extension, but are actually binary files with four distinct blocks. The first block is a header with metainformation about the whole file. The second block contains information about all objects in the file. The third block contains information about all fields in the file, and the fourth block is the actual data with field names and field data.

While the general structure of the format resembles JSON, there are subtle differences. For one, there are types in the binary data that don't exist in JSON in the same way. A very simple example is `int` vs. `float`: The game doesn't include any information to distinguish between the two, both are 4-byte sized data. There are also some more exotic types. Hence, the application needs to include hardcoded information about some types (see `DsonTypes.java`).

A full documentation of the format can be found in [docs/dson.md](docs/dson.md).


## Decoding

    java -jar DDSaveReader.jar [--debug, -d] [--names, -n <namefile>] [--output, -o <outfile>] filename

`-d` dumps all metadata without known purpose as comments into the JSON file at the appropriate place.
This might come in handy when trying to find a pattern in them. With `-d`, the file is not valid JSON, but should be after removing all comments. Files translated without the `-d` flag should be valid JSON.
`-n` provides a Name File, a newline separated list of strings that are recognized as hashed values.

## Name Files

Darkest Dungeon hashes some strings using their own hash algorithm, which can make reading some files rather complicated for you. Whenever an integer is recognized as the hash of a given string, it's replaced with that String instead.
When combined with the `-d` flag, the hashed integers are added as comments.

A list can be compiled by running

    java -cp DDSaveReader.jar de.robojumper.ddsavereader.util.ReadNames [dir1] [dir2] [dir3] [...]

`dir1`, ... are directories that contain Darkest Dungeon game data. These are usually the game root directory, but can also be mods.  
There is no output file parameter, just pipe it to a file (append ` > names.txt`).

## Saving

There also is an experimental save writer:

    java -cp DDSaveReader.jar de.robojumper.ddsavereader.file.Json2Dson [--output, -o outfile] filename
    
The input file must be a save file decoded **without a name file** and **without the debug parameter**. Providing a name file changes the JSON structure and replaces some integers with strings, which breaks the game compatibility.

While the JSON exported by the decoder is valid JSON without any nonstandard extensions, the application still only de-/serializes Darkest Dungeon save files correctly. There are no guarantees that any additions that don't resemble the Darkest Dungeon data will serialize correctly.   

## Save File Model & Spreadsheets

The Application includes a save file watcher that automatically watches for changes to the save file and updates its internal save file model. This can be used to (for example) add a Twitch bot that can respond to queries by viewers, or upload data to spreadsheets.

This application includes a service that uploads some save file data to a Google Spreadsheet. If you have created a custom app at the Google Developer console and enabled the Spreadsheets API with an OAuth 2.0 Key, you can launch it by creating a file `client_secret.json` with the following content in the same directory as the jar file:

    {
    	"installed": {
    		"client_id": "clientid",
    		"client_secret": "clientsecret"
    	}
    }

And launch it via

    java -cp DDSaveReader.jar de.robojumper.ddsavereader.spreadsheets.SpreadsheetsService SpreadsheetID SaveDir NameList
	


## Building

The application uses Gradle to build. You can build a complete jar file using `gradlew fatJar`. The jar file can be found as `build/libs/DDSaveReader.jar`.
If you are using the spreadsheets service, you can instead add the `client_secret.json` to `src/main/resources` and build with `gradlew fatJar -PincludeSecret`. This will include the id and secret in the jar file so you don't need to add a separate `client_secret.json` to the file system, just make sure you don't accidentally give this jar to anyone else as this would incur the risk of API Key abuse.

## Download

[Releases Page](https://github.com/robojumper/DarkestDungeonSaveReader/releases/)

## Plans

* Figure out unknown variables

## Attribution

This application uses the [Google GSON Library](https://github.com/google/gson) 2.8.5, licensed under the [Apache License 2.0](Licenses/Apachev2.0.txt).

This application uses the [Google API Client Libraries](https://github.com/google/google-api-java-client) 1.23.0, licensed under the [Apache License 2.0](Licenses/Apachev2.0.txt).

## License

This application is licensed under the [MIT License](LICENSE).
