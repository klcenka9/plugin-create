package org.example.autoban;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public final class AntiCheatBan extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, Deque<Violation>> violations = new ConcurrentHashMap<>();
    private final Set<UUID> automaticBans = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile List<Pattern> patterns = List.of();
    private volatile Duration banDuration = Duration.ofDays(7), window = Duration.ofSeconds(30);
    private volatile int verificationChecks = 2;
    private volatile boolean debug;

    @Override public void onEnable() {
        saveDefaultConfig(); reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        var command = getCommand("acb");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        // Bukkit scheduler is intentionally used only for thread-safe data maintenance.
        new BukkitRunnable() { @Override public void run() { cleanup(); } }.runTaskTimerAsynchronously(this, 20L * 60 * 30, 20L * 60 * 30);
        getLogger().info("AntiCheatBan enabled with " + patterns.size() + " detection patterns.");
    }

    @Override public void onDisable() { violations.clear(); }

    private void reloadSettings() {
        reloadConfig(); FileConfiguration c = getConfig();
        try { banDuration = parseDuration(c.getString("ban-duration", "7d")); }
        catch (IllegalArgumentException ex) { getLogger().warning("Invalid ban-duration; using 7d."); banDuration = Duration.ofDays(7); }
        try { window = parseDuration(c.getString("verification-time-window", "30s")); }
        catch (IllegalArgumentException ex) { getLogger().warning("Invalid verification-time-window; using 30s."); window = Duration.ofSeconds(30); }
        verificationChecks = Math.max(2, c.getInt("verification-checks", 2));
        debug = c.getBoolean("debug-mode", false);
        List<Pattern> compiled = new ArrayList<>();
        for (String regex : c.getStringList("detection-words")) try { compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE)); }
        catch (PatternSyntaxException ex) { getLogger().warning("Ignoring invalid detection regex: " + regex); }
        patterns = List.copyOf(compiled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        String reason = event.getReason(); Pattern match = findPattern(reason);
        if (match == null) return;
        Player player = event.getPlayer(); UUID id = player.getUniqueId(); names.put(id, player.getName());
        if (isExempt(player)) { debug("Exempt kick signal from " + player.getName()); return; }

        // Preserve the kick. Cancelling it would let an unverified cheater remain online.
        // Bukkit bans and command execution must run on the server thread; schedule a post-kick verification.
        String pattern = match.pattern();
        Bukkit.getScheduler().runTask(this, () -> recordAndMaybeBan(id, player.getName(), reason, pattern));
    }

    private void recordAndMaybeBan(UUID id, String name, String reason, String pattern) {
        names.put(id, name);
        Deque<Violation> history = violations.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        boolean shouldBan;
        synchronized (history) {
            Instant now = Instant.now(); prune(history, now);
            history.addLast(new Violation(now, pattern, reason));
            long distinct = history.stream().map(Violation::pattern).distinct().count();
            shouldBan = history.size() >= verificationChecks || distinct >= 2;
        }
        debug("Matched kick pattern for " + name + "; verification entries=" + count(id));
        if (shouldBan) applyBan(id, name, reason);
    }

    private void applyBan(UUID id, String name, String reason) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        Date expires = Date.from(Instant.now().plus(banDuration));
        String banReason = "AntiCheatBan: opakované anti-cheat kicky (" + safe(reason) + ")";
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, banReason, expires, getName());
        automaticBans.add(id); violations.remove(id);
        String message = ChatColor.RED + "Byl jsi dočasně zabanován za opakované anti-cheat kicky. Délka: " + format(banDuration);
        Player online = Bukkit.getPlayer(id); if (online != null) online.kickPlayer(message);
        String notice = ChatColor.RED + "[AntiCheatBan] " + ChatColor.YELLOW + name + " byl automaticky zabanován na " + format(banDuration) + ".";
        // This runs on the primary server thread, including on Folia via the API scheduler.
        for (Player viewer : Bukkit.getOnlinePlayers()) if (viewer.hasPermission("anticheatban.view")) viewer.sendMessage(notice);
        getLogger().info(name + " automatically banned until " + expires + "; reason: " + safe(reason));
    }

    private boolean isExempt(Player p) {
        String permission = getConfig().getString("exemption-permission", "anticheatban.immune");
        if (p.hasPermission(permission)) return true;
        for (String entry : getConfig().getStringList("whitelisted-players"))
            if (entry.equalsIgnoreCase(p.getName()) || entry.equalsIgnoreCase(p.getUniqueId().toString())) return true;
        return false;
    }

    private Pattern findPattern(String message) {
        for (Pattern pattern : patterns) if (pattern.matcher(message).find()) return pattern;
        return null;
    }
    private void cleanup() {
        Instant cutoff = Instant.now().minus(window);
        violations.entrySet().removeIf(entry -> {
            Deque<Violation> q = entry.getValue(); synchronized (q) { prune(q, Instant.now()); return q.isEmpty(); }
        });
    }
    private void prune(Deque<Violation> q, Instant now) { while (!q.isEmpty() && q.peekFirst().at().plus(window).isBefore(now)) q.removeFirst(); }
    private int count(UUID id) { Deque<Violation> q=violations.get(id); if(q==null)return 0; synchronized(q){return q.size();} }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("/acb <reload|check|whitelist|unban|stats|list|autobans|violations|test>"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String permission = "anticheatban." + sub;
        if (!sender.hasPermission(permission)) { sender.sendMessage(ChatColor.RED + "Nemáš oprávnění " + permission + "."); return true; }
        switch (sub) {
            case "reload" -> { reloadSettings(); sender.sendMessage("AntiCheatBan konfigurace načtena."); }
            case "check" -> check(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "unban" -> unban(sender, args);
            case "stats" -> sender.sendMessage("Bany: " + Bukkit.getBanList(BanList.Type.NAME).getBanEntries().size() + ", auto bany: " + automaticBans.size() + ", aktivní violations: " + violations.values().stream().mapToInt(q -> { synchronized(q){return q.size();} }).sum());
            case "list" -> listBans(sender, false);
            case "autobans" -> sender.sendMessage(automaticBans.isEmpty() ? "Žádné automatické bany v aktuální relaci." : "Auto bany (aktuální relace): " + automaticBans.stream().map(id -> names.getOrDefault(id, id.toString())).collect(Collectors.joining(", ")));
            case "violations" -> listViolations(sender);
            case "test" -> test(sender, args);
            default -> sender.sendMessage("Neznámý příkaz.");
        }
        return true;
    }
    private void check(CommandSender s, String[] a) {
        if(a.length<2){s.sendMessage("Použití: /acb check <hráč>");return;}
        OfflinePlayer p=Bukkit.getOfflinePlayer(a[1]); UUID id=p.getUniqueId(); String name=p.getName()==null?a[1]:p.getName();
        boolean banned=Bukkit.getBanList(BanList.Type.NAME).isBanned(name); boolean white=isWhitelisted(name,id);
        s.sendMessage(name+" | ban: "+banned+" | whitelist: "+white+" | violations: "+count(id));
    }
    private void whitelist(CommandSender s,String[] a) {
        if(a.length<2){s.sendMessage("Použití: /acb whitelist <add|remove|list> [hráč]");return;}
        List<String> list=new ArrayList<>(getConfig().getStringList("whitelisted-players")); String op=a[1].toLowerCase(Locale.ROOT);
        if(op.equals("list")){s.sendMessage("Whitelist: "+(list.isEmpty()?"(prázdný)":String.join(", ",list)));return;}
        if(a.length<3){s.sendMessage("Zadej jméno nebo UUID.");return;}
        if(op.equals("add")){if(list.stream().noneMatch(v->v.equalsIgnoreCase(a[2])))list.add(a[2]);}
        else if(op.equals("remove"))list.removeIf(v->v.equalsIgnoreCase(a[2])); else {s.sendMessage("Použití: add|remove|list");return;}
        getConfig().set("whitelisted-players",list); saveConfig(); s.sendMessage("Whitelist aktualizován.");
    }
    private void unban(CommandSender s,String[] a) {
        if(a.length<2){s.sendMessage("Použití: /acb unban <hráč>");return;}
        String name=a[1]; Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        OfflinePlayer p=Bukkit.getOfflinePlayer(name); violations.remove(p.getUniqueId()); automaticBans.remove(p.getUniqueId());
        s.sendMessage("Odbanováno a violations vymazány pro "+name+".");
    }
    private void listBans(CommandSender s, boolean autoOnly) {
        var entries=Bukkit.getBanList(BanList.Type.NAME).getBanEntries();
        if(entries.isEmpty()){s.sendMessage("Žádné bany.");return;}
        entries.stream().limit(50).forEach(e->s.sendMessage(e.getTarget()+" — "+e.getReason()+" (do "+e.getExpiration()+")"));
    }
    private void listViolations(CommandSender s) {
        int shown=0;
        for(var entry:violations.entrySet()) { Deque<Violation> q=entry.getValue(); synchronized(q) { for(Violation v:q) { if(shown++>=50)return; s.sendMessage(names.getOrDefault(entry.getKey(),entry.getKey().toString())+" — "+v.at()+" — "+v.reason()); } } }
        if(shown==0)s.sendMessage("Žádná aktivní porušení.");
    }
    private void test(CommandSender s,String[] a) {
        if(a.length<2){s.sendMessage("Použití: /acb test <zpráva>");return;}
        String message=String.join(" ",java.util.Arrays.copyOfRange(a,1,a.length)); Pattern p=findPattern(message);
        s.sendMessage(p==null?"Bez shody.":"Shoda: "+p.pattern());
    }
    private boolean isWhitelisted(String name,UUID id) { return getConfig().getStringList("whitelisted-players").stream().anyMatch(v->v.equalsIgnoreCase(name)||v.equalsIgnoreCase(id.toString())); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if(args.length==1)return starts(List.of("reload","check","whitelist","unban","stats","list","autobans","violations","test"),args[0]);
        if(args.length==2&&args[0].equalsIgnoreCase("whitelist"))return starts(List.of("add","remove","list"),args[1]);
        if(args.length==2&&List.of("check","unban").contains(args[0].toLowerCase(Locale.ROOT)))return starts(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),args[1]);
        return Collections.emptyList();
    }
    private List<String> starts(List<String> options,String prefix){return options.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();}
    private static Duration parseDuration(String text) {
        if(text==null)throw new IllegalArgumentException(); var m=Pattern.compile("(?i)(\\d+)([smhdw])").matcher(text.trim());
        if(!m.matches())throw new IllegalArgumentException(); long n=Long.parseLong(m.group(1));
        return switch(m.group(2).toLowerCase(Locale.ROOT)){case "s"->Duration.ofSeconds(n);case "m"->Duration.ofMinutes(n);case "h"->Duration.ofHours(n);case "d"->Duration.ofDays(n);case "w"->Duration.ofDays(Math.multiplyExact(n,7));default->throw new IllegalArgumentException();};
    }
    private static String format(Duration d){long sec=d.getSeconds(); if(sec%604800==0)return sec/604800+"w";if(sec%86400==0)return sec/86400+"d";if(sec%3600==0)return sec/3600+"h";if(sec%60==0)return sec/60+"m";return sec+"s";}
    private static String safe(String s){return s==null?"unknown":s.replaceAll("[\\r\\n]"," ");}
    private void debug(String s){if(debug)getLogger().info("[debug] "+s);}
    private record Violation(Instant at,String pattern,String reason){}
}
