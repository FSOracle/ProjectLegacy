package net.h2020.friend;
import java.nio.file.*;
import java.util.*;
public final class FriendPurgeTest {
 public static void main(String[] args)throws Exception{
  Class<?> c=Class.forName("net.h2020.friend.FriendStore"); Path path=Files.createTempDirectory("friendpurge").resolve("data");
  Object s=c.getConstructor(Path.class,long.class).newInstance(path,1000L); UUID a=UUID.randomUUID(),b=UUID.randomUUID(),d=UUID.randomUUID(),e=UUID.randomUUID();
  c.getMethod("invite",UUID.class,UUID.class,long.class).invoke(s,a,b,100L);
  c.getMethod("invite",UUID.class,UUID.class,long.class).invoke(s,d,e,900L);
  java.lang.reflect.Method purge;
  try{purge=c.getMethod("purgeExpired",long.class);}catch(NoSuchMethodException ex){throw new AssertionError("must purge all expired requests",ex);}
  int removed=(Integer)purge.invoke(s,1100L);if(removed!=1)throw new AssertionError("must remove exactly one expired request");
  s=c.getConstructor(Path.class,long.class).newInstance(path,1000L);
  if((Integer)purge.invoke(s,1100L)!=0)throw new AssertionError("purge must persist across restart");
  if(!(Boolean)c.getMethod("accept",UUID.class,UUID.class,long.class).invoke(s,e,d,1100L))throw new AssertionError("live request lost");
  System.out.println("PASS expired request cleanup persisted");
 }
}
