package pl.panryba.mc.chat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import pl.panryba.mc.permissions.PermissionsManager;
import pl.panryba.mc.permissions.events.PlayerGroupsChangedEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Plugin extends JavaPlugin implements Listener {

    private String defaultFormat;
    private Map<Player, Long> times;
    private Long interval_seconds;
    private boolean turnedOff;
    private long minimumAge;
    private Map<String, String> templates;
    private Map<Player, PlayerChatInfo> playerGroups;
    private List<String> groupsOrder;
    private List<FilterInfo> filters;

    private class FilterInfo {
        private Pattern pattern;
        private String message;
        private Pattern replace;

        public FilterInfo(String regExpPattern, String replacePattern, String message) {
            this.pattern = Pattern.compile(regExpPattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

            if(replacePattern != null && !replacePattern.isEmpty()) {
                this.replace = Pattern.compile(replacePattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            }

            this.message = message;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public String getMessage() {
            return message;
        }

        public Pattern getReplace() {
            return replace;
        }
    }

    private class PlayerChatInfo {
        private String group;
        private boolean colors;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public boolean isColors() {
            return colors;
        }

        public void setColors(boolean colors) {
            this.colors = colors;
        }
    }

    @Override
    public void onEnable() {
        FileConfiguration config = getConfig();

        interval_seconds = config.getLong("interval_seconds", 5);
        minimumAge = config.getLong("minimum_age", 0);

        times = new HashMap<>();
        templates = new HashMap<>();
        playerGroups = new ConcurrentHashMap<>();
        filters = new ArrayList<>();

        List<Map<String, Object>> filtersList = (List<Map<String, Object>>) config.getList("filters");
        if(filtersList != null) {
            for (Map<String, Object> item : filtersList) {
                String pattern = (String) item.get("pattern");
                String message = (String) item.get("message");
                String replace = (String) item.get("replace");

                FilterInfo info = new FilterInfo(pattern, replace, message);
                filters.add(info);
            }
        }

        for(Map.Entry<String, Object> entry : config.getConfigurationSection("groups.templates").getValues(false).entrySet()) {
            String template = entry.getValue().toString();

            template = prepareTemplate(template);
            this.templates.put(entry.getKey().toLowerCase(), template);
        }

        List<String> groupsOrderConfig = config.getStringList("groups.order");
        this.groupsOrder = new ArrayList<>();
        for(String groupName : groupsOrderConfig) {
            this.groupsOrder.add(groupName.toLowerCase());
        }

        this.defaultFormat = prepareTemplate(config.getString("default"));

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("chat").setExecutor(new ChatCommand(this));

        for(Player player : Bukkit.getOnlinePlayers()) {
            preparePlayerGroup(player);
        }
    }

    private String prepareTemplate(String template) {
        return ColorUtils
                .replaceColors(template)
                .replace("{MESSAGE}", "%2$s");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onAsyncChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage();
        if(msg.startsWith("@") || msg.startsWith("#")) {
            // Ignore guild chats
            return;
        }

        Player player = event.getPlayer();
        if(player.hasPermission("fishchat.interval.bypass")) {
            processChat(event);
            return;
        }

        Long now = new Date().getTime();
        Long lastTime = times.get(player);

        if (lastTime == null) {
            this.times.put(player, now);
            processChat(event);
            return;
        }

        long diff = now - lastTime;
        if(diff >= this.interval_seconds * 1000) {
            this.times.put(player, now);
            processChat(event);
            return;
        }

        long secs = this.interval_seconds - (long)Math.floor(diff / 1000.0);
        String failMsg = ChatColor.GRAY + "Nastepna wiadomosc mozesz napisac za " + ChatColor.YELLOW + "" + secs + "s";
        player.sendMessage(failMsg);
        
        Bukkit.getLogger().info("Ignored chat: " + player.getName() + " - " + msg);

        event.setCancelled(true);
    }

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        preparePlayerGroup(player);
    }

    private void preparePlayerGroup(Player player) {
        boolean colors = player.hasPermission("fishchat.colors");
        PlayerChatInfo info = new PlayerChatInfo();
        info.setColors(colors);

        String group = null;

        Set<String> playerGroups = PermissionsManager.getInstance().getPlayerGroups(player.getName());
        for(String orderGroup : this.groupsOrder) {
            if(playerGroups.contains(orderGroup)) {
                group = orderGroup;
                break;
            }
        }

        if(group == null) {
            for(String playerGroup : playerGroups) {
                group = playerGroup;
            }
        }

        info.setGroup(group);
        this.playerGroups.put(player, info);
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        this.times.remove(event.getPlayer());
        this.playerGroups.remove(event.getPlayer());
    }

    @EventHandler
    private void onPlayerKick(PlayerKickEvent event) {
        this.times.remove(event.getPlayer());
        this.playerGroups.remove(event.getPlayer());
    }

    @EventHandler
    private void onPlayerGroupsChanged(PlayerGroupsChangedEvent event) {
        preparePlayerGroup(event.getPlayer());
    }

    private void processChat(AsyncPlayerChatEvent event) {
        try {
            handleLimits(event);
            if(!event.isCancelled()) {
                filterMessage(event);
            }
        }
        finally {
            if(!event.isCancelled()) {
                formatChat(event);
            }
        }
    }

    private void filterMessage(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = ColorUtils.removeColors(event.getMessage());

        for (final FilterInfo info : this.filters) {
            Pattern replace = info.getReplace();

            if (replace != null) {
                message = replace.matcher(message).replaceAll("");
            }

            Pattern pattern = info.getPattern();
            Matcher matcher = pattern.matcher(message);

            if (matcher.find()) {
                event.setCancelled(true);
                player.sendMessage(ColorUtils.replaceColors(info.getMessage()));
                Bukkit.getLogger().info("[FILTER] " + player.getName() + ": " + message);
                return;
            }
        }
    }

    private void handleLimits(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (this.turnedOff) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.GRAY + "Czat publiczny jest w tej chwili wylaczony");
            Bukkit.getLogger().info(player.getName() + " chat turned off: " + event.getMessage());
            return;
        }

        if (this.minimumAge > 0 && !event.getPlayer().hasPermission("fishchat.age.bypass")) {
            long firstPlayed = player.getFirstPlayed();
            Date now = new Date();
            long diff = now.getTime() - firstPlayed;
            long diffSecs = diff / 1000;

            if (diffSecs < this.minimumAge) {
                event.setCancelled(true);

                long waitDiff = this.minimumAge - diffSecs;
                player.sendMessage(ChatColor.GRAY + "Jestes nowym graczem. Aby korzystac z czatu, musisz poczekac " + waitDiff + "s");
                Bukkit.getLogger().info(player.getName() + " can't chat yet: " + event.getMessage());
            }
        }
    }

    private void formatChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        PlayerChatInfo info = this.playerGroups.get(player);
        if(info == null) {
            info = new PlayerChatInfo();
        }

        if(info.isColors()) {
            event.setMessage(ColorUtils.replaceColors(event.getMessage()));
        } else {
            event.setMessage(ColorUtils.removeColors(event.getMessage()));
        }

        String group = info.getGroup();
        String format = null;
        format = this.templates.get(group.toLowerCase());

        if(format == null) {
            format = this.defaultFormat;
        }

        if (format == null) {
            return;
        }

        format = format
                .replace("{DISPLAYNAME}", player.getDisplayName());

        event.setFormat(format);
    }

    void enableChat() {
        this.turnedOff = false;
    }

    void disableChat() {
        this.turnedOff = true;
    }
}
