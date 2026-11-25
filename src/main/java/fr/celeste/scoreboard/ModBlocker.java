package fr.celeste.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
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

public final class ModBlocker implements Listener {

    private static final TextColor SKY = TextColor.color(0x87CEEB);

    private record Probe(String mod, String key) {}

    private record Result(boolean ok, List<Probe> hits, Map<Probe, String> texts) {}

    // METHODE 1 : canaux de communication (debut du canal -> nom du mod)
    private static final Map<String, String> BLOCKED_CHANNELS = Map.of(
            "xaerominimap", "Xaero's Minimap"
    );

    // METHODE 2 : test des noms de touches (nom du mod, cle de traduction d'une touche du mod)
    // Les cles Freecam sont des SUPPOSITIONS : verifie-les avec /modprobe <cle>.
    private static final List<Probe> PROBES = List.of(
            new Probe("Freecam", "key.freecam.toggle"),
            new Probe("Freecam", "key.freecam.playerControl"),
            new Probe("Freecam", "key.freecam.configGui")
            // Health Indicator : ajoute ici les cles du mod, par exemple :
            // new Probe("Health Indicator", "key.nomdumod.toggle")
    );

    // Cle de controle : existe dans Minecraft sans mod
    private static final Probe CONTROL = new Probe("controle", "key.jump");

    private final JavaPlugin plugin;
    private final Set<UUID> probing = ConcurrentHashMap.newKeySet();
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newHttpClient();

    private String webhookUrl = "";
    private boolean kickPlayers = true;

    public ModBlocker(JavaPlugin plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    // ============================================================ REGLAGES

    /** Lit plugins/CelesteScoreboard/alerts.yml (le cree au premier demarrage). */
    private void loadSettings() {
        File dir = plugin.getDataFolder();
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "alerts.yml");
        if (!file.exists()) {
            try {
                Files.writeString(file.toPath(),
                        "# Webhook Discord : Parametres du salon > Integrations > Webhooks > Copier l'URL\n"
                                + "discord-webhook: \"\"\n"
                                + "# true = expulser le joueur en plus de l'alerte, false = alerte seulement\n"
                                + "kick-players: true\n",
                        StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("Impossible de creer alerts.yml : " + ex.getMessage());
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        webhookUrl = cfg.getString("discord-webhook", "").trim();
        kickPlayers = cfg.getBoolean("kick-players", true);
    }

    // =================================================== METHODE 1 : CANAUX

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("celeste.modbypass")) return;

        String channel = e.getChannel().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : BLOCKED_CHANNELS.entrySet()) {
            if (channel.startsWith(entry.getKey())) {
                punish(p, List.of(entry.getValue()), "Canal de communication");
                return;
            }
        }
    }

    // ============================================= METHODE 2 : NOMS DE TOUCHES

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
                    punish(p, new ArrayList<>(mods), "Test des touches");
                }
            });
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String prefix = e.getPlayer().getUniqueId() + ":";
        alerted.removeIf(k -> k.startsWith(prefix));
    }

    // ============================================================ COMMANDES

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        Player p = e.getPlayer();

        if (lower.startsWith("/modprobe")) {
            e.setCancelled(true);
            if (!isAdmin(p)) return;
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
                            "Test non concluant : ton client n'a pas repondu (controle : " + ctl + ").",
                            NamedTextColor.RED));
                    return;
                }
                boolean hit = !result.hits().isEmpty();
                p.sendMessage(Component.text(
                        "Controle OK (" + ctl + "). " + key + " -> \"" + out + "\" : "
                                + (hit ? "MOD DETECTE" : "non traduit (aucun mod avec cette cle)"),
                        hit ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            });

        } else if (lower.startsWith("/modalerttest")) {
            e.setCancelled(true);
            if (!isAdmin(p)) return;
            if (webhookUrl.isEmpty()) {
                p.sendMessage(Component.text(
                        "Aucun webhook : remplis discord-webhook dans plugins/CelesteScoreboard/alerts.yml puis /modreload.",
                        NamedTextColor.RED));
                return;
            }
            sendWebhook("\uD83E\uDDEA Test d'alerte", p, "Test", "Commande /modalerttest", "Aucune");
            p.sendMessage(Component.text("Test envoye : regarde ton salon Discord.", NamedTextColor.GREEN));

        } else if (lower.startsWith("/modreload")) {
            e.setCancelled(true);
            if (!isAdmin(p)) return;
            loadSettings();
            p.sendMessage(Component.text("alerts.yml recharge.", NamedTextColor.GREEN));
        }
    }

    private boolean isAdmin(Player p) {
        if (p.isOp() || p.hasPermission("celeste.modprobe")) return true;
        p.sendMessage(Component.text("Tu n'as pas la permission.", NamedTextColor.RED));
        return false;
    }

    // ======================================================= SANCTION + DISCORD

    private void punish(Player p, List<String> mods, String method) {
        String list = String.join(", ", mods);
        boolean first = alerted.add(p.getUniqueId() + ":" + list);
        if (first) {
            plugin.getLogger().info(p.getName() + " : mod interdit detecte (" + list + ", " + method + ")");
            sendWebhook("\uD83D\uDEA8 Mod interdit d\u00E9tect\u00E9", p, list, method,
                    kickPlayers ? "Expuls\u00E9" : "Alerte seulement");
        }
        if (kickPlayers) kick(p, list);
    }

    private void sendWebhook(String title, Player p, String mods, String method, String action) {
        if (webhookUrl.isEmpty()) return;

        String json = "{\"username\":\"Celeste Anti-Mods\","
                + "\"allowed_mentions\":{\"parse\":[]},"
                + "\"embeds\":[{"
                + "\"title\":\"" + esc(title) + "\","
                + "\"color\":8900331,"
                + "\"fields\":["
                + field("Joueur", p.getName(), true) + ","
                + field("Mod", mods, true) + ","
                + field("M\u00E9thode", method, true) + ","
                + field("Action", action, true)
                + "],"
                + "\"timestamp\":\"" + Instant.now() + "\"}]}";

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(r -> {
                        if (r.statusCode() / 100 != 2) {
                            plugin.getLogger().warning("Webhook Discord : erreur HTTP " + r.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Webhook Discord : " + ex.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("URL de webhook invalide dans alerts.yml");
        }
    }

    private String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(value) + "\",\"inline\":" + inline + "}";
    }

    private String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    private void kick(Player p, String modList) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.kick(Component.text()
                    .append(Component.text("\u2726 C\u00C9LESTE SMP \u2726", SKY).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("Tu ne peux pas te connecter au serveur avec le mod ", NamedTextColor.WHITE))
                    .append(Component.text(modList, SKY).decorate(TextDecoration.BOLD))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Retire-le puis reconnecte-toi.", NamedTextColor.GRAY))
                    .build());
        });
    }

    // ================================================= SONDE (ENCLUME INVISIBLE)

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
}
