package net.cubexmc.SlackChatBungee;

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

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main extends Plugin implements Listener {

    public static ServerSocket serverSocket;
    public static DefaultHttpServerConnection conn;
    public HttpParams params = new BasicHttpParams();
    public HttpRequest request;
    public HttpClient httpClient;

    @Override
    public void onEnable() {
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        try {
            int port = 25464;
            serverSocket = new ServerSocket(port);
            getLogger().info("[SlackChat] Connected to port " + port);
        } catch (Exception e) {
            e.printStackTrace();
        }
        conn = new DefaultHttpServerConnection();
        ProxyServer.getInstance().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run() {
                while (!serverSocket.isClosed()) {
                    try {
                        conn.bind(serverSocket.accept(), params);
                        request = conn.receiveRequestHeader();
                        conn.receiveRequestEntity((HttpEntityEnclosingRequest) request);
                        HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                        String data = EntityUtils.toString(entity);
                        String[] tokens = data.split("&");
                        String result = "Got it";
                        getLogger().info(Arrays.toString(tokens));
                        if (tokens[7].contains("command=%2Fsay")) {
                            String channel = tokens[4].replace("channel_name=", "");
                            String user = tokens[6].replace("user_name=", "");
                            String message = decodeMessage(tokens[8]);
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
                                    if (!getDataFolder().exists()) {
                                        if (!getDataFolder().mkdir()) {
                                            getLogger().warning("Unable to create config folder!");
                                        }
                                    }
                                    File f = new File(getDataFolder(), "config.yml");
                                    if (!f.exists()) {
                                        Files.copy(getResourceAsStream("config.yml"), f.toPath());
                                    }
                                    Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
                                    channel = config.getString(channelId);
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
                            getLogger().info("[SLACK - " + channel + "] " + user + ": " + message);
                            result = "";
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
                    } catch (Exception e) {
                        try {
                            e.printStackTrace();
                            if (conn.isOpen()) {
                                conn.close();
                            }
                        } catch (Exception ignored) {}
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
        logAttendance(event.getPlayer().getName(), "1");
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        postPayload("_left the game_", event.getPlayer().getName(), "globalchat");
        logAttendance(event.getPlayer().getName(), "0");
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

    public void scheduleVote(final boolean done) {
        ProxyServer.getInstance().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run() {
                if (java.time.LocalTime.now().getHour() == 19 && java.time.LocalTime.now().getMinute() == 0) {
                    if (!done) {
                        ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("VOTE", "Vote", "<!everyone> Vote now at http://cubexmc.net/?a=vote!"));
                    }
                    scheduleVote(true);
                } else {
                    scheduleVote(false);
                }
            }
        }, 15, TimeUnit.SECONDS);
    }

    public String getList() {
        String players = "";
        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
            players += "\n" + getPlayerList(info.getName());
        }
        return players;
    }

    public String getPlayerList(String serverName) {
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

    public void logAttendance(String name, String IO) {
        try {
            if (!getDataFolder().exists()) {
                if (!getDataFolder().mkdir()) {
                    getLogger().warning("Unable to create config folder!");
                }
            }
            File f = new File(getDataFolder(), "config.yml");
            if (!f.exists()) {
                Files.copy(getResourceAsStream("config.yml"), f.toPath());
            }
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
            List<String> staffList = config.getStringList("staff");
            boolean match = false;
            for (String staff : staffList) {
                if (name.equalsIgnoreCase(staff) && !match) {
                    String form = "126NnT3lEnaHUBD-mEICj0ereHJ3lIioFI2F2OsUqXC4";
                    String month = "" + LocalDateTime.now().getMonthValue();
                    String day = "" + LocalDateTime.now().getDayOfMonth();
                    String year = "" + LocalDateTime.now().getYear();
                    String hour = "" + LocalDateTime.now().getHour();
                    String minute = "" + LocalDateTime.now().getMinute();
                    runCommand("curl 'https://docs.google.com/forms/d/" + form + "/formResponse' " +
                            "-H 'Host: docs.google.com' -H 'User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64; rv:26.0) Gecko/20100101 Firefox/26.0' -H 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' " +
                            "-H 'Accept-Language: nl,en-us;q=0.7,en;q=0.3' -H 'Accept-Encoding: gzip, deflate' -H 'DNT: 1' " +
                            "-H 'Referer: https://docs.google.com/forms/d/" + form + "/viewform' " +
                            "-H 'Cookie: GDS_PREF=hl=en_US; __utma=184632636.34...1517515.2; PREF=ID=4922e3....:FF=0:LD=nl:TM=1380883655:LM=1381731571:S=JvyU_OhlkQ7rE3x3; NID=67=hy29...sglz4PVeS53BZ4eLkYK_wDm9-jmdj7apqNZv6rEwUPDobxjagtLN5gpl4A7v0oA' " +
                            "-H 'Connection: keep-alive' " +
                            "-H 'Content-Type: application/x-www-form-urlencoded' " +
                            "--data 'entry.324043662='" + name + "'&entry.1069936891='" + IO + "'&entry.1717374858='" + month + "'&entry.555297325='" + day + "'&entry.1020866769='" + year + "'&entry.1208667130='" + hour + "'&entry.2011924973='" + minute + "'&draftResponse=%5B%5D%0D%0A&pageHistory=0&fbzx=-5804634750901421753'");
                    match = true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void broadcastServer(String msg, String player, String serverName) {
        for (ProxiedPlayer p : ProxyServer.getInstance().getServerInfo(serverName).getPlayers()) {
            p.sendMessage("[S] " + player + ChatColor.DARK_GRAY + ": " + ChatColor.WHITE + msg);
        }
    }

    public void postPayload(String msg, String player, String serverName) {
        postPayload(msg, player, serverName, true);
    }

    public void postPayload(String msg, String player, String serverName, boolean icon) {
        httpClient = HttpClientBuilder.create().build();
        msg = msg.replace("\"", "\\\"").replace("&", "%26");
        try {
            HttpPost request = new HttpPost("https://hooks.slack.com/services/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");
            StringEntity params;
            if (icon) {
                params = new StringEntity("payload={\"channel\": \"#" + serverName + "\", \"username\": \"" + player + "\", \"icon_url\": \"https://cravatar.eu/helmavatar/" + player + "/100.png\", \"text\": \"" + msg + "\"}");
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

    public String decodeMessage(String message) throws UnsupportedEncodingException {
        return URLDecoder.decode(message.replace("text=", "").replace("+", " "), "UTF-8")
                .replace("&amp;", "").replace("&lt;", "<").replace("&gt;", ">")
                .replaceFirst("(<(?=https?://[^\\|]+))|(\\|[^>]+>)", "");
    }

    public void runCommand(final String command) {
        final Plugin plugin = this;
        ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                File wd = new File("/bin");
                Process proc = null;
                try {
                    proc = Runtime.getRuntime().exec("/bin/bash", null, wd);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (proc != null) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(proc.getOutputStream())), true);
                    out.println(command);
                    try {
                        proc.waitFor();
                        in.close();
                        out.close();
                        proc.destroy();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }
}
