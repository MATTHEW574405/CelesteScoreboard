package fr.celeste.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
        obj.numberFormat(NumberFormat.blank());

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

    private Component header(String text) {
        return Component.text()
                .append(Component.text("\u00BB ", SKY).decoration(TextDecoration.BOLD, true))
                .append(Component.text(text, WHITE).decoration(TextDecoration.BOLD, true))
                .build();
    }

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

// =====================================================================================
// BLOCAGE DES MODS (meme fichier, classe non publique)
// =====================================================================================
final class ModBlocker implements Listener {

    private static final TextColor SKY = TextColor.color(0x87CEEB);

    private record Probe(String mod, String key) {}
    private record Result(boolean ok, List<Probe> hits, Map<Probe, String> texts) {}

    // METHODE 1 : canaux (fiable pour Xaero)
    private static final Map<String, String> BLOCKED_CHANNELS = Map.of(
            "xaerominimap", "Xaero's Minimap"
    );

    // METHODE 2 : cles de traduction des touches des mods (a verifier avec /modprobe)
    private static final List<Probe> PROBES = List.of(
            new Probe("Freecam", "key.freecam.toggle"),
            new Probe("Freecam", "key.freecam.playerControl"),
            new Probe("Freecam", "key.freecam.configGui")
            // Health Indicator : ajoute ici new Probe("Health Indicator", "la.cle.du.mod")
    );

    private static final Probe CONTROL = new Probe("controle", "key.jump");

    private final JavaPlugin plugin;
    private final Set<UUID> probing = ConcurrentHashMap.newKeySet();

    ModBlocker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("celeste.modbypass")) return;
        String channel = e.getChannel().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : BLOCKED_CHANNELS.entrySet()) {
            if (channel.startsWith(entry.getKey())) {
                kick(p, List.of(entry.getValue()));
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("celeste.modbypass") || PROBES.isEmpty()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || probing.contains(p.getUniqueId())) return;

            List<Probe> queue = new ArrayList<>();
            queue.add(CONTROL);
            queue.addAll(PROBES);

            startProbe(p, queue, result -> {
                if (!result.ok()) {
                    plugin.getLogger().info("Test des mods non concluant pour " + p.getName());
                    return;
                }
                if (!result.hits().isEmpty()) {
                    Set<String> mods = new LinkedHashSet<>();
                    for (Probe hit : result.hits()) mods.add(hit.mod());
                    kick(p, new ArrayList<>(mods));
                }
            });
        }, 60L);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (!msg.toLowerCase(Locale.ROOT).startsWith("/modprobe")) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.isOp() && !p.hasPermission("celeste.modprobe")) {
            p.sendMessage(Component.text("Tu n'as pas la permission.", NamedTextColor.RED));
            return;
        }

        String[] args = msg.trim().split("\\s+");
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage : /modprobe <cle>  (ex : /modprobe key.jump)", NamedTextColor.GRAY));
            return;
        }

        String key = args[1];
        Probe test = new Probe("test", key);
        startProbe(p, List.of(CONTROL, test), result -> {
            String ctl = result.texts().get(CONTROL);
            String out = result.texts().get(test);
            if (!result.ok()) {
                p.sendMessage(Component.text(
                        "Test non concluant (controle : " + ctl + ").", NamedTextColor.RED));
                return;
            }
            boolean hit = !result.hits().isEmpty();
            p.sendMessage(Component.text(
                    "Controle OK (" + ctl + "). " + key + " -> \"" + out + "\" : "
                            + (hit ? "MOD DETECTE" : "non traduit (aucun mod avec cette cle)"),
                    hit ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        });
    }

    private void startProbe(Player p, List<Probe> queue, Consumer<Result> done) {
        InventoryView raw = p.openAnvil(null, true);
        if (!(raw instanceof AnvilView view)) {
            done.accept(new Result(false, List.of(), Map.of()));
            return;
        }
        probing.add(p.getUniqueId());
        step(p, view, queue, 0, new HashMap<>(), done);
    }

    private void step(Player p, AnvilView view, List<Probe> queue, int i,
                      Map<Probe, String> texts, Consumer<Result> done) {
        boolean stillOpen = p.isOnline() && p.getOpenInventory().getType() == InventoryType.ANVIL;
        if (!stillOpen) {
            finish(p, view, queue, texts, false, done);
            return;
        }
        if (i >= queue.size()) {
            finish(p, view, queue, texts, true, done);
            return;
        }

        Probe probe = queue.get(i);
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(m -> m.displayName(Component.translatable(probe.key())));
        view.getTopInventory().setItem(0, item);

        long delay = Math.min(30L, 6L + p.getPing() / 25L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            texts.put(probe, view.getRenameText());
            step(p, view, queue, i + 1, texts, done);
        }, delay);
    }

    private void finish(Player p, AnvilView view, List<Probe> queue,
                        Map<Probe, String> texts, boolean complete, Consumer<Result> done) {
        view.getTopInventory().clear();
        probing.remove(p.getUniqueId());
        if (p.isOnline() && p.getOpenInventory().getType() == InventoryType.ANVIL) {
            p.closeInventory();
        }

        Set<String> allKeys = new LinkedHashSet<>();
        for (Probe pr : queue) allKeys.add(pr.key());

        boolean controlOk = complete && translated(CONTROL.key(), texts.get(CONTROL), allKeys);
        List<Probe> hits = new ArrayList<>();
        if (controlOk) {
            for (Probe pr : queue) {
                if (pr != CONTROL && translated(pr.key(), texts.get(pr), allKeys)) hits.add(pr);
            }
        }
        done.accept(new Result(controlOk, hits, texts));
    }

    private boolean translated(String key, String text, Set<String> allKeys) {
        return text != null
                && !text.isBlank()
                && !text.equals(key)
                && !allKeys.contains(text)
                && !text.startsWith("key.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (probing.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (probing.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (probing.contains(e.getPlayer().getUniqueId())) e.getInventory().clear();
    }

    private void kick(Player p, List<String> modNames) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            String list = String.join(", ", modNames);
            plugin.getLogger().info(p.getName() + " expulse : mod interdit " + list);
            p.kick(Component.text()
                    .append(Component.text("\u2726 C\u00C9LESTE SMP \u2726", SKY).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("Tu ne peux pas te connecter au serveur avec le mod ", NamedTextColor.WHITE))
                    .append(Component.text(list, SKY).decorate(TextDecoration.BOLD))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Retire-le puis reconnecte-toi.", NamedTextColor.GRAY))
                    .build());
        });
    }
}
