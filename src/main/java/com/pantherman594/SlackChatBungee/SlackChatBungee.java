package com.pantherman594.SlackChatBungee;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.pantherman594.gssentials.event.GlobalChatEvent;
import com.pantherman594.gssentials.event.StaffChatEvent;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@SuppressWarnings({"ResultOfMethodCallIgnored", "SpellCheckingInspection"})
public class SlackChatBungee extends Plugin implements Listener {
    private static SlackChatBungee instance;
    private static Configuration config;

    private SlackAPI slackApi;
    private SpreadsheetsAPI spreadsheetsAPI;

    static SlackChatBungee getInstance() {
        return instance;
    }

    static Configuration getConfig() {
        return config;
    }

    static void saveConfig() {
        File f = new File(getInstance().getDataFolder(), "config.yml");
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, f);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdir()) {
                getLogger().log(Level.WARNING, "Unable to create config folder!");
            }
        }
        reload();

        int port = config.getInt("port");
        if (port == 0) {
            getLogger().log(Level.WARNING, "Please configure a port and url. Plugin disabling...");
            return;
        }
        try {
            slackApi = new SlackAPI(port, SlackChatBungee.getConfig().getString("url"), "3291865129.140567584388", "30372eb12001648ab42c496f621327cb");
            spreadsheetsAPI = new SpreadsheetsAPI("Sheets - Attendance", new File(getDataFolder(), "creds"), new File(getDataFolder(), "client_secret.json"));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        ProxyServer.getInstance().getPluginManager().registerCommand(this, new ReloadCommand());
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onStaffChat(StaffChatEvent event) {
        slackApi.postPayload(event.getMessage(), event.getSender(), "staffchat");
    }

    @EventHandler
    public void onGlobalChat(GlobalChatEvent event) {
        slackApi.postPayload(event.getMessage(), event.getSender(), "globalchat");
    }

    @EventHandler
    public void onPlayerJoin(PostLoginEvent event) {
        slackApi.postPayload("_joined the game_", event.getPlayer().getName(), "globalchat");
        if (!config.getString(event.getPlayer().getName().toLowerCase() + ".id").equals("")) {
            logPresence(event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        slackApi.postPayload("_left the game_", event.getPlayer().getName(), "globalchat");
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        slackApi.postPayload("_switched to " + event.getPlayer().getServer().getInfo().getName() + "_", event.getPlayer().getName(), "globalchat");
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
        for (String regex : config.getStringList("blacklist")) {
            if (event.getMessage().matches(regex)) {
                return;
            }
        }
        slackApi.postPayload(event.getMessage(), sender.getName(), sender.getServer().getInfo().getName());
    }

    @Override
    public void onDisable() {
        slackApi.close();
    }

    void logPresence(String name) {
        try {
            Sheets sheet = spreadsheetsAPI.getSheetsService();
            String sheetId = "1knJK27uThsaGoe74D5CZw7iufEJ-dTyPzKTP3c18S_A";
            String range = "Attendance!A1:Z1000";

            ValueRange vRange = sheet.spreadsheets().values().get(sheetId, range).execute();
            List<List<Object>> values = vRange.getValues();
            boolean todayFound = false;
            int width = 0;
            for (int i = 0; i < values.size(); i++) {
                List<Object> row = values.get(i);
                width = row.size();
                if (row.get(0).equals(LocalDate.now().format(DateTimeFormatter.ISO_DATE))) {
                    todayFound = true;
                    int column = 1;
                    while (!values.get(0).get(column).equals("")) {
                        String sheetName = (String) values.get(0).get(column);
                        if (sheetName.equalsIgnoreCase(name)) {
                            row.set(column, 1);
                            break;
                        }
                        column++;
                    }
                    values.set(i, row);
                    vRange.setValues(values);
                    sheet.spreadsheets().values().update(sheetId, range, vRange).setValueInputOption("USER_ENTERED").execute();
                    break;
                }
            }

            if (!todayFound) {
                values = new ArrayList<>();
                List<Object> row = new ArrayList<>();
                row.add(LocalDate.now().format(DateTimeFormatter.ISO_DATE));
                for (int i = 1; i < width; i++) {
                    row.add(0);
                }
                values.add(row);
                vRange = new ValueRange().setValues(values);
                sheet.spreadsheets().values().append(sheetId, range, vRange).setValueInputOption("USER_ENTERED").execute();
                logPresence(name);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void reload() {
        File f = new File(getDataFolder(), "config.yml");
        try {
            if (!f.exists()) {
                Files.copy(getResourceAsStream("config.yml"), f.toPath());
            }
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public class ReloadCommand extends Command {

        ReloadCommand() {
            super("scbreload", "scb.reload", "scb");
        }

        @Override
        public void execute(CommandSender commandSender, String[] strings) {
            reload();
        }
    }
}
