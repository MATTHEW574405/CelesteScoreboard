package fr.celeste.scoreboard;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoleManager implements Listener {

    private static final TextColor SKY = TextColor.color(0x87CEEB);
    private static final String DEFAULT_ROLE = "joueur";

    // A partir de quel role on peut utiliser /mettre et /vanish (change l'id si besoin)
    private static final String MANAGE_MIN_ROLE = "helper";
    private static final String VANISH_MIN_ROLE = "modo";

    private record Role(String id, String name, String symbol, TextColor color, TextColor color2,
                        int weight, boolean op, String parent, List<String> permissions) {}

    private static final String DEFAULT_ROLES_YML = """
            # Roles de Celeste SMP : modifie ce fichier librement, puis tape /rolesreload en jeu.
            # couleur / couleur2 : debut et fin du degrade (codes hexadecimaux).
            # poids   : plus il est grand, plus le role est haut (un poids different par role).
            # herite  : le role recoit aussi les permissions du role indique.
            # op      : true = le joueur est operateur (op) tant qu'il a ce role.
            # permissions : une permission par ligne. Deux raccourcis :
            #   "plugin:NomDuPlugin" = toutes les permissions declarees par ce plugin
            #   "*"                  = toutes les permissions de tous les plugins
            roles:
              owner:
                nom: "Owner"
                symbole: "\\u2605"
                couleur: "#FF0080"
                couleur2: "#FFB300"
                poids: 100
                op: true
                herite: "admin"
                permissions:
                  - "*"
              admin:
                nom: "Admin"
                symbole: "\\u2726"
                couleur: "#FF3B30"
                couleur2: "#FF9500"
                poids: 90
                herite: "modo"
                permissions:
                  - "celeste.modprobe"
                  - "lightbans.ban"
                  - "lightbans.ipban"
                  - "lightbans.muteip"
                  - "lightbans.unban"
                  - "lightbans.unmuteip"
                  - "lightbans.softban"
                  - "lightbans.blacklist"
                  - "lightbans.banhistory"
                  - "lightbans.mutehistory"
                  - "lightbans.userhistory"
                  - "minecraft.command.ban"
                  - "minecraft.command.ban-ip"
                  - "minecraft.command.pardon"
                  - "minecraft.command.pardon-ip"
                  - "minecraft.command.banlist"
                  - "coreprotect.rollback"
                  - "coreprotect.restore"
                  - "plugin:InvSeePlusPlus"
                  - "plugin:InventoryRollbackPlus"
                  - "voicechat.admin"
              modo:
                nom: "Mod\\u00E9rateur"
                symbole: "\\u2727"
                couleur: "#00C6FF"
                couleur2: "#0072FF"
                poids: 70
                herite: "helper"
                permissions:
                  - "celeste.modbypass"
                  - "lightbans.mod"
                  - "lightbans.tempban"
                  - "lightbans.mute"
                  - "lightbans.unmute"
                  - "lightbans.kick"
                  - "lightbans.unwarn"
                  - "lightbans.alts"
                  - "minecraft.command.kick"
                  - "minecraft.command.gamemode"
                  - "coreprotect.inspect"
                  - "coreprotect.lookup"
                  - "coreprotect.help"
              helper:
                nom: "Helper"
                symbole: "\\u2666"
                couleur: "#00F5A0"
                couleur2: "#00D9F5"
                poids: 50
                herite: "vipplus"
                permissions:
                  - "lightbans.warn"
                  - "lightbans.tempmute"
                  - "lightbans.history"
                  - "lightbans.check"
                  - "minecraft.command.teleport"
                  - "minecraft.command.tp"
              vipplus:
                nom: "VIP+"
                symbole: "\\u2756"
                couleur: "#B721FF"
                couleur2: "#21D4FD"
                poids: 30
                herite: "vip"
                permissions:
                  - "celeste.vipplus"
              vip:
                nom: "VIP"
                symbole: "\\u2606"
                couleur: "#FFD200"
                couleur2: "#F7971E"
                poids: 20
                herite: "joueur"
                permissions:
                  - "celeste.vip"
              joueur:
                nom: "Joueur"
                symbole: ""
                couleur: "#87CEEB"
                couleur2: "#FFFFFF"
                poids: 0
                permissions:
                  - "celeste.joueur"
            """;

    private final JavaPlugin plugin;
    private volatile Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> assigned = new ConcurrentHashMap<>();
    private final Set<UUID> opGranted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public RoleManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Appele a l'arret : retire nos commandes, nos permissions et le vanish. */
    public void disable() {
        Bukkit.getCommandMap().getKnownCommands().values().removeIf(c -> c instanceof Cmd);
        for (UUID id : new ArrayList<>(vanished)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unvanish(p, false);
        }
        for (Map.Entry<UUID, PermissionAttachment> e : attachments.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                try {
                    p.removeAttachment(e.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        attachments.clear();
    }

    public void enable() {
        plugin.getDataFolder().mkdirs();
        loadRoles();
        loadPlayers();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        CommandMap map = Bukkit.getCommandMap();
        map.register("celeste", new Cmd("mettre", "set", "Definir le role d'un joueur", "celeste.roles.manage"));
        map.register("celeste", new Cmd("roles", "roles", "Voir les roles", null));
        map.register("celeste", new Cmd("rolesreload", "reload", "Recharger roles.yml", null));
        map.register("celeste", new Cmd("rolesperms", "perms", "Voir les permissions d'un plugin", null));
        map.register("celeste", new Cmd("vanish", "vanish", "Devenir invisible", "celeste.vanish"));

        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTeams, 40L, 100L);
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
    }

    // ================================================================ COMMANDES

    private final class Cmd extends Command {
        private final String kind;

        Cmd(String name, String kind, String description, String permission) {
            super(name);
            this.kind = kind;
            setDescription(description);
            if (permission != null) setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return run(kind, sender, label, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return complete(kind, args);
        }
    }

    private boolean run(String kind, CommandSender s, String label, String[] args) {
        switch (kind) {
            case "roles" -> showRoles(s, args);
            case "reload" -> {
                if (!isBoss(s)) {
                    s.sendMessage(err("Tu n'as pas la permission."));
                    return true;
                }
                loadRoles();
                for (Player p : Bukkit.getOnlinePlayers()) apply(p);
                s.sendMessage(ok("roles.yml recharg\u00E9."));
            }
            case "perms" -> {
                if (!isBoss(s)) {
                    s.sendMessage(err("Tu n'as pas la permission."));
                    return true;
                }
                if (args.length < 1) {
                    s.sendMessage(err("Usage : /rolesperms <plugin>"));
                    return true;
                }
                Set<String> nodes = expand("plugin:" + args[0]);
                if (nodes.isEmpty()) {
                    s.sendMessage(err("Aucune permission d\u00E9clar\u00E9e par " + args[0]
                            + " (plugin introuvable, ou il n'en d\u00E9clare pas)."));
                    return true;
                }
                s.sendMessage(ok(nodes.size() + " permissions pour " + args[0] + " :"));
                int shown = 0;
                for (String n : nodes) {
                    if (shown >= 40) {
                        s.sendMessage(Component.text("  ... et " + (nodes.size() - 40) + " autres", NamedTextColor.GRAY));
                        break;
                    }
                    s.sendMessage(Component.text("  " + n, NamedTextColor.GRAY));
                    shown++;
                }
            }
            case "vanish" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(err("Commande r\u00E9serv\u00E9e aux joueurs."));
                    return true;
                }
                if (!canVanish(p)) {
                    s.sendMessage(err("Tu n'as pas la permission."));
                    return true;
                }
                toggleVanish(p);
            }
            default -> setRole(s, label, args);
        }
        return true;
    }

    /** /mettre <pseudo> <role> : remplace le role du joueur (joueur = on remet par defaut). */
    private void setRole(CommandSender s, String label, String[] args) {
        if (!canManage(s)) {
            s.sendMessage(err("Tu n'as pas la permission."));
            return;
        }
        if (args.length != 2) {
            s.sendMessage(err("Usage : /" + label + " <pseudo> <role>"));
            return;
        }

        // l'ordre est libre : /mettre Pseudo vip   ou   /mettre vip Pseudo
        Role role = roles.get(args[1].toLowerCase(Locale.ROOT));
        String pseudo = args[0];
        if (role == null) {
            role = roles.get(args[0].toLowerCase(Locale.ROOT));
            pseudo = args[1];
        }
        if (role == null) {
            s.sendMessage(err("R\u00F4le inconnu. R\u00F4les : " + roleList()));
            return;
        }

        OfflinePlayer target = findPlayer(pseudo);
        if (target == null) {
            s.sendMessage(err("Joueur introuvable (il doit s'\u00EAtre d\u00E9j\u00E0 connect\u00E9 au serveur)."));
            return;
        }

        boolean self = s instanceof Player sp && sp.getUniqueId().equals(target.getUniqueId());
        if (!isBoss(s)) {
            int mine = top(((Player) s).getUniqueId()).weight();
            if (mine <= role.weight()) {
                s.sendMessage(err("Tu ne peux pas donner le r\u00F4le " + role.name() + "."));
                return;
            }
            if (!self && top(target.getUniqueId()).weight() >= mine) {
                s.sendMessage(err("Ce joueur est au-dessus de toi."));
                return;
            }
        }

        UUID id = target.getUniqueId();
        if (role.id().equals(DEFAULT_ROLE)) {
            assigned.remove(id);
        } else {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.add(role.id());
            assigned.put(id, set);
        }
        save();

        String display = target.getName() != null ? target.getName() : pseudo;
        Player online = target.getPlayer();
        if (online != null) {
            apply(online);
            online.sendMessage(Component.text()
                    .append(Component.text("\u2726 Ton r\u00F4le est maintenant ", SKY))
                    .append(gradient(role.name(), role.color(), role.color2(), true))
                    .build());
        } else {
            reconcileOp(target);
        }
        s.sendMessage(ok(display + " est maintenant " + role.name() + "."));
    }

    private void showRoles(CommandSender s, String[] args) {
        if (args.length >= 1) {
            OfflinePlayer t = findPlayer(args[0]);
            if (t == null) {
                s.sendMessage(err("Joueur introuvable."));
                return;
            }
            TextComponent.Builder line = Component.text();
            line.append(Component.text("\u2726 " + (t.getName() != null ? t.getName() : args[0]) + " : ", SKY));
            List<Role> list = rolesOf(t.getUniqueId());
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) line.append(Component.text(", ", NamedTextColor.GRAY));
                line.append(gradient(list.get(i).name(), list.get(i).color(), list.get(i).color2(), true));
            }
            s.sendMessage(line.build());
            return;
        }

        s.sendMessage(Component.text("\u2726 R\u00F4les du serveur", SKY).decorate(TextDecoration.BOLD));
        List<Role> all = new ArrayList<>(roles.values());
        all.sort(Comparator.<Role>comparingInt(Role::weight).reversed());
        for (Role r : all) {
            s.sendMessage(Component.text("  ").append(chatPrefix(r)));
        }
    }

    private List<String> complete(String kind, String[] args) {
        List<String> out = new ArrayList<>();
        String cur = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        if (kind.equals("roles")) {
            if (args.length == 1) addPlayers(out, cur);
        } else if (kind.equals("perms")) {
            if (args.length == 1) {
                for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
                    if (pl.getName().toLowerCase(Locale.ROOT).startsWith(cur)) out.add(pl.getName());
                }
            }
        } else if (kind.equals("set")) {
            if (args.length == 1) {
                addPlayers(out, cur);
                addRoles(out, cur);
            } else if (args.length == 2) {
                if (roles.containsKey(args[0].toLowerCase(Locale.ROOT))) addPlayers(out, cur);
                else addRoles(out, cur);
            }
        }
        return out;
    }

    private void addPlayers(List<String> out, String cur) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(cur)) out.add(p.getName());
        }
    }

    private void addRoles(List<String> out, String cur) {
        for (String id : roles.keySet()) {
            if (id.startsWith(cur)) out.add(id);
        }
    }

    private String roleList() {
        return String.join(", ", roles.keySet());
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    /** Console et ops peuvent tout faire. */
    private boolean isBoss(CommandSender s) {
        return !(s instanceof Player p) || p.isOp();
    }

    private int minWeight(String roleId) {
        Role r = roles.get(roleId);
        return r != null ? r.weight() : Integer.MAX_VALUE;
    }

    private boolean canManage(CommandSender s) {
        if (isBoss(s)) return true;
        Player p = (Player) s;
        return p.hasPermission("celeste.roles.manage")
                || top(p.getUniqueId()).weight() >= minWeight(MANAGE_MIN_ROLE);
    }

    private boolean canVanish(Player p) {
        return p.isOp() || p.hasPermission("celeste.vanish");
    }

    private Component ok(String msg) {
        return Component.text()
                .append(Component.text("\u2726 ", SKY))
                .append(Component.text(msg, NamedTextColor.WHITE))
                .build();
    }

    private Component err(String msg) {
        return Component.text("\u2726 " + msg, NamedTextColor.RED);
    }

    // ================================================================ DONNEES

    private void loadRoles() {
        File file = new File(plugin.getDataFolder(), "roles.yml");
        if (!file.exists()) {
            try {
                Files.writeString(file.toPath(), DEFAULT_ROLES_YML, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("Impossible de creer roles.yml : " + ex.getMessage());
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Map<String, Role> map = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("roles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection r = section.getConfigurationSection(key);
                if (r == null) continue;
                String id = key.toLowerCase(Locale.ROOT);
                TextColor color = TextColor.fromHexString(r.getString("couleur", "#FFFFFF"));
                if (color == null) color = NamedTextColor.WHITE;
                TextColor color2 = TextColor.fromHexString(r.getString("couleur2", ""));
                if (color2 == null) color2 = color;
                map.put(id, new Role(id, r.getString("nom", key), r.getString("symbole", ""),
                        color, color2, r.getInt("poids", 0), r.getBoolean("op", false),
                        r.getString("herite", ""), r.getStringList("permissions")));
            }
        }
        if (!map.containsKey(DEFAULT_ROLE)) {
            map.put(DEFAULT_ROLE, new Role(DEFAULT_ROLE, "Joueur", "", SKY, NamedTextColor.WHITE,
                    0, false, "", List.of("celeste.joueur")));
        }
        roles = map;
    }

    private void loadPlayers() {
        File file = new File(plugin.getDataFolder(), "players.yml");
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = cfg.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (String r : ps.getStringList(key)) set.add(r.toLowerCase(Locale.ROOT));
                    assigned.put(id, set);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (String s : cfg.getStringList("op-granted")) {
            try {
                opGranted.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : assigned.entrySet()) {
            if (!e.getValue().isEmpty()) {
                cfg.set("players." + e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        List<String> granted = new ArrayList<>();
        for (UUID id : opGranted) granted.add(id.toString());
        cfg.set("op-granted", granted);
        try {
            cfg.save(new File(plugin.getDataFolder(), "players.yml"));
        } catch (IOException ex) {
            plugin.getLogger().warning("Impossible d'enregistrer players.yml : " + ex.getMessage());
        }
    }

    private List<Role> rolesOf(UUID id) {
        List<Role> out = new ArrayList<>();
        Map<String, Role> current = roles;
        Set<String> ids = assigned.get(id);
        if (ids != null) {
            for (String rid : ids) {
                Role r = current.get(rid);
                if (r != null) out.add(r);
            }
        }
        Role def = current.get(DEFAULT_ROLE);
        if (def != null && !out.contains(def)) out.add(def);
        out.sort(Comparator.<Role>comparingInt(Role::weight).reversed());
        return out;
    }

    private Role top(UUID id) {
        return rolesOf(id).get(0);
    }

    /** Permissions (brutes) du role + celles des roles dont il herite. */
    private Set<String> allPerms(Role r) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Role cur = r;
        while (cur != null && seen.add(cur.id())) {
            out.addAll(cur.permissions());
            cur = cur.parent().isBlank() ? null : roles.get(cur.parent().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** "*" = toutes les permissions des plugins ; "plugin:Nom" = celles declarees par ce plugin. */
    private Set<String> expand(String token) {
        Set<String> out = new LinkedHashSet<>();
        String t = token.trim();
        if (t.equals("*")) {
            for (Permission perm : Bukkit.getPluginManager().getPermissions()) out.add(perm.getName());
        } else if (t.toLowerCase(Locale.ROOT).startsWith("plugin:")) {
            Plugin target = Bukkit.getPluginManager().getPlugin(t.substring(7).trim());
            if (target != null) {
                try {
                    for (Permission perm : target.getDescription().getPermissions()) out.add(perm.getName());
                } catch (Exception ignored)
