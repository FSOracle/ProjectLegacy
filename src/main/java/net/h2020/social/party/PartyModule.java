package net.h2020.social.party;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.core.CoreUtil;
import net.h2020.social.api.PartyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** All party mutations and Bukkit calls are serialized on the server thread. */
public final class PartyModule implements Module, PartyService, Listener, CommandExecutor {
    private static final long INVITE_NANOS = 60L * 1000000000L;
    private JavaPlugin plugin;
    private int maxMembers = 8, invitesPerMinute = 10;
    private final Map<UUID,ArrayDeque<Long>> inviteTimes = new HashMap<UUID,ArrayDeque<Long>>();
    private static int bounded(JavaPlugin p, String key, int def, int min, int max) {
        Object v = p.getConfig().get(key);
        if (v == null) return def;
        if (!(v instanceof Integer)) throw new IllegalArgumentException(key + " must be an integer");
        int n = (Integer) v;
        if (n < min || n > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return n;
    }
    /** True if this sender may send another invite now (sliding 60 s window). */
    private boolean inviteAllowed(UUID id) {
        long now = System.nanoTime();
        ArrayDeque<Long> q = inviteTimes.computeIfAbsent(id, k -> new ArrayDeque<Long>());
        while (!q.isEmpty() && now - q.peekFirst() >= 60L * 1000000000L) q.pollFirst();
        if (q.size() >= invitesPerMinute) return false;
        q.addLast(now); return true;
    }
    private static final class Party {
        final LinkedHashSet<UUID> members = new LinkedHashSet<UUID>();
        UUID leader;
        boolean allInvite, muted;
        Party(UUID leader) { this.leader=leader; members.add(leader); }
    }
    private static final class Invitation {
        final UUID inviter; final Party party; final long expires;
        Invitation(UUID inviter, Party party) { this.inviter=inviter; this.party=party; this.expires=System.nanoTime()+INVITE_NANOS; }
    }
    private final Map<UUID,Party> parties = new HashMap<UUID,Party>();
    private final Map<UUID,Map<UUID,Invitation>> invites = new HashMap<UUID,Map<UUID,Invitation>>();
    private final Map<UUID,List<UUID>> snapshots = new ConcurrentHashMap<UUID,List<UUID>>();
    private static final class Route {
        final UUID player; final Party party;
        Route(UUID player, Party party) { this.player=player; this.party=party; }
    }
    private final Map<Player,Route> partyChat = new ConcurrentHashMap<Player,Route>();
    private void refreshRoute(UUID id, Party party) {
        Player player=Bukkit.getPlayer(id);
        if(player!=null && partyChat.containsKey(player)) partyChat.put(player,new Route(id,party));
    }
    private void removeInvitesByInviter(UUID id) {
        for(Iterator<Map<UUID,Invitation>> it=invites.values().iterator();it.hasNext();) {
            Map<UUID,Invitation> m=it.next();m.remove(id);if(m.isEmpty())it.remove();
        }
    }
    @Override public String name() { return "party"; }
    @Override public void enable(JavaPlugin p) {
        plugin=p;
        maxMembers=bounded(p,"party.max-members",8,2,100);
        invitesPerMinute=bounded(p,"party.invites-per-minute",10,1,1000);
        if (p.getCommand("party")==null || p.getCommand("pchat")==null || p.getCommand("chat")==null)
            throw new IllegalStateException("Missing party command registrations");
        p.getCommand("party").setExecutor(this); p.getCommand("pchat").setExecutor(this); p.getCommand("chat").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this,p);
        H2020.register(PartyService.class,this);
    }
    @Override public void disable() {
        H2020.unregister(PartyService.class,this); HandlerList.unregisterAll(this);
        parties.clear();invites.clear();snapshots.clear();partyChat.clear();inviteTimes.clear();
    }
    @Override public List<UUID> membersOf(UUID player) {
        Objects.requireNonNull(player,"player"); List<UUID> list=snapshots.get(player);
        return list==null ? Collections.singletonList(player) : list;
    }
    @Override public boolean isLeader(UUID player) {
        Objects.requireNonNull(player,"player"); List<UUID> list=snapshots.get(player);
        return list!=null && player.equals(list.get(0));
    }
    private void publish(Party p) {
        List<UUID> ordered=new ArrayList<UUID>(); ordered.add(p.leader);
        for(UUID id:p.members) if(!id.equals(p.leader)) ordered.add(id);
        List<UUID> immutable=Collections.unmodifiableList(ordered);
        for(UUID id:p.members) snapshots.put(id,immutable);
    }
    private void msg(Player p,String s) { p.sendMessage(ChatColor.YELLOW+"Party: "+ChatColor.RESET+s); }
    private Player online(UUID id) { Player p=Bukkit.getPlayer(id); return p!=null && p.isOnline() && !CoreUtil.isBot(p) ? p : null; }
    private Player resolve(String name) {
        Player p=Bukkit.getPlayerExact(name);
        return p!=null && p.isOnline() && !CoreUtil.isBot(p) ? p : null;
    }
    private void broadcast(Party party,String message) {
        for(UUID id:party.members) { Player p=online(id); if(p!=null) msg(p,message); }
    }
    private boolean leader(Player player,Party p) {
        if(p==null || !p.leader.equals(player.getUniqueId())) { msg(player,"Only the party leader can do that.");return false; }
        return true;
    }
    private void clearInvites(UUID target) { invites.remove(target); }
    private void removeInvites(Party party) {
        for(Iterator<Map<UUID,Invitation>> it=invites.values().iterator();it.hasNext();) {
            Map<UUID,Invitation> m=it.next(); m.values().removeIf(i->i.party==party);if(m.isEmpty())it.remove();
        }
    }
    private void dissolve(Party p,String message) {
        if(message!=null) broadcast(p,message);
        for(UUID id:p.members) { parties.remove(id);snapshots.remove(id);refreshRoute(id,null); }
        removeInvites(p);p.members.clear();
    }
    private void leave(UUID id,boolean quit) {
        clearInvites(id); Party p=parties.get(id); if(p==null)return;
        String name=Optional.ofNullable(Bukkit.getPlayer(id)).map(Player::getName).orElse("Player");
        if(p.leader.equals(id)) { dissolve(p,name+" disbanded the party.");return; }
        p.members.remove(id);parties.remove(id);snapshots.remove(id);refreshRoute(id,null);
        removeInvitesByInviter(id);
        broadcast(p,name+" left the party.");publish(p);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void quit(PlayerQuitEvent e) { Player p=e.getPlayer();inviteTimes.remove(p.getUniqueId());partyChat.remove(p);leave(p.getUniqueId(),true); }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void chat(AsyncPlayerChatEvent e) {
        final Player player=e.getPlayer(); final Route route=partyChat.get(player); if(route==null)return;
        e.setCancelled(true); final String text=e.getMessage();
        Bukkit.getScheduler().runTask(plugin,()-> {
            Player p=online(route.player);
            if(p==player && partyChat.get(player)==route && route.party!=null && parties.get(route.player)==route.party) sendParty(p,text);
            else if(p==player) msg(p,"Party changed; message was not sent.");
        });
    }
    private void sendParty(Player sender,String message) {
        Party p=parties.get(sender.getUniqueId());
        if(p==null) { msg(sender,"You are not in a party. Use /chat a to return to public chat.");return; }
        if(p.muted && !p.leader.equals(sender.getUniqueId())) { msg(sender,"The party is muted.");return; }
        String clean=ChatColor.stripColor(message); broadcast(p,"Party > "+sender.getName()+": "+clean);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(!(sender instanceof Player)) { sender.sendMessage("Players only.");return true; }
        Player actor=(Player)sender;
        if(CoreUtil.isBot(actor)) return true;
        UUID id=actor.getUniqueId();String command=cmd.getName().toLowerCase(Locale.ROOT);
        if(command.equals("chat")) {
            if(args.length!=1 || !(args[0].equalsIgnoreCase("p") || args[0].equalsIgnoreCase("a"))) { msg(actor,"Usage: /chat p|a");return true; }
            if(args[0].equalsIgnoreCase("a")) { partyChat.remove(actor);msg(actor,"Now using public chat."); }
            else if(!parties.containsKey(id)) msg(actor,"You are not in a party.");
            else { partyChat.put(actor,new Route(id,parties.get(id)));msg(actor,"Now using party chat."); }
            return true;
        }
        if(command.equals("pchat")) {
            if(args.length==0)msg(actor,"Usage: /pchat <message>");else sendParty(actor,String.join(" ",args));
            return true;
        }
        if(args.length==0) { msg(actor,"Commands: invite, accept, deny, leave, list, promote, kick, disband, settings allinvite, mute");return true; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        // Reserved subcommands retain precedence over a player with the same name.
        if (args.length == 1 && !Arrays.asList("invite", "accept", "deny", "leave", "list", "promote", "kick", "disband", "settings", "mute").contains(sub)) {
            args = new String[] { "invite", args[0] };
            sub = "invite";
        }
        Party p=parties.get(id);
        if(sub.equals("invite")) {
            if(args.length!=2) { msg(actor,"Usage: /party invite <player>");return true; }
            Player target=resolve(args[1]);
            if(target==null) {msg(actor,"Player not found, offline or unavailable.");return true;}
            UUID tid=target.getUniqueId();
            if(id.equals(tid)) {msg(actor,"You cannot invite yourself.");return true;}
            if(parties.containsKey(tid)) {msg(actor,"That player is already in a party.");return true;}
            if(p!=null && !p.leader.equals(id) && !p.allInvite) {msg(actor,"Only the leader can invite.");return true;}
            if(p!=null && p.members.size()>=maxMembers) {msg(actor,"The party is full (max "+maxMembers+" members).");return true;}
            if(!inviteAllowed(id)) {msg(actor,"You are sending too many invites. Try again in a minute.");return true;}
            if(p==null) {p=new Party(id);parties.put(id,p);refreshRoute(id,p);publish(p);}
            Map<UUID,Invitation> m=invites.computeIfAbsent(tid,k->new HashMap<UUID,Invitation>());
            Invitation old=m.get(id);
            if(old!=null && old.party==p && System.nanoTime()-old.expires<0) {msg(actor,"Invitation already pending.");return true;}
            m.put(id,new Invitation(id,p));msg(actor,"Invited "+target.getName()+" to the party.");msg(target,actor.getName()+" invited you to a party. Use /party accept "+actor.getName()+" (60 seconds).");return true;
        }
        if(sub.equals("accept")||sub.equals("deny")) {
            if(args.length!=2) {msg(actor,"Usage: /party "+sub+" <player>");return true;}
            Player inviter=resolve(args[1]);
            if(inviter==null) {msg(actor,"Inviter is offline or unavailable.");return true;}
            UUID iid=inviter.getUniqueId();Map<UUID,Invitation> m=invites.get(id);Invitation i=m==null?null:m.get(iid);
            if(i==null || System.nanoTime()-i.expires>=0 || parties.get(iid)!=i.party || (!i.party.leader.equals(iid)&&!i.party.allInvite)) {
                if(m!=null){m.remove(iid);if(m.isEmpty())invites.remove(id);} msg(actor,"No valid invitation.");return true;
            }
            m.remove(iid);if(m.isEmpty())invites.remove(id);
            if(sub.equals("deny")){msg(actor,"Invitation denied.");msg(inviter,actor.getName()+" denied your invitation.");return true;}
            if(parties.containsKey(id)) {msg(actor,"You are already in a party.");return true;}
            if(i.party.members.size()>=maxMembers) {msg(actor,"That party is full.");return true;}
            clearInvites(id);i.party.members.add(id);parties.put(id,i.party);refreshRoute(id,i.party);publish(i.party);broadcast(i.party,actor.getName()+" joined the party.");return true;
        }
        if(sub.equals("leave")) { if(p==null)msg(actor,"You are not in a party.");else {msg(actor,"You left the party.");leave(id,false);}return true; }
        if(sub.equals("list")) {
            if(p==null)msg(actor,"You are not in a party.");else {StringBuilder b=new StringBuilder("Leader: ");Player l=online(p.leader);b.append(l==null?p.leader:l.getName());b.append("; Members:");for(UUID mid:p.members)if(!mid.equals(p.leader)){Player q=online(mid);b.append(' ').append(q==null?mid:q.getName());}msg(actor,b.toString());}return true;
        }
        if(sub.equals("disband")) {if(leader(actor,p))dissolve(p,actor.getName()+" disbanded the party.");return true;}
        if(sub.equals("kick")||sub.equals("promote")) {
            if(!leader(actor,p))return true;
            if(args.length!=2) {msg(actor,"Usage: /party "+sub+" <player>");return true;}
            Player target=resolve(args[1]);if(target==null || !p.members.contains(target.getUniqueId()) || target.getUniqueId().equals(id)) {msg(actor,"Choose another online party member.");return true;}
            UUID tid=target.getUniqueId();
            if(sub.equals("promote")){removeInvites(p);p.leader=tid;publish(p);broadcast(p,target.getName()+" is now the party leader.");}
            else {msg(target,"You were kicked from the party.");p.members.remove(tid);parties.remove(tid);snapshots.remove(tid);refreshRoute(tid,null);clearInvites(tid);removeInvitesByInviter(tid);publish(p);broadcast(p,target.getName()+" was kicked from the party.");}
            return true;
        }
        if(sub.equals("settings")||sub.equals("mute")) {
            if(!leader(actor,p))return true;
            String setting;int i;
            if(sub.equals("settings")){if(args.length<2){msg(actor,"Usage: /party settings allinvite|mute [on|off]");return true;}setting=args[1].toLowerCase(Locale.ROOT);i=2;}
            else {setting="mute";i=1;}
            if(!(setting.equals("allinvite")||setting.equals("mute")) || args.length>i+1 || (args.length>i && !args[i].equalsIgnoreCase("on") && !args[i].equalsIgnoreCase("off"))){msg(actor,"Use allinvite|mute [on|off].");return true;}
            boolean current=setting.equals("mute")?p.muted:p.allInvite;boolean value=args.length>i?args[i].equalsIgnoreCase("on"):!current;
            if(setting.equals("mute"))p.muted=value;else { if(p.allInvite && !value) removeInvites(p); p.allInvite=value; }broadcast(p,setting+" is now "+(value?"on":"off")+".");return true;
        }
        msg(actor,"Unknown party subcommand.");return true;
    }
}
