package fr.celeste.scoreboard;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
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

    private record Role(String id, String name, String symbol, TextColor color,
                        int weight, boolean op, String parent, List<String> permissions) {}

    private static final String DEFAULT_ROLES_YML = """
            # Roles de Celeste SMP : modifie ce fichier librement, puis tape /rolesreload en jeu.
            # poids   : plus il est grand, plus le role est haut (un poids different par role).
            # herite  : le role recoit aussi les permissions du role indique.
            # op      : true = le joueur est operateur (op) tant qu'il a ce role.
            roles:
              owner:
                nom: "Owner"
                symbole: "\\u2726"
                couleur: "#FFD166"
                poids: 100
                op: true
                herite: "admin"
                permissions: []
              admin:
                nom: "Admin"
                symbole: "\\u2726"
                couleur: "#FF6B6B"
                poids: 90
                herite: "modo"
                permissions:
                  - "celeste.roles.manage"
                  - "celeste.modprobe"
                  - "minecraft.command.gamerule"
                  - "minecraft.command.time"
                  - "minecraft.command.weather"
                  - "minecraft.command.give"
                  - "minecraft.command.clear"
                  - "minecraft.command.effect"
                  - "minecraft.command.kill"
                  - "minecraft.command.difficulty"
                  - "minecraft.command.whitelist"
                  - "minecraft.command.enchant"
                  - "minecraft.command.experience"
                  - "minecraft.command.summon"
              modo:
                nom: "Mod\\u00E9rateur"
                symbole: "\\u2727"
                couleur: "#4DA3FF"
                poids: 70
                herite: "helper"
                permissions:
                  - "celeste.modbypass"
                  - "minecraft.command.kick"
                  - "minecraft.command.ban"
                  - "minecraft.command.ban-ip"
                  - "minecraft.command.pardon"
                  - "minecraft.command.pardon-ip"
                  - "minecraft.command.banlist"
                  - "minecraft.command.gamemode"
              helper:
                nom: "Helper"
                symbole: "\\u2727"
                couleur: "#7FDBFF"
                poids: 50
                herite: "vipplus"
                permissions:
                  - "minecraft.command.teleport"
                  - "minecraft.command.tp"
              vipplus:
                nom: "VIP+"
                symbole: "\\u2756"
                couleur: "#C792EA"
                poids: 30
                herite: "vip"
                permissions:
                  - "celeste.vipplus"
              vip:
                nom: "VIP"
                symbole: "\\u2756"
                couleur: "#5EEAD4"
                poids: 20
                herite: "joueur"
                permissions:
                  - "celeste.vip"
              joueur:
                nom: "Joueur"
                symbole: ""
                couleur: "#E6E6E6"
                poids: 0
                permissions:
                  - "celeste.joueur"
            """;

    private final JavaPlugin plugin;
    private volatile Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> assigned = new ConcurrentHashMap<>();
    private final Set<UUID> opGranted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public RoleManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        plugin.getDataFolder().mkdirs();
        loadRoles();
        loadPlayers();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        CommandMap map = Bukkit.getCommandMap();
        map.register("celeste", new Cmd("ajouter", "add", "Ajouter un role a un joueur", "celeste.roles.manage"));
        map.register("celeste", new Cmd("retirer", "remove", "Retirer un role a un joueur", "celeste.roles.manage"));
        map.register("celeste", new Cmd("roles", "roles", "Voir les roles", null));
        map.register("celeste", new Cmd("rolesreload", "reload", "Recharger roles.yml", "celeste.roles.manage"));

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
        if (kind.equals("roles")) {
            showRoles(s, args);
        } else if (kind.equals("reload")) {
            if (!canManage(s)) {
                s.sendMessage(err("Tu n'as pas la permission."));
                return true;
            }
            loadRoles();
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
            s.sendMessage(ok("roles.yml recharg\u00E9."));
        } else {
            change(s, label, args, kind.equals("add"));
        }
        return true;
    }

    private void change(CommandSender s, String label, String[] args, boolean add) {
        if (!canManage(s)) {
            s.sendMessage(err("Tu n'as pas la permission."));
            return;
        }
        if (args.length != 2) {
            s.sendMessage(err("Usage : /" + label + " <pseudo> <role>"));
            return;
        }

        // l'ordre est libre : /ajouter Pseudo vip   ou   /ajouter vip Pseudo
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
        if (role.id().equals(DEFAULT_ROLE)) {
            s.sendMessage(err("Tout le monde est Joueur par d\u00E9faut."));
            return;
        }

        OfflinePlayer target = findPlayer(pseudo);
        if (target == null) {
            s.sendMessage(err("Joueur introuvable (il doit s'\u00EAtre d\u00E9j\u00E0 connect\u00E9 au serveur)."));
            return;
        }

        if (!isBoss(s)) {
            int mine = top(((Player) s).getUniqueId()).weight();
            if (mine <= role.weight()) {
                s.sendMessage(err("Tu ne peux pas g\u00E9rer le r\u00F4le " + role.name() + "."));
                return;
            }
            if (top(target.getUniqueId()).weight() >= mine) {
                s.sendMessage(err("Ce joueur est au-dessus de toi."));
                return;
            }
        }

        String display = target.getName() != null ? target.getName() : pseudo;
        Set<String> set = assigned.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (add) {
            if (!set.add(role.id())) {
                s.sendMessage(err(display + " a d\u00E9j\u00E0 le r\u00F4le " + role.name() + "."));
                return;
            }
        } else {
            if (!set.remove(role.id())) {
                s.sendMessage(err(display + " n'a pas le r\u00F4le " + role.name() + "."));
                return;
            }
        }
        save();

        Player online = target.getPlayer();
        if (online != null) {
            apply(online);
            if (add) {
                online.sendMessage(Component.text()
                        .append(Component.text("\u2726 Tu as re\u00E7u le r\u00F4le ", SKY))
                        .append(Component.text(role.name(), role.color()).decorate(TextDecoration.BOLD))
                        .build());
            } else {
                online.sendMessage(Component.text()
                        .append(Component.text("\u2726 Le r\u00F4le ", SKY))
                        .append(Component.text(role.name(), role.color()).decorate(TextDecoration.BOLD))
                        .append(Component.text(" t'a \u00E9t\u00E9 retir\u00E9.", SKY))
                        .build());
            }
        } else {
            reconcileOp(target);
        }

        s.sendMessage(ok(add
                ? display + " est maintenant " + role.name() + "."
                : display + " n'est plus " + role.name() + "."));
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
                line.append(Component.text(list.get(i).name(), list.get(i).color()));
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
        if (kind.equals("reload")) return out;
        String cur = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        if (kind.equals("roles")) {
            if (args.length == 1) addPlayers(out, cur);
            return out;
        }
        if (args.length == 1) {
            addPlayers(out, cur);
            addRoles(out, cur);
        } else if (args.length == 2) {
            if (roles.containsKey(args[0].toLowerCase(Locale.ROOT))) {
                addPlayers(out, cur);
            } else if (kind.equals("remove")) {
                OfflinePlayer t = findPlayer(args[0]);
                if (t != null) {
                    Set<String> has = assigned.get(t.getUniqueId());
                    if (has != null) {
                        for (String id : has) {
                            if (id.startsWith(cur)) out.add(id);
                        }
                    }
                }
            } else {
                addRoles(out, cur);
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
            if (!id.equals(DEFAULT_ROLE) && id.startsWith(cur)) out.add(id);
        }
    }

    private String roleList() {
        List<String> ids = new ArrayList<>();
        for (String id : roles.keySet()) {
            if (!id.equals(DEFAULT_ROLE)) ids.add(id);
        }
        return String.join(", ", ids);
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

    private boolean canManage(CommandSender s) {
        return isBoss(s) || s.hasPermission("celeste.roles.manage");
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
                map.put(id, new Role(id, r.getString("nom", key), r.getString("symbole", ""),
                        color, r.getInt("poids", 0), r.getBoolean("op", false),
                        r.getString("herite", ""), r.getStringList("permissions")));
            }
        }
        if (!map.containsKey(DEFAULT_ROLE)) {
            map.put(DEFAULT_ROLE, new Role(DEFAULT_ROLE, "Joueur", "", TextColor.color(0xE6E6E6),
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

    /** Permissions du role + celles des roles dont il herite. */
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

    // ============================================================ APPLICATION

    private void apply(Player p) {
        UUID id = p.getUniqueId();
        PermissionAttachment old = attachments.remove(id);
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
        PermissionAttachment att = p.addAttachment(plugin);
        for (Role r : rolesOf(id)) {
            for (String perm : allPerms(r)) att.setPermission(perm, true);
        }
        attachments.put(id, att);

        reconcileOp(p);
        p.recalculatePermissions();
        p.updateCommands();
        p.playerListName(tabName(p));
        refreshTeams();
    }

    /** Un role "op" rend operateur ; on ne retire l'op que si c'est nous qui l'avons donne. */
    private void reconcileOp(OfflinePlayer o) {
        UUID id = o.getUniqueId();
        boolean wantOp = false;
        for (Role r : rolesOf(id)) {
            if (r.op()) wantOp = true;
        }
        if (wantOp && !o.isOp()) {
            o.setOp(true);
            opGranted.add(id);
            save();
        } else if (!wantOp && opGranted.remove(id)) {
            o.setOp(false);
            save();
        }
    }

    // ================================================================ AFFICHAGE
    /** Role affiche dans le scoreboard (symbole + nom, couleur du role). */
    public Component rankDisplay(Player p) {
        Role r = top(p.getUniqueId());
        String text = (r.symbol().isEmpty() ? "" : r.symbol() + " ") + r.name();
        return Component.text(text, r.color());
    }
    private Component chatPrefix(Role r) {
        String text = (r.symbol().isEmpty() ? "" : r.symbol() + " ") + r.name().toUpperCase(Locale.ROOT);
        Component c = Component.text(text, r.color());
        return r.weight() > 0 ? c.decorate(TextDecoration.BOLD) : c;
    }

    private Component tabName(Player p) {
        Role r = top(p.getUniqueId());
        return Component.text()
                .append(chatPrefix(r))
                .append(Component.text(" \u2502 ", NamedTextColor.DARK_GRAY))
                .append(Component.text(p.getName(), NamedTextColor.WHITE))
                .build();
    }

    private Component nameplatePrefix(Role r) {
        return r.symbol().isEmpty() ? Component.empty() : Component.text(r.symbol() + " ", r.color());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> {
            Role r = top(source.getUniqueId());
            return Component.text()
                    .append(chatPrefix(r))
                    .append(Component.text(" \u2502 ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(source.getName(), r.color()))
                    .append(Component.text(" \u00BB ", NamedTextColor.GRAY))
                    .append(message.colorIfAbsent(NamedTextColor.WHITE))
                    .build();
        }));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        apply(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) apply(p);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player quitting = e.getPlayer();
        attachments.remove(quitting.getUniqueId());
        String name = quitting.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(quitting)) continue;
            for (Team t : new ArrayList<>(viewer.getScoreboard().getTeams())) {
                if (isRoleTeam(t) && t.hasEntry(name)) t.removeEntry(name);
            }
        }
    }

    // ===================================== NOMS AU-DESSUS DES TETES + TRI DU TAB

    private boolean isRoleTeam(Team t) {
        return t.getName().matches("r\\d{3}.*");
    }

    /** Nom de team : plus le role est haut, plus le nom est petit, donc en haut du TAB. */
    private String teamName(Role r) {
        String id = r.id().length() > 8 ? r.id().substring(0, 8) : r.id();
        return String.format("r%03d%s", Math.max(0, 999 - r.weight()), id);
    }

    private void refreshTeams() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            Scoreboard sb = viewer.getScoreboard();
            for (Player target : online) {
                Role r = top(target.getUniqueId());
                String wanted = teamName(r);
                Team team = sb.getTeam(wanted);
                if (team == null) team = sb.registerNewTeam(wanted);
                team.prefix(nameplatePrefix(r));
                team.color(NamedTextColor.nearestTo(r.color()));

                String entry = target.getName();
                for (Team other : new ArrayList<>(sb.getTeams())) {
                    if (isRoleTeam(other) && !other.getName().equals(wanted) && other.hasEntry(entry)) {
                        other.removeEntry(entry);
                    }
                }
                if (!team.hasEntry(entry)) team.addEntry(entry);
            }
        }
    }
}
