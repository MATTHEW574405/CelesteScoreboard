package fr.celeste.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

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

    // ---------------------------------------------------------------------
    // METHODE 1 : canaux de communication (fiable pour Xaero's Minimap)
    // debut du canal -> nom du mod affiche au joueur
    private static final Map<String, String> BLOCKED_CHANNELS = Map.of(
            "xaerominimap", "Xaero's Minimap"
    );

    // ---------------------------------------------------------------------
    // METHODE 2 : test des noms de touches (Freecam, Health Indicator...)
    // Chaque ligne = (nom du mod affiche, cle de traduction d'une touche du mod).
    // Les cles ci-dessous pour Freecam sont des SUPPOSITIONS : verifie-les avec
    // la commande /modprobe <cle> (voir explications) et corrige si besoin.
    private static final List<Probe> PROBES = List.of(
            new Probe("Freecam", "key.freecam.toggle"),
            new Probe("Freecam", "key.freecam.playerControl"),
            new Probe("Freecam", "key.freecam.configGui")
            // Health Indicator : ajoute ici les cles du mod, par exemple :
            // new Probe("Health Indicator", "key.nomdumod.toggle")
    );

    // Cle de controle : existe dans Minecraft sans mod. Sert a verifier que le test marche.
    private static final Probe CONTROL = new Probe("controle", "key.jump");

    private final JavaPlugin plugin;
    private final Set<UUID> probing = ConcurrentHashMap.newKeySet();

    public ModBlocker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ===================================================== METHODE 1 : CANAUX

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

    // =============================================== METHODE 2 : NOMS DE TOUCHES

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("celeste.modbypass") || PROBES.isEmpty()) return;

        // on attend 3 secondes que le client ait fini de charger
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

    /** /modprobe <cle> : teste UNE cle sur toi-meme (admins seulement). */
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
        view.getTopInventory().clear(); // rien ne doit etre rendu au joueur
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

    /** Vrai si le client a traduit la cle (donc un mod qui la definit est installe). */
    private boolean translated(String key, String text, Set<String> allKeys) {
        return text != null
                && !text.isBlank()
                && !text.equals(key)
                && !allKeys.contains(text)   // evite de lire le resultat de la sonde precedente
                && !text.startsWith("key.");
    }

    // Le joueur ne doit pas pouvoir toucher a l'objet de la sonde
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

    // ------------------------------------------------------------------ KICK

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
