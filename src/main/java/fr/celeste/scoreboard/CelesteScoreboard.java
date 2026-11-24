package fr.celeste.scoreboard;

import io.papermc.paper.event.player.PlayerRegisterChannelEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class CelesteScoreboard extends JavaPlugin implements Listener {

    // Mots-clés recherchés dans les canaux de plugin enregistrés par les mods
    private static final List<String> BLOCKED = List.of("xaero", "freecam");

    private static final Component KICK_MESSAGE = Component.text()
            .append(Component.text("Mods interdits détectés !", NamedTextColor.RED))
            .appendNewline()
            .append(Component.text("Retire Xaero's Minimap / Freecam / Health Indicator.", NamedTextColor.GRAY))
            .build();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("celeste.bypass")) return;
        if (isBlocked(event.getChannel())) {
            kick(player, "canal " + event.getChannel());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("celeste.bypass")) return;

        // Codes qui demandent à Xaero de désactiver minimap et radar
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§n§o§m§i§n§i§m§a§p");
            player.sendMessage("§f§a§i§r§x§a§e§r§o");
        }, 10L);

        // Vérification différée des canaux et du client
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            String brand = player.getClientBrandName();
            if (brand != null && isBlocked(brand)) {
                kick(player, "client " + brand);
                return;
            }
            for (String channel : player.getListeningPluginChannels()) {
                if (isBlocked(channel)) {
                    kick(player, "canal " + channel);
                    return;
                }
            }
        }, 40L);
    }

    private boolean isBlocked(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED) {
            if (v.contains(blocked)) return true;
        }
        return false;
    }

    private void kick(Player player, String reason) {
        getLogger().warning(player.getName() + " détecté (" + reason + ")");
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) player.kick(KICK_MESSAGE);
        });
    }
}
