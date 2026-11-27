package fr.celeste.scoreboard;

final class RoleDefaults {

    private RoleDefaults() {
    }

    static final String YML = """
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
}
