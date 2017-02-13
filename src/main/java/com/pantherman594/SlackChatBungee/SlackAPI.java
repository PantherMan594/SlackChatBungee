package com.pantherman594.SlackChatBungee;

import com.google.common.base.Joiner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pantherman594.gssentials.BungeeEssentials;
import com.pantherman594.gssentials.event.GlobalChatEvent;
import com.pantherman594.gssentials.event.StaffChatEvent;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Created by david on 2/12.
 *
 * @author david
 */
class SlackAPI {
    private static final String OAUTH_AUTH = "https://slack.com/oauth/authorize";
    private static final String OAUTH_URL = "https://slack.com/api/oauth.access";
    private static final String PRESENCE_URL = "https://slack.com/api/users.getPresence";
    private static final String USER_AGENT = "Mozilla/5.0";

    private ServerSocket serverSocket;
    private String redirectUri;
    private String accessToken = null;

    SlackAPI(int port, String url, String clientId, String clientSecret) throws IOException {
        serverSocket = new ServerSocket(port);
        redirectUri = URLEncoder.encode(url + ":" + port);
        SlackChatBungee.getInstance().getLogger().log(Level.INFO, "[SlackAPI] Connected to port " + port);
        SlackChatBungee.getInstance().getLogger().log(Level.SEVERE, "\n\nPlease authenticate the application at: " + OAUTH_AUTH + "?client_id=" + clientId + "&scope=users:read&redirect_uri=" + redirectUri + "\n\n");

        ProxyServer.getInstance().getScheduler().schedule(SlackChatBungee.getInstance(), () -> {
            try {
                checkPresence();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, 15, TimeUnit.MINUTES);

        ProxyServer.getInstance().getScheduler().runAsync(SlackChatBungee.getInstance(), () -> {
            while (!serverSocket.isClosed()) {
                try (
                        Socket client = serverSocket.accept();
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        PrintWriter out = new PrintWriter(client.getOutputStream())
                ) {
                    String line;
                    String line2 = null;
                    while ((line = in.readLine()) != null) {
                        line2 = line;
                        if (line.startsWith("GET")) {
                            line2 = line.split(" ")[1].substring(2);
                            break;
                        }
                    }
                    if (line2 == null) continue;
                    String[] tokens = line2.split("&");

                    String result = "Got it";
                    if (tokens[0].startsWith("code=")) {
                        String code = tokens[0].replace("code=", "");
                        String getUrl = String.format(OAUTH_URL + "?client_id=%s&client_secret=%s&code=%s&redirect_uri=%s", clientId, clientSecret, code, redirectUri);

                        HttpURLConnection con = (HttpURLConnection) new URL(getUrl).openConnection();
                        con.setRequestProperty("User-Agent", USER_AGENT);

                        BufferedReader in2 = new BufferedReader(new InputStreamReader(con.getInputStream()));
                        JsonObject json = new Gson().fromJson(in2, JsonObject.class);
                        accessToken = json.get("access_token").getAsString();

                        checkPresence();
                    } else if (tokens.length >= 6 && tokens[7].startsWith("command=%2F")) {
                        switch (tokens[7].replace("command=%2F", "")) {
                            case "say":
                                String channel = tokens[4].replace("channel_name=", "");
                                String user = tokens[6].replace("user_name=", "");
                                if (SlackChatBungee.getConfig().getBoolean(user + ".say")) {
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
                                                channel = SlackChatBungee.getConfig().getString(channelId + ".id");
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
                                        if (SlackChatBungee.getConfig().getBoolean("logmsg")) {
                                            SlackChatBungee.getInstance().getLogger().log(Level.INFO, "[SLACK - " + channel + "] " + formatMsg(message, user));
                                        }
                                    }
                                    result = "";
                                } else {
                                    result = "You don't have permission to use /say.";
                                }
                                break;
                            case "run":
                                if (SlackChatBungee.getConfig().getBoolean(tokens[6].replace("user_name=", "") + ".run")) {
                                    ProxyServer.getInstance().getPluginManager().dispatchCommand(ProxyServer.getInstance().getConsole(), decodeMessage(tokens[8]));
                                    postPayload(decodeMessage("/" + tokens[8]), tokens[6].replace("user_name=", ""), "staffchat");
                                    result = "Ran command /" + decodeMessage(tokens[8]);
                                } else {
                                    result = "You don't have permission to use /run.";
                                }
                                break;
                            case "log":
                                if (SlackChatBungee.getConfig().getBoolean(tokens[6].replace("user_name=", "") + ".log")) {
                                    int lines = isInteger(decodeMessage(tokens[8])) ? Integer.valueOf(decodeMessage(tokens[8])) : 10;
                                    result = tail(new File(ProxyServer.getInstance().getPluginsFolder().getParent(), "proxy.log.0"), lines);
                                } else {
                                    result = "You don't have permission to use /log.";
                                }
                                break;
                            case "list":
                                if (SlackChatBungee.getConfig().getBoolean(tokens[6].replace("user_name=", "") + ".list")) {
                                    result = getList();
                                } else {
                                    result = "You don't have permission to use /list.";
                                }
                                break;
                        }
                    }

                    out.println("HTTP/1.1 200 OK");
                    out.println("Content-Type: text/plain");
                    out.println("Connection: close");
                    if (!result.equals("")) {
                        out.println();
                        out.println(result);
                    }
                    SlackChatBungee.getInstance().getLogger().log(Level.CONFIG, Arrays.toString(tokens));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    void postPayload(String msg, String player, String serverName) {
        postPayload(msg, player, serverName, true);
    }

    private void postPayload(final String message, String player, String serverName, boolean icon) {
        ProxyServer.getInstance().getScheduler().runAsync(SlackChatBungee.getInstance(), () -> {
            String msg = message;
            if (msg.contains(" ")) {
                List<String> words = Arrays.asList(msg.split(" "));
                int i = 0;
                for (String word : words) {
                    if (word.startsWith("@")) {
                        if (!SlackChatBungee.getConfig().getString(word.substring(1).toLowerCase() + ".id").equals("")) {
                            words.set(i, "<" + SlackChatBungee.getConfig().getString(word.substring(1).toLowerCase() + ".id") + ">");
                        }
                    }
                    i++;
                }
                msg = Joiner.on(" ").join(words);
            } else {
                if (msg.startsWith("@")) {
                    if (!SlackChatBungee.getConfig().getString(msg.substring(1).toLowerCase() + ".id").equals("")) {
                        msg = "<" + SlackChatBungee.getConfig().getString(msg.substring(1).toLowerCase() + ".id") + ">";
                    }
                }
            }
            msg = msg.replace("\"", "\\\"").replace("&", "%26");
            try {
                URL obj = new URL(SlackChatBungee.getConfig().getString("slackurl"));
                HttpURLConnection con = (HttpURLConnection) obj.openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("User-Agent", USER_AGENT);
                con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");

                String params;
                if (icon) {
                    String url = "https://cravatar.eu/helmavatar/" + player.toLowerCase() + "/100.png";
                    if (SlackChatBungee.getConfig().getBoolean("isJunct")) {
                        long purgeTime = System.currentTimeMillis() - (5 * 24 * 60 * 60 * 1000);
                        File image = new File("/var/www/images/avatars/" + player.toLowerCase() + ".png");
                        if (!image.exists() || image.lastModified() < purgeTime) {
                            URL website = new URL(url);
                            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
                            FileOutputStream fos = new FileOutputStream(image);
                            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                        }
                        url = UUID.randomUUID().toString().split("-")[0] + "-i.thejunct.io/avatars/" + player.toLowerCase() + ".png";
                    }
                    params = "payload={\"channel\": \"#" + serverName + "\", \"username\": \"" + player + "\", \"icon_url\": \"" + url + "\", \"text\": \"" + msg + "\"}";
                } else {
                    params = "payload={\"channel\": \"#" + serverName + "\", \"username\": \"" + player + "\", \"text\": \"" + msg + "\"}";
                }

                con.setDoOutput(true);
                DataOutputStream wr = new DataOutputStream(con.getOutputStream());
                wr.writeBytes(params);
                wr.flush();
                wr.close();

                con.getResponseCode();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
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
                if (BungeeEssentials.getInstance().getPlayerData().isHidden(p.getUniqueId().toString())) {
                    names += "[Hidden]";
                }
                num++;
            }
        } else {
            playerNames = false;
        }
        if (playerNames) {
            return serverName + " " + "(" + num + ")" + ": " + names;
        } else {
            return serverName + ": " + num;
        }
    }

    private void checkPresence() throws IOException {
        if (accessToken != null) {
            for (String name : SlackChatBungee.getConfig().getKeys()) {
                if (SlackChatBungee.getConfig().get(name) instanceof Configuration && SlackChatBungee.getConfig().getString(name + ".id").startsWith("@")) {
                    String url = String.format(PRESENCE_URL + "?token=%s&user=%s", accessToken, SlackChatBungee.getConfig().getString(name + ".id").substring(1));

                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestProperty("User-Agent", USER_AGENT);

                    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                    JsonObject json = new Gson().fromJson(in, JsonObject.class);
                    boolean present = json.get("presence").getAsString().equals("active");

                    if (present) {
                        SlackChatBungee.getInstance().logPresence(name);
                    }
                }
            }
        }
    }

    private void broadcastServer(String msg, String player, String serverName) {
        for (ProxiedPlayer p : ProxyServer.getInstance().getServerInfo(serverName).getPlayers()) {
            p.sendMessage(formatMsg(msg, player));
        }
    }

    private String formatMsg(String msg, String player) {
        return ChatColor.translateAlternateColorCodes('&', SlackChatBungee.getConfig().getString(player.toLowerCase() + ".tag")) + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + msg;
    }

    private String decodeMessage(String message) throws UnsupportedEncodingException {
        return URLDecoder.decode(message.replace("text=", "").replace("+", " "), "UTF-8")
                .replace("&amp;", "").replace("&lt;", "<").replace("&gt;", ">")
                .replaceFirst("(<(?=https?://[^|]+))|(\\|[^>]+>)", "");
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

    private boolean isInteger(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (i == 0 && s.charAt(i) == '-') {
                if (s.length() == 1) return false;
                else continue;
            }
            if (Character.digit(s.charAt(i), 10) < 0) return false;
        }
        return true;
    }
}
