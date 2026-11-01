package net.h2020.core.lobby;

import net.h2020.core.Core;
import net.h2020.api.H2020;
import net.h2020.api.friend.FriendView;
import net.h2020.social.api.PartyService;
import org.bukkit.Bukkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.h2020.core.gui.ItemBuilder;
import net.h2020.core.gui.Menu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Social information; commands remain authoritative for social operations. */
final class SocialMenus {
    private final Core core;
    SocialMenus(Core core) { this.core = core; }
    void open(final Player owner) {
        if (!core.lobby.isLobby(owner.getWorld()) || core.games.gameByWorld(owner.getWorld()) != null) return;
        Menu menu = new Menu("Social", 3);
        menu.set(11, new ItemBuilder(Material.BOOK).name(ChatColor.GREEN + "Friends")
                .lore(ChatColor.GRAY + "/friend add <player>", ChatColor.GRAY + "/friend requests",
                      ChatColor.GRAY + "/friend accept <player>", ChatColor.GRAY + "/friend deny <player>",
                      ChatColor.GRAY + "/friend remove <player>", ChatColor.YELLOW + "Click to view friends").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (valid(owner, player, type)) friends(player, 0);
            }
        });
        menu.set(12, new ItemBuilder(Material.EMPTY_MAP).name(ChatColor.GREEN + "Friend Requests")
                .lore(ChatColor.GRAY + "Accept or deny pending requests.", ChatColor.YELLOW + "Click to view requests").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (valid(owner, player, type)) requests(player);
            }
        });
        menu.set(13, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + "Party")
                .lore(ChatColor.GRAY + "/party invite <player>", ChatColor.GRAY + "/party accept <player>",
                      ChatColor.GRAY + "/party list", ChatColor.GRAY + "/party for more commands",
                      ChatColor.YELLOW + "Click to view party").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (valid(owner, player, type)) party(player, 0);
            }
        });
        menu.set(15, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Guild unavailable")
                .lore(ChatColor.GRAY + "Guilds are not implemented.").build());
        menu.set(22, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Close").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (player.getUniqueId().equals(owner.getUniqueId()) && type == ClickType.LEFT) player.closeInventory();
            }
        });
        menu.open(owner);
    }
    private Player uniqueOnline(UUID id) {
        Player target = Bukkit.getPlayer(id);
        if (target == null || !target.isOnline() || !id.equals(target.getUniqueId())
                || !target.getName().matches("[A-Za-z0-9_]{1,16}")) return null;
        int matches = 0;
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getName().equalsIgnoreCase(target.getName())) matches++;
        return matches == 1 ? target : null;
    }
    private boolean valid(Player owner, Player player, ClickType type) {
        return type == ClickType.LEFT && player.isOnline() && player.getUniqueId().equals(owner.getUniqueId())
                && core.lobby.isLobby(player.getWorld()) && core.games.gameByWorld(player.getWorld()) == null;
    }
    private void requests(final Player owner) {
        FriendView view = H2020.find(FriendView.class);
        if (view == null) { owner.sendMessage(ChatColor.RED + "Friends are unavailable."); return; }
        List<UUID> ids = new ArrayList<UUID>(view.requestsFor(owner.getUniqueId(), System.currentTimeMillis()));
        Collections.sort(ids);
        Menu menu = new Menu("Friend Requests", 6);
        int end = Math.min(ids.size(), 45);
        for (int i = 0; i < end; i++) {
            final UUID id = ids.get(i); Player online = Bukkit.getPlayer(id);
            String name = online != null && online.isOnline() ? online.getName() : Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = id.toString();
            menu.set(i, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + name)
                    .lore(ChatColor.GREEN + "Left-click to accept", ChatColor.RED + "Right-click to deny").build(), new Menu.Click() {
                @Override public void click(Player player, ClickType type) {
                    if (type != ClickType.LEFT && type != ClickType.RIGHT) return;
                    if (!valid(owner, player, ClickType.LEFT)) return;
                    FriendView current = H2020.find(FriendView.class);
                    if (current == null || !current.requestsFor(player.getUniqueId(), System.currentTimeMillis()).contains(id)) {
                        player.sendMessage(ChatColor.RED + "Request no longer exists."); requests(player); return;
                    }
                    Player live = uniqueOnline(id);
                    if (live == null) { player.sendMessage(ChatColor.RED + "Sender must be online."); return; }
                    player.performCommand("friend " + (type == ClickType.LEFT ? "accept " : "deny ") + live.getName());
                    requests(player);
                }
            });
        }
        if (ids.isEmpty()) menu.set(22, new ItemBuilder(Material.PAPER).name(ChatColor.GRAY + "No pending requests").build());
        menu.set(49, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Back").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) open(player); }
        });
        menu.open(owner);
    }
    private void party(final Player owner, int requestedPage) {
        PartyService service = H2020.find(PartyService.class);
        if (service == null) { owner.sendMessage(ChatColor.RED + "Party is unavailable."); return; }
        List<UUID> ids = service.membersOf(owner.getUniqueId());
        boolean inParty = ids.size() > 1 || service.isLeader(owner.getUniqueId());
        final int page = Math.min(Math.max(0, requestedPage), Math.max(0, (ids.size() - 1) / 45));
        Menu menu = new Menu("Party", 6);
        int end = inParty ? Math.min(ids.size(), (page + 1) * 45) : 0;
        for (int i = page * 45; i < end; i++) {
            UUID id = ids.get(i); Player online = Bukkit.getPlayer(id);
            String name = online != null && online.isOnline() ? online.getName() : Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = id.toString();
            menu.set(i - page * 45, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + name)
                    .lore(i == 0 ? ChatColor.GOLD + "Leader" : ChatColor.GRAY + "Member",
                          online != null && online.isOnline() ? ChatColor.GREEN + "Online" : ChatColor.GRAY + "Offline").build());
        }
        if (inParty) {
            final boolean leader = service.isLeader(owner.getUniqueId());
            menu.set(50, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + (leader ? "Disband party" : "Leave party")).build(), new Menu.Click() {
                @Override public void click(Player player, ClickType type) {
                    if (!valid(owner, player, type)) return;
                    PartyService now = H2020.find(PartyService.class);
                    if (now == null) { player.sendMessage(ChatColor.RED + "Party is unavailable."); return; }
                    boolean isLeader = now.isLeader(player.getUniqueId());
                    boolean member = now.membersOf(player.getUniqueId()).size() > 1 || isLeader;
                    if (!member) { player.sendMessage(ChatColor.RED + "You are not in a party."); party(player, 0); return; }
                    if (isLeader != leader) { player.sendMessage(ChatColor.RED + "Party changed; reopen."); party(player, 0); return; }
                    player.closeInventory();
                    player.performCommand(isLeader ? "party disband" : "party leave");
                }
            });
        }
        if (!inParty) menu.set(22, new ItemBuilder(Material.PAPER).name(ChatColor.GRAY + "Not in a party")
                .lore(ChatColor.GRAY + "/party invite <player>", ChatColor.GRAY + "/party accept <player>").build());
        if (page > 0) menu.set(45, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Previous page").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) party(player, page - 1); }
        });
        if (end < ids.size() && inParty) menu.set(53, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Next page").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) party(player, page + 1); }
        });
        menu.set(49, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Back").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) open(player); }
        });
        menu.open(owner);
    }
    private void friendActions(final Player owner, final UUID id, final int page) {
        Player target = Bukkit.getPlayer(id);
        String name = target != null && target.isOnline() ? target.getName() : Bukkit.getOfflinePlayer(id).getName();
        if (name == null) name = id.toString();
        Menu menu = new Menu("Friend Actions", 6);
        menu.set(20, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + "Invite to party")
                .lore(ChatColor.GRAY + "Invites this online friend via /party.", ChatColor.YELLOW + "Click to invite").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (!valid(owner, player, type)) return;
                FriendView current = H2020.find(FriendView.class);
                if (current == null || !current.areFriends(player.getUniqueId(), id)) {
                    player.sendMessage(ChatColor.RED + "Friendship no longer exists."); return;
                }
                if (H2020.find(PartyService.class) == null) { player.sendMessage(ChatColor.RED + "Party is unavailable."); return; }
                Player live = uniqueOnline(id);
                if (live == null) { player.sendMessage(ChatColor.RED + "Friend is offline or unavailable."); return; }
                player.performCommand("party invite " + live.getName());
            }
        });
        menu.set(22, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + name)
                .lore(target != null && target.isOnline() ? ChatColor.GREEN + "Online" : ChatColor.GRAY + "Offline").build());
        menu.set(24, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Remove friend")
                .lore(ChatColor.GRAY + "Asks for confirmation first.").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (!valid(owner, player, type)) return;
                FriendView current = H2020.find(FriendView.class);
                if (current == null || !current.areFriends(player.getUniqueId(), id)) {
                    player.sendMessage(ChatColor.RED + "Friendship no longer exists."); friends(player, page); return;
                }
                confirmRemove(player, id, page);
            }
        });
        menu.set(49, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Back").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) friends(player, page); }
        });
        menu.open(owner);
    }
    private void confirmRemove(final Player owner, final UUID id, final int page) {
        Player target = Bukkit.getPlayer(id);
        String name = target != null && target.isOnline() ? target.getName() : Bukkit.getOfflinePlayer(id).getName();
        if (name == null) name = id.toString();
        Menu menu = new Menu("Remove Friend?", 3);
        menu.set(11, new ItemBuilder(Material.WOOL, 1, 5).name(ChatColor.GREEN + "Confirm remove " + name).build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) {
                if (!valid(owner, player, type)) return;
                FriendView current = H2020.find(FriendView.class);
                if (current == null || !current.areFriends(player.getUniqueId(), id)) {
                    player.sendMessage(ChatColor.RED + "Friendship no longer exists."); player.closeInventory(); return;
                }
                Player live = uniqueOnline(id);
                if (live == null) { player.sendMessage(ChatColor.RED + "Friend must be online to be removed."); return; }
                player.closeInventory();
                player.performCommand("friend remove " + live.getName());
            }
        });
        menu.set(15, new ItemBuilder(Material.WOOL, 1, 14).name(ChatColor.RED + "Cancel").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) friendActions(player, id, page); }
        });
        menu.open(owner);
    }
    private void friends(final Player owner, int requestedPage) {
        FriendView view = H2020.find(FriendView.class);
        if (view == null) { owner.sendMessage(ChatColor.RED + "Friends are unavailable."); return; }
        List<UUID> ids = new ArrayList<UUID>(view.friendsOf(owner.getUniqueId()));
        Collections.sort(ids);
        final int page = Math.min(Math.max(0, requestedPage), Math.max(0, (ids.size() - 1) / 45));
        Menu menu = new Menu("Friends", 6);
        int end = Math.min(ids.size(), (page + 1) * 45);
        for (int i = page * 45; i < end; i++) {
            final UUID id = ids.get(i); Player online = Bukkit.getPlayer(id);
            String name = online != null && online.isOnline() ? online.getName() : Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = id.toString();
            menu.set(i - page * 45, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + name)
                    .lore(online != null && online.isOnline() ? ChatColor.GREEN + "Online" : ChatColor.GRAY + "Offline",
                          ChatColor.YELLOW + "Click for details").build(), new Menu.Click() {
                @Override public void click(Player player, ClickType type) {
                    if (!valid(owner, player, type)) return;
                    FriendView current = H2020.find(FriendView.class);
                    if (current == null || !current.areFriends(player.getUniqueId(), id)) {
                        player.sendMessage(ChatColor.RED + "Friendship no longer exists."); friends(player, page); return;
                    }
                    friendActions(player, id, page);
                }
            });
        }
        if (page > 0) menu.set(45, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Previous page").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) friends(player, page - 1); }
        });
        if (end < ids.size()) menu.set(53, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Next page").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) friends(player, page + 1); }
        });
        menu.set(49, new ItemBuilder(Material.ARROW).name(ChatColor.YELLOW + "Back").build(), new Menu.Click() {
            @Override public void click(Player player, ClickType type) { if (valid(owner, player, type)) open(player); }
        });
        menu.open(owner);
    }
}
