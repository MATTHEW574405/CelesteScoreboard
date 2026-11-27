package fr.celeste.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager implements Listener {

    private static final TextColor SKY = TextColor.color(0x87CEEB);

    private final JavaPlugin plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("celeste", new VanishCmd());
    }

    public void disable() {
        Bukkit.getCommandMap().getKnownCommands().values().removeIf(c -> c instanceof VanishCmd);
        for (UUID id : new ArrayList<>(vanished)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unvanish(p, false);
        }
    }

    // ------------------------------------------------------------------ COMMANDE

    private final class VanishCmd extends Command {
        VanishCmd() {
            super("vanish");
            setDescription("Devenir invisible");
            setPermission("celeste.vanish");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Commande r\u00E9serv\u00E9e aux joueurs.", NamedTextColor.RED));
                return true;
            }
            if (!canVanish(p)) {
                p.sendMessage(Component.text("Tu n'as pas la permission.", NamedTextColor.RED));
                return true;
            }
            if (vanished.contains(p.getUniqueId())) unvanish(p, true);
            else vanish(p);
            return true;
        }
    }

    private boolean canVanish(Player p) {
        return p.isOp() || p.hasPermission("celeste.vanish");
    }

    private Component msg(String text) {
        return Component.text()
                .append(Component.text("\u2726 ", SKY))
                .append(Component.text(text, NamedTextColor.WHITE))
                .build();
    }

    // -------------------------------------------------------------------- ETAT

    private void vanish(Player p) {
        vanished.add(p.getUniqueId());
        p.setInvulnerable(true);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setCanPickupItems(false);
        p.setSleepingIgnored(true);
        p.setCollidable(false);
        for (Player other : Bukkit.getOnlinePlayers()) updateVisibility(other, p);
        p.sendMessage(msg("Vanish activ\u00E9 : invisible, immunis\u00E9, vol activ\u00E9."));
    }

    private void unvanish(Player p, boolean notify) {
        vanished.remove(p.getUniqueId());
        p.setInvulnerable(false);
        p.setCanPickupItems(true);
        p.setSleepingIgnored(false);
        p.setCollidable(true);
        GameMode gm = p.getGameMode();
        if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(p)) other.showPlayer(plugin, p);
        }
        if (notify) p.sendMessage(msg("Vanish d\u00E9sactiv\u00E9."));
    }

    // ---------------------------------------------------------------- VISIBILITE

    /** Les joueurs avec la permission vanish (et les vanish entre eux) se voient. */
    private boolean canSeeVanished(Player viewer) {
        return vanished.contains(viewer.getUniqueId()) || canVanish(viewer);
    }

    private void updateVisibility(Player viewer, Player target) {
        if (viewer.equals(target)) return;
        if (vanished.contains(target.getUniqueId()) && !canSeeVanished(viewer)) {
            viewer.hidePlayer(plugin, target);
        } else {
            viewer.showPlayer(plugin, target);
        }
    }

    /** Appele par RoleManager quand les roles d'un joueur changent ou qu'il se connecte. */
    public void refresh(Player p) {
        if (vanished.contains(p.getUniqueId()) && !canVanish(p)) unvanish(p, true);
        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) updateVisibility(p, v);
        }
    }

    /** Nombre de joueurs en ligne visibles par ce joueur (pour le TAB). */
    public int visibleOnline(Player viewer) {
        int n = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!vanished.contains(o.getUniqueId()) || canSeeVanished(viewer)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ EVENEMENTS

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (vanished.contains(p.getUniqueId())) {
            unvanish(p, false);
            e.quitMessage(null);
        }
    }
}
