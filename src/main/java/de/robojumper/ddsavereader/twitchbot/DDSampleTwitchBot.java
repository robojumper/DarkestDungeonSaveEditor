package de.robojumper.ddsavereader.twitchbot;

/*import java.io.IOException;
import me.philippheuer.twitch4j.TwitchClient;
import me.philippheuer.twitch4j.TwitchClientBuilder;
import me.philippheuer.twitch4j.events.Event;
import me.philippheuer.twitch4j.events.IListener;
import me.philippheuer.twitch4j.events.event.irc.ChannelMessageEvent;
import de.robojumper.ddsavereader.model.SaveState;
import de.robojumper.ddsavereader.watcher.DarkestSaveFileWatcher;*/

public class DDSampleTwitchBot {

    public static void main(String[] args) {
/*        TwitchClient twitchClient;
        @SuppressWarnings("unused")
        DarkestSaveFileWatcher watcher;
        SaveState saveState = new SaveState();
        try {
            // Load Name File!!!
            watcher = new DarkestSaveFileWatcher(saveState, System.getenv("DDSAVEDIR"));
            twitchClient = TwitchClientBuilder.init()
                    .withClientId(System.getenv("DDCLIENTID"))
                    .withClientSecret(System.getenv("DDCLIENTSECRET"))
                    .withCredential(System.getenv("DDTOKEN")).connect();
            twitchClient.getChannelEndpoint(System.getenv("DDCHANNEL")).registerEventListener();
            twitchClient.getDispatcher().registerListener(new IListener<Event>() {

                @Override
                public void handle(Event event) {
                    if (event instanceof ChannelMessageEvent) {
                        ChannelMessageEvent message = (ChannelMessageEvent)event;
                        String content = message.getMessage().trim();
                        String response = Commands.buildResponse(saveState, content);
                        
                        if (response != null && response.length() > 0) {
                            event.getClient().getMessageInterface().sendMessage(message.getChannel().getName(), response);
                        }
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }
}
