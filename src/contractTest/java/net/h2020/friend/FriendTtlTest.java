package net.h2020.friend;
import java.nio.file.*;
import java.util.*;
public final class FriendTtlTest {
 public static void main(String[] args)throws Exception{
  Class<?> c=Class.forName("net.h2020.friend.FriendStore"); Object store;
  try{store=c.getConstructor(Path.class,long.class).newInstance(Files.createTempDirectory("friendttl").resolve("data"),1000L);}
  catch(NoSuchMethodException e){throw new AssertionError("Request TTL must be configurable",e);}
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  c.getMethod("invite",UUID.class,UUID.class,long.class).invoke(store,a,b,100L);
  boolean accepted=(Boolean)c.getMethod("accept",UUID.class,UUID.class,long.class).invoke(store,b,a,1100L);
  if(accepted)throw new AssertionError("configured 1000ms TTL must expire at boundary");
  System.out.println("PASS configured TTL boundary");
 }
}
