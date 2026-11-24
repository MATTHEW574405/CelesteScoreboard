package fr.celeste.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CelesteScoreboard extends JavaPlugin implements Listener {

    private static final String TITLE = "CELESTE SMP";
    private static final TextColor SKY = TextColor.color(0x87CEEB);   // bleu ciel
    private static final TextColor SHINE_1 = TextColor.color(0xFFFFFF); // blanc
    private static final TextColor SHINE_2 = TextColor.color(0xD6F2FF);
    private static final TextColor SHINE_3 = TextColor.color(0xB4E4F8);
    private static final int PAUSE = 12; // ticks d'attente entre deux passages du reflet

    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();
    private int tick = 0;
    private String serverIp;

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        final Team ip, kills, deaths, time;

        Board(Scoreboard sb, Objective obj, Team ip, Team kills, Team deaths, Team time) {
            this.scoreboard = sb;
            this.objective = obj;
            this.ip = ip;
            this.kills = kills;
            this.deaths = deaths;
            this.time = time;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverIp = getConfig().getString("server-ip", "play.celestesmp.net");
        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            createBoard(p);
        }

        // Animation du titre : toutes les 2 ticks
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            Component title = buildTitle(tick);
            for (Board b : boards.values()) {
                b.objective.displayName(title);
            }
        }, 1L, 2L);

        // Stats : toutes les secondes
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Board b = boards.get(p.getUniqueId());
                if (b != null) updateLines(p, b);
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        createBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    private void createBoard(Player player) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("celeste", Criteria.DUMMY, buildTitle(tick));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Chaque ligne = une team avec une entree invisible unique (anti-clignotement)
        addBlank(sb, obj, "\u00A70", 6);
        Team ip = addLine(sb, obj, "\u00A71", 5);
        addBlank(sb, obj, "\u00A72", 4);
        Team kills = addLine(sb, obj, "\u00A73", 3);
        Team deaths = addLine(sb, obj, "\u00A74", 2);
        Team time = addLine(sb, obj, "\u00A75", 1);

        Board board = new Board(sb, obj, ip, kills, deaths, time);
        boards.put(player.getUniqueId(), board);
        updateLines(player, board);
        player.setScoreboard(sb);
    }

    private Team addLine(Scoreboard sb, Objective obj, String entry, int score) {
        Team t = sb.registerNewTeam("l" + score);
        t.addEntry(entry);
        obj.getScore(entry).setScore(score);
        return t;
    }

    private void addBlank(Scoreboard sb, Objective obj, String entry, int score) {
        Team t = addLine(sb, obj, entry, score);
        t.prefix(Component.empty());
    }

    private void updateLines(Player p, Board b) {
        b.ip.prefix(label("IP", NamedTextColor.AQUA, serverIp, NamedTextColor.WHITE));

        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;

        b.kills.prefix(label("Kills", NamedTextColor.GREEN, String.valueOf(kills), NamedTextColor.WHITE));
        b.deaths.prefix(label("Morts", NamedTextColor.RED, String.valueOf(deaths), NamedTextColor.WHITE));
        b.time.prefix(label("Temps", NamedTextColor.YELLOW, formatTime(seconds), NamedTextColor.WHITE));
    }

    private Component label(String name, NamedTextColor nameColor, String value, NamedTextColor valueColor) {
        return Component.text()
                .append(Component.text(name + " \u00BB ", nameColor).decoration(TextDecoration.ITALIC, false))
                .append(Component.text(value, valueColor).decoration(TextDecoration.ITALIC, false))
                .build();
    }

    private String formatTime(long totalSeconds) {
        long d = totalSeconds / 86400;
        long h = (totalSeconds % 86400) / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (d > 0) return d + "j " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    /** Titre bleu ciel avec un reflet blanc qui glisse de gauche a droite entre les lettres. */
    private Component buildTitle(int t) {
        int len = TITLE.length();
        int cycle = len + 6 + PAUSE;
        int center = (t % cycle) - 3;

        TextComponent.Builder b = Component.text();
        for (int i = 0; i < len; i++) {
            char c = TITLE.charAt(i);
            int d = Math.abs(i - center);
            TextColor color = switch (d) {
                case 0 -> SHINE_1;
                case 1 -> SHINE_2;
                case 2 -> SHINE_3;
                default -> SKY;
            };
            b.append(Component.text(c, color).decorate(TextDecoration.BOLD));
        }
        return b.build();
    }
}
