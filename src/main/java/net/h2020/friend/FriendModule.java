package net.h2020.friend;

import net.h2020.api.Module;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.util.UUID;

public final class FriendModule implements Module, CommandExecutor {
 private FriendStore store;
 private final java.util.Map<UUID,Long> lastInvite=new java.util.HashMap<>();
 public String name(){ return "friend"; }
 public void enable(JavaPlugin plugin){
  Object configured=plugin.getConfig().get("friend.request-ttl-seconds");
  if(configured!=null && !(configured instanceof Integer || configured instanceof Long))throw new IllegalArgumentException("friend.request-ttl-seconds must be a positive integer");
  long seconds=configured==null?300L:((Number)configured).longValue();
  if(seconds<1 || seconds>31536000L)throw new IllegalArgumentException("friend.request-ttl-seconds must be between 1 and 31536000");
  try {store=new FriendStore(plugin.getDataFolder().toPath().resolve("friends.dat"),seconds*1000L);}
  catch(IOException e){throw new IllegalStateException("Friend data cannot be loaded",e);}
  if(plugin.getCommand("friend")==null)throw new IllegalStateException("friend command not declared");
  plugin.getCommand("friend").setExecutor(this);
  net.h2020.api.H2020.register(net.h2020.api.friend.FriendView.class,store);
 }
 public void disable(){net.h2020.api.H2020.unregister(net.h2020.api.friend.FriendView.class,store);lastInvite.clear();}
 public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
  if(!(sender instanceof Player)){sender.sendMessage("Players only.");return true;}
  Player player=(Player)sender;
  if(args.length==1 && args[0].equalsIgnoreCase("requests")){
   java.util.Set<UUID> requests=store.requestsFor(player.getUniqueId(),System.currentTimeMillis());
   player.sendMessage("Friend requests ("+requests.size()+"):");int shown=0;
   for(UUID id:requests){if(shown++>=50){player.sendMessage("List truncated after 50 entries.");break;}
    Player online=Bukkit.getPlayer(id);String name=online!=null?online.getName():Bukkit.getOfflinePlayer(id).getName();
    player.sendMessage(name==null?id.toString():name);
   }return true;
  }
  if(args.length==1 && args[0].equalsIgnoreCase("list")){
   java.util.Set<UUID> friends=store.friendsOf(player.getUniqueId());
   player.sendMessage("Friends ("+friends.size()+"):");int shown=0;
   for(UUID id:friends){if(shown++>=50){player.sendMessage("List truncated after 50 entries.");break;}
    Player online=Bukkit.getPlayer(id);String name=online!=null?online.getName():Bukkit.getOfflinePlayer(id).getName();
    player.sendMessage((name==null?id.toString():name)+(online==null?" (offline)":" (online)"));
   }return true;
  }
  if(args.length==2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny") || args[0].equalsIgnoreCase("remove"))){
   Player target=null;
   for(Player online:Bukkit.getOnlinePlayers())if(online.getName().equalsIgnoreCase(args[1])){
    if(target!=null){sender.sendMessage("Name is ambiguous.");return true;}target=online;
   }
   if(target==null){sender.sendMessage("Player not online.");return true;}
   try {
    if(args[0].equalsIgnoreCase("add")){
     long now=System.nanoTime();lastInvite.entrySet().removeIf(e -> now-e.getValue()>=2000000000L);
     if(lastInvite.containsKey(player.getUniqueId())){player.sendMessage("Slow down before sending another friend request.");return true;}
    }
    if(args[0].equalsIgnoreCase("remove")){
     if(store.remove(player.getUniqueId(),target.getUniqueId())){
      player.sendMessage("Removed "+target.getName()+" from your friends.");
      target.sendMessage(player.getName()+" removed you from friends.");
     } else player.sendMessage("You are not friends with "+target.getName()+".");
    }else if(args[0].equalsIgnoreCase("deny")){
     if(store.deny(player.getUniqueId(),target.getUniqueId(),System.currentTimeMillis())) player.sendMessage("Denied friend request from "+target.getName()+".");
     else player.sendMessage("No active request from "+target.getName()+".");
    }else if(args[0].equalsIgnoreCase("accept")){
     if(store.accept(player.getUniqueId(),target.getUniqueId(),System.currentTimeMillis())){
      player.sendMessage("You are now friends with "+target.getName()+".");
      target.sendMessage("You are now friends with "+player.getName()+".");
     } else player.sendMessage("No active request from "+target.getName()+".");
    }else if(store.invite(player.getUniqueId(),target.getUniqueId(),System.currentTimeMillis())){
     lastInvite.put(player.getUniqueId(),System.nanoTime());
     sender.sendMessage("Friend request sent to "+target.getName()+".");
     target.sendMessage("Friend request from "+player.getName()+".");
    }else sender.sendMessage("Friend request not sent.");
   }catch(IOException e){sender.sendMessage("Friend data could not be saved; no change made.");}
   return true;
  }
  sender.sendMessage("/friend add <player> | /friend accept <player> | /friend deny <player>");
  sender.sendMessage("/friend remove <player> | /friend list | /friend requests"); return true;
 }
}
