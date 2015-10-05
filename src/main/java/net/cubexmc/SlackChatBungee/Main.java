package net.cubexmc.SlackChatBungee;

import com.pantherman594.gssentials.event.GlobalChatEvent;
import com.pantherman594.gssentials.event.StaffChatEvent;
import com.pantherman594.gssentials.utils.Messenger;
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

import java.net.ServerSocket;
import java.net.URLDecoder;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main extends Plugin implements Listener {

    public static ServerSocket serverSocket;
    public static DefaultHttpServerConnection conn;
    public final Logger logger = Logger.getLogger("Minecraft");
    public HttpParams params = new BasicHttpParams();
    public HttpRequest request;
    public HttpClient httpClient;

    @Override
    public void onEnable() {
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        try {
            int port = 25464;
            serverSocket = new ServerSocket(port);
            logger.info("[SlackChat] Connected to port " + port);
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
                        String result = "";
                        if (tokens[8].contains("user_name=")) {
                            String channel = tokens[5].replace("channel_name=", "");
                            String user = tokens[8].replace("user_name=", "");
                            if (!user.equals("slackbot")) {
                                String message = URLDecoder.decode(tokens[9].replace("text=", "").replace("+", " "), "UTF-8").replace("&amp;", "").replace("&lt;", "<").replace("&gt;", ">").replaceAll("(<(?=https?:\\/\\/[^\\|]+))|(\\|[^>]+>)", "");
                                switch (channel) {
                                    case "staffchat":
                                        ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("SLACK", user, message));
                                        break;
                                    case "globalchat":
                                        ProxyServer.getInstance().getPluginManager().callEvent(new GlobalChatEvent("SLACK", user, message));
                                        break;
                                    default:
                                        boolean found = false;
                                        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
                                            if (info.getName().equalsIgnoreCase(channel)) {
                                                broadcastServer(message, user, info.getName());
                                                found = true;
                                            }
                                        }
                                        if (!found) {
                                            ProxyServer.getInstance().getPluginManager().callEvent(new StaffChatEvent("SLACK", user, message));
                                        }
                                        break;
                                }
                                ProxyServer.getInstance().getLogger().log(Level.INFO, "[SLACK - " + channel + "] " + user + ": " + message);
                            }
                        } else {
                            String channel = tokens[4].replace("channel_name=", "");
                            if (tokens[8].equals("text=")) {
                                result = getList(channel);
                            } else {
                                result = getList(tokens[8].replace("text=", ""));
                            }
                        }
                        HttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
                        if (result.equals("")) {
                            response.setEntity(new StringEntity("Got it"));
                        } else {
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
        ProxyServer.getInstance().getScheduler().schedule(this, new Runnable() {
            @Override
            public void run() {
                if (java.time.LocalTime.now().equals(java.time.LocalTime.of(16, 0)) && java.time.LocalTime.now().getSecond() <= 15) {
                    postPayload("Vote now at http://cubexmc.net/?a=vote!", "Vote", "@slackbot", false);
                }
            }
        }, 15, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onStaffChat(StaffChatEvent event) {
        if (!event.getServer().equals("SLACK")) {
            if (!(event.getMessage().contains("is suspected for") || event.getMessage().contains("may be hacking ("))) {
                postPayload(event.getMessage(), event.getSender(), "#staffchat");
            }
        }
    }

    @EventHandler
    public void onGlobalChat(GlobalChatEvent event) {
        if (!event.getServer().equals("SLACK")) postPayload(event.getMessage(), event.getSender(), "#globalchat");
    }

    @EventHandler
    public void onPlayerJoin(PostLoginEvent event) {
        postPayload("_joined the game_", event.getPlayer().getName(), "#globalchat");
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        postPayload("_left the game_", event.getPlayer().getName(), "#globalchat");
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        postPayload("_switched to " + event.getPlayer().getServer().getInfo().getName() + "_", event.getPlayer().getName(), "#globalchat");
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
        postPayload(event.getMessage(), sender.getName(), "#" + sender.getServer().getInfo().getName());
    }

    @Override
    public void onDisable() {
        try {
            serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getList(String serverName) {
        String players = "";
        if (serverName.equalsIgnoreCase("staffchat") || serverName.equalsIgnoreCase("globalchat")) {
            for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
                players += "\n" + getPlayerList(info.getName());
            }
        } else {
            for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
                if (info.getName().equalsIgnoreCase(serverName)) {
                    players += "\n" + getPlayerList(info.getName());
                }
            }
        }
        return players;
    }

    public String getPlayerList(String serverName) {
        boolean playerNames = true;
        String names = "";
        int num = 0;
        ServerInfo info = ProxyServer.getInstance().getServerInfo(serverName);
        if (!info.getPlayers().isEmpty()) {
            for (ProxiedPlayer player : info.getPlayers()) {
                if (!Messenger.isHidden(player)) {
                    if (names.equals("")) {
                        names += player.getName();
                    } else {
                        names += ", " + player.getName();
                    }
                    num++;
                }
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
        msg = msg.replace("\"", "\\\"");
        try {
            HttpPost request = new HttpPost("https://hooks.slack.com/services/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");
            StringEntity params;
            if (icon) {
                params = new StringEntity("payload={\"channel\": \"" + serverName + "\", \"username\": \"" + player + "\", \"icon_url\": \"https://cravatar.eu/helmavatar/" + player + "/100.png\", \"text\": \"" + msg + "\"}");
            } else {
                params = new StringEntity("payload={\"channel\": \"" + serverName + "\", \"username\": \"" + player + "\", \"text\": \"" + msg + "\"}");
            }
            request.addHeader("content-type", "application/x-www-form-urlencoded");
            request.setEntity(params);
            httpClient.execute(request);
            httpClient.getConnectionManager().shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
