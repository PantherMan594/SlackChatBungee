package net.cubexmc.SlackChatBungee;

import com.google.common.base.Joiner;
import com.pantherman594.gssentials.PlayerData;
import com.pantherman594.gssentials.event.GlobalChatEvent;
import com.pantherman594.gssentials.event.StaffChatEvent;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class Main extends Plugin implements Listener {

    private static ServerSocket serverSocket;
    private static DefaultHttpServerConnection conn;
    private HttpParams params = new BasicHttpParams();
    private HttpRequest request;
    private Configuration config;

    @Override
    public void onEnable() {
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        try {
            int port = 25464;
            serverSocket = new ServerSocket(port);
            getLogger().log(Level.INFO, "[SlackChat] Connected to port " + port);
        } catch (Exception e) {
            e.printStackTrace();
        }
        conn = new DefaultHttpServerConnection();
        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdir()) {
                getLogger().warning("Unable to create config folder!");
            }
        }
        File f = new File(getDataFolder(), "config.yml");
        try {
            if (f.exists()) {
                f.delete();
            }
            Files.copy(getResourceAsStream("config.yml"), f.toPath());
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
        } catch (IOException e) {
            e.printStackTrace();
        }
        ProxyServer.getInstance().getScheduler().schedule(this, () -> {
            while (!serverSocket.isClosed()) {
                try {
                    conn.bind(serverSocket.accept(), params);
                    request = conn.receiveRequestHeader();
                    conn.receiveRequestEntity((HttpEntityEnclosingRequest) request);
                    HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                    String data = EntityUtils.toString(entity);
                    String[] tokens = data.split("&");
                    String result = "Got it";
                    if (tokens[7].contains("command=%2Fsay")) {
                        String channel = tokens[4].replace("channel_name=", "");
                        String user = tokens[6].replace("user_name=", "");
                        String message = decodeMessage(tokens[8]);
                        if (message.startsWith("(a) ")) {
                            postPayload(message.substring(4), "Anonymous", "staffchat", false);
                        } else {
                            boolean found = true;
                            switch (channel) {
                                case "staffchat":
                                    ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("SLACK", user, message));
                                    break;
                                case "globalchat":
                                    ProxyServer.getInstance().getPluginManager().callEvent(new GlobalChatEvent("SLACK", user, message));
                                    break;
                                case "privategroup":
                                    String channelId = tokens[3].replace("channel_id=", "");
                                    channel = config.getString(channelId + ".id");
                                default:
                                    found = false;
                                    for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
                                        if (info.getName().equalsIgnoreCase(channel)) {
                                            broadcastServer(message, user, info.getName());
                                            postPayload(message, user, info.getName());
                                            found = true;
                                            break;
                                        }
                                    }
                                    break;
                            }
                            if (!found) {
                                ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("SLACK", user, message));
                            }
                            getLogger().log(Level.INFO, "[SLACK - " + channel + "] " + formatMsg(message, user));
                        }
                        result = "";
                    } else if (tokens[7].contains("command=%2Frun") && config.getString(tokens[6].replace("user_name=", "") + ".tag") != null && config.getString(tokens[6].replace("user_name=", "") + ".tag").contains("Owner")) {
                        ProxyServer.getInstance().getPluginManager().dispatchCommand(ProxyServer.getInstance().getConsole(), decodeMessage(tokens[8]));
                        postPayload(decodeMessage("/" + tokens[8]), tokens[6].replace("user_name=", ""), "staffchat");
                        result = "Ran command /" + decodeMessage(tokens[8]);
                    } else if (tokens[7].contains("command=%2Flog") && !config.getString(tokens[6].replace("user_name=", "") + ".tag").equals("") && config.getString(tokens[6].replace("user_name=", "") + ".tag").contains("Owner")) {
                        int lines = isInteger(decodeMessage(tokens[8]), 10) ? Integer.valueOf(decodeMessage(tokens[8])) : 10;
                        result = tail(new File(ProxyServer.getInstance().getPluginsFolder().getParent(), "proxy.log.0"), lines);
                    } else if (tokens[7].contains("command=%2Flist")) {
                        result = getList();
                    }
                    HttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
                    if (!result.equals("")) {
                        response.setEntity(new StringEntity(result));
                    }
                    conn.sendResponseHeader(response);
                    conn.sendResponseEntity(response);
                    conn.close();
                    getLogger().log(Level.CONFIG, Arrays.toString(tokens));
                } catch (Exception e) {
                    try {
                        e.printStackTrace();
                        if (conn.isOpen()) {
                            conn.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }, 0, TimeUnit.SECONDS);
        scheduleVote(false);
    }

    @EventHandler
    public void onStaffChat(StaffChatEvent event) {
        if (!event.getServer().equals("VOTE")) {
            if (!(event.getMessage().contains("is suspected for") || event.getMessage().contains("may be hacking ("))) {
                postPayload(event.getMessage(), event.getSender(), "staffchat");
            }
        } else {
            postPayload(event.getMessage(), event.getSender(), "staffchat", false);
        }
    }

    @EventHandler
    public void onGlobalChat(GlobalChatEvent event) {
        postPayload(event.getMessage(), event.getSender(), "globalchat");
    }

    @EventHandler
    public void onPlayerJoin(PostLoginEvent event) {
        postPayload("_joined the game_", event.getPlayer().getName(), "globalchat");
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        postPayload("_left the game_", event.getPlayer().getName(), "globalchat");
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        postPayload("_switched to " + event.getPlayer().getServer().getInfo().getName() + "_", event.getPlayer().getName(), "globalchat");
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
        postPayload(event.getMessage(), sender.getName(), sender.getServer().getInfo().getName());
    }

    @Override
    public void onDisable() {
        try {
            serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scheduleVote(final boolean done) {
        ProxyServer.getInstance().getScheduler().schedule(this, () -> {
            if (java.time.LocalTime.now().getHour() == 19 && java.time.LocalTime.now().getMinute() == 0) {
                if (!done) {
                    ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("VOTE", "Vote", "@everyone Vote now at http://cubexmc.net/?a=vote!"));
                }
                scheduleVote(true);
            } else {
                scheduleVote(false);
            }
        }, 15, TimeUnit.SECONDS);
    }

    private String getList() {
        String players = "";
        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
            players += "\n" + getPlayerList(info.getName());
        }
        return players;
    }

    private String getPlayerList(String serverName) {
        boolean playerNames = true;
        String names = "";
        int num = 0;
        ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
        try {
            Socket s = new Socket();
            s.connect(info.getAddress());
            s.close();
        } catch (IOException e) {
            return serverName + ": Offline";
        }
        if (!info.getPlayers().isEmpty()) {
            for (ProxiedPlayer p : info.getPlayers()) {
                if (names.equals("")) {
                    names += p.getName();
                } else {
                    names += ", " + p.getName();
                }
                if (PlayerData.getData(p.getUniqueId()).isHidden()) {
                    names += "[Hidden]";
                }
                num++;
            }
        } else {
            playerNames = false;
        }
        if (playerNames) {
            return serverName + " " + "(" + num + ")" + ": " +  names;
        } else {
            return serverName + ": " + num;
        }
    }

    private String formatMsg(String msg, String player) {
        return ChatColor.GRAY + "[S] " + ChatColor.translateAlternateColorCodes('&', config.getString(player.toLowerCase() + ".tag")) + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + msg;
    }

    private void broadcastServer(String msg, String player, String serverName) {
        for (ProxiedPlayer p : ProxyServer.getInstance().getServerInfo(serverName).getPlayers()) {
            p.sendMessage(formatMsg(msg, player));
        }
    }

    private void postPayload(String msg, String player, String serverName) {
        postPayload(msg, player, serverName, true);
    }

    private void postPayload(String msg, String player, String serverName, boolean icon) {
        if (msg.contains(" ")) {
            List<String> words = Arrays.asList(msg.split(" "));
            int i = 0;
            for (String word : words) {
                if (word.startsWith("@")) {
                    if (!config.getString(word.substring(1).toLowerCase() + ".id").equals("")) {
                        words.set(i, "<" + config.getString(word.substring(1).toLowerCase() + ".id") + ">");
                    }
                }
                i++;
            }
            msg = Joiner.on(" ").join(words);
        } else {
            if (msg.startsWith("@")) {
                if (!config.getString(msg.substring(1).toLowerCase() + ".id").equals("")) {
                    msg = "<" + config.getString(msg.substring(1).toLowerCase() + ".id") + ">";
                }
            }
        }
        HttpClient httpClient = HttpClientBuilder.create().build();
        msg = msg.replace("\"", "\\\"").replace("&", "%26");
        try {
            HttpPost request = new HttpPost("https://hooks.slack.com/services/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");
            StringEntity params;
            if (icon) {
                long purgeTime = System.currentTimeMillis() - (5 * 24 * 60 * 60 * 1000);
                File image = new File("/var/www/images/avatars/" + player.toLowerCase() + ".png");
                if (!image.exists() || image.lastModified() < purgeTime) {
                    URL website = new URL("https://cravatar.eu/helmavatar/" + player.toLowerCase() + "/100.png");
                    ReadableByteChannel rbc = Channels.newChannel(website.openStream());
                    FileOutputStream fos = new FileOutputStream(image);
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                }
                String url = UUID.randomUUID().toString().split("-")[0] + "-i.cubexmc.net/avatars/" + player.toLowerCase() + ".png";
                getLogger().info(url);
                params = new StringEntity("payload={\"channel\": \"#" + serverName + "\", \"username\": \"" + player + "\", \"icon_url\": \"" + url + "\", \"text\": \"" + msg + "\"}");
            } else {
                params = new StringEntity("payload={\"channel\": \"#" + serverName + "\", \"username\": \"" + player + "\", \"text\": \"" + msg + "\"}");
            }
            request.addHeader("content-type", "application/x-www-form-urlencoded");
            request.setEntity(params);
            httpClient.execute(request);
            httpClient.getConnectionManager().shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String decodeMessage(String message) throws UnsupportedEncodingException {
        return URLDecoder.decode(message.replace("text=", "").replace("+", " "), "UTF-8")
                .replace("&amp;", "").replace("&lt;", "<").replace("&gt;", ">")
                .replaceFirst("(<(?=https?://[^\\|]+))|(\\|[^>]+>)", "");
    }

    private String tail(File file, int lines) {
        java.io.RandomAccessFile fileHandler = null;
        try {
            fileHandler =
                    new java.io.RandomAccessFile(file, "r");
            long fileLength = fileHandler.length() - 1;
            StringBuilder sb = new StringBuilder();
            int line = 0;

            for (long filePointer = fileLength; filePointer != -1; filePointer--) {
                fileHandler.seek(filePointer);
                int readByte = fileHandler.readByte();

                if (readByte == 0xA) {
                    if (filePointer < fileLength) {
                        line = line + 1;
                    }
                } else if (readByte == 0xD) {
                    if (filePointer < fileLength - 1) {
                        line = line + 1;
                    }
                }
                if (line >= lines) {
                    break;
                }
                sb.append((char) readByte);
            }

            return sb.reverse().toString();
        } catch (java.io.FileNotFoundException e) {
            e.printStackTrace();
            return null;
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (fileHandler != null) {
                try {
                    fileHandler.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean isInteger(String s, int radix) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (i == 0 && s.charAt(i) == '-') {
                if (s.length() == 1) return false;
                else continue;
            }
            if (Character.digit(s.charAt(i), radix) < 0) return false;
        }
        return true;
    }
}
