package fr.celeste.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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

    private static final String TITLE = "\u2726 C\u00C9LESTE SMP \u2726";
    private static final String SERVER_IP = "celestesmp.boxtoplay.com";
    private static final String DISCORD = "discord.gg/P7R2bSMwzW";

    private static final TextColor SKY = TextColor.color(0x87CEEB);
    private static final TextColor GRAY = TextColor.color(0x9A9A9A);
    private static final TextColor WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor SHINE_2 = TextColor.color(0xD6F2FF);
    private static final TextColor SHINE_3 = TextColor.color(0xB4E4F8);
    private static final int PAUSE = 12;

    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();
    private int tick = 0;

    private static final class Board {
        final Objective objective;
        final Team pseudo, kills, deaths, time;

        Board(Objective objective, Team pseudo, Team kills, Team deaths, Team time) {
            this.objective = objective;
            this.pseudo = pseudo;
            this.kills = kills;
            this.deaths = deaths;
            this.time = time;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new ModBlocker(this), this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            createBoard(p);
            setupTabName(p);
        }

        // Animation : titre du scoreboard + TAB (toutes les 2 ticks)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            Component title = buildTitle(tick);
            for (Board b : boards.values()) {
                b.objective.displayName(title);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                updateTab(p);
            }
        }, 1L, 2L);

        // Stats : chaque seconde
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
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            p.playerListName(null);
        }
        boards.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        createBoard(e.getPlayer());
        setupTabName(e.getPlayer());
        updateTab(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- SCOREBOARD

    private void createBoard(Player player) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("celeste", Criteria.DUMMY, buildTitle(tick));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank()); // cache les chiffres rouges

        addLine(sb, obj, "\u00A70", 11).prefix(Component.empty());
        addLine(sb, obj, "\u00A71", 10).prefix(header("Profil"));
        Team pseudo = addLine(sb, obj, "\u00A72", 9);
        addLine(sb, obj, "\u00A73", 8).prefix(Component.empty());
        addLine(sb, obj, "\u00A74", 7).prefix(header("Statistiques"));
        Team kills = addLine(sb, obj, "\u00A75", 6);
        Team deaths = addLine(sb, obj, "\u00A76", 5);
        Team time = addLine(sb, obj, "\u00A77", 4);
        addLine(sb, obj, "\u00A78", 3).prefix(Component.empty());
        addLine(sb, obj, "\u00A79", 2).prefix(
                Component.text("\u2726 " + SERVER_IP, SKY));

        Board board = new Board(obj, pseudo, kills, deaths, time);
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

    /** Fleche bleue + titre de section en blanc gras. */
    private Component header(String text) {
        return Component.text()
                .append(Component.text("\u00BB ", SKY).decoration(TextDecoration.BOLD, true))
                .append(Component.text(text, WHITE).decoration(TextDecoration.BOLD, true))
                .build();
    }

    /** Nom en gris, point, valeur en blanc. */
    private Component row(String name, String value) {
        return Component.text()
                .append(Component.text(name + " \u00B7 ", GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(value, WHITE).decoration(TextDecoration.BOLD, false))
                .build();
    }

    private void updateLines(Player p, Board b) {
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;

        b.pseudo.prefix(row("Pseudo", p.getName()));
        b.kills.prefix(row("Kills", String.valueOf(kills)));
        b.deaths.prefix(row("Morts", String.valueOf(deaths)));
        b.time.prefix(row("Temps", formatTime(seconds)));
    }

    private String formatTime(long total) {
        long d = total / 86400;
        long h = (total % 86400) / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (d > 0) return d + "j " + h + "h " + m + "m " + s + "s";
        if (h > 0) return h + "h " + m + "m " + s + "s";
        return m + "m " + s + "s";
    }

    /** Titre bleu ciel avec un reflet blanc qui glisse de gauche a droite. */
    private Component buildTitle(int t) {
        int len = TITLE.length();
        int cycle = len + 6 + PAUSE;
        int center = (t % cycle) - 3;

        TextComponent.Builder b = Component.text();
        for (int i = 0; i < len; i++) {
            int d = Math.abs(i - center);
            TextColor color = switch (d) {
                case 0 -> WHITE;
                case 1 -> SHINE_2;
                case 2 -> SHINE_3;
                default -> SKY;
            };
            b.append(Component.text(TITLE.charAt(i), color).decorate(TextDecoration.BOLD));
        }
        return b.build();
    }

    // ----------------------------------------------------------------------- TAB

    private void setupTabName(Player p) {
        p.playerListName(Component.text()
                .append(Component.text("\u2726 ", SKY))
                .append(Component.text(p.getName(), WHITE))
                .build());
    }

    private void updateTab(Player p) {
        Component header = Component.text()
                .append(Component.newline())
                .append(buildTitle(tick))
                .append(Component.newline())
                .append(Component.text("Bienvenue, ", GRAY))
                .append(Component.text(p.getName(), WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(shimmerBar())
                .build();

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();

        Component footer = Component.text()
                .append(shimmerBar())
                .append(Component.newline())
                .append(Component.text("Joueurs ", GRAY))
                .append(Component.text(online + "/" + max, WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text("  \u2726  ", SKY))
                .append(Component.text("Ping ", GRAY))
                .append(Component.text(p.getPing() + " ms", WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("\u2726 IP \u00BB ", SKY))
                .append(Component.text(SERVER_IP, WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("\u2726 Discord \u00BB ", SKY))
                .append(Component.text(DISCORD, WHITE).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .build();

        p.sendPlayerListHeaderAndFooter(header, footer);
    }

    /** Barre qui ondule entre bleu ciel et blanc. */
    private Component shimmerBar() {
        int n = 24;
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < n; i++) {
            float t = (float) ((Math.sin(i * 0.45 - tick * 0.35) + 1.0) / 2.0);
            b.append(Component.text("\u2501", TextColor.lerp(t, SKY, WHITE)));
        }
        return b.build();
    }
}
