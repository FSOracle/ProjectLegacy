package net.h2020.friend;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

public final class FriendStoreTest {
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Object call(Object s,String n,Class<?>[] types,Object... args) throws Exception {
  try {return s.getClass().getMethod(n,types).invoke(s,args);}
  catch(NoSuchMethodException e){throw new AssertionError("Missing behavior: "+n,e);}
  catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause(); throw e;}
 }
 static final Class<?>[] REQUEST={UUID.class,UUID.class,long.class};
 static final Class<?>[] PAIR={UUID.class,UUID.class};
 static boolean request(Object s,String n,UUID a,UUID b,long t)throws Exception{return (Boolean)call(s,n,REQUEST,a,b,t);}
 static boolean pair(Object s,String n,UUID a,UUID b)throws Exception{return (Boolean)call(s,n,PAIR,a,b);}
 public static void main(String[] x)throws Exception{
  Path dir=Files.createTempDirectory("friend-store-"); Path path=dir.resolve("friends.dat");
  UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),c=UUID.fromString("00000000-0000-0000-0000-000000000003");
  Class<?> cls=Class.forName("net.h2020.friend.FriendStore"); Object s=cls.getConstructor(Path.class).newInstance(path);
  ok(!request(s,"invite",a,a,100),"self invitation denied");
  ok(request(s,"invite",a,b,100),"request created"); ok(!request(s,"invite",a,b,101),"duplicate rejected");
  ok(!request(s,"accept",c,a,102),"only requested recipient can accept");
  ok(request(s,"accept",b,a,102),"accept request"); ok(pair(s,"areFriends",a,b)&&pair(s,"areFriends",b,a),"mutual friendship");
  s=cls.getConstructor(Path.class).newInstance(path); ok(pair(s,"areFriends",a,b),"persisted friendship");
  ok(pair(s,"remove",a,b),"remove"); ok(!pair(s,"areFriends",a,b),"removed friendship");
  ok(request(s,"invite",a,c,200),"request c"); ok(request(s,"deny",c,a,201),"deny"); ok(!request(s,"accept",c,a,202),"denied request invalid");
  ok(request(s,"invite",a,c,300),"new request"); ok(!request(s,"accept",c,a,300+86400001L),"expired request rejected");
  // Concurrent requests for the same pair must create exactly one pending request.
  final Object shared=s; final java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();
  java.util.List<Thread> threads=new ArrayList<>();
  final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
  for(int i=0;i<16;i++){Thread t=new Thread(() -> {try {if(request(shared,"invite",b,c,500))accepted.incrementAndGet();}catch(Exception e){error.compareAndSet(null,e);}});threads.add(t);t.start();}
  for(Thread t:threads)t.join(); ok(error.get()==null,"threads succeeded");ok(accepted.get()==1,"concurrent duplicate requests serialized");
  // Break the target directory, then verify failed persistence does not publish an in-memory friendship.
  Path moved=dir.resolveSibling(dir.getFileName()+"-saved");Files.move(dir,moved);Files.write(dir,new byte[]{1});
  boolean failed=false;try{request(s,"accept",c,b,501);}catch(java.io.IOException expected){failed=true;}
  ok(failed,"write failure reported");ok(!pair(s,"areFriends",b,c),"write failure rolls back memory");
  Files.delete(dir);Files.move(moved,dir);ok(request(s,"accept",c,b,502),"request remains after write failure");
  byte[] bytes=Files.readAllBytes(path); bytes[bytes.length-1]^=1; Files.write(path,bytes);
  boolean corrupt=false; try { cls.getConstructor(Path.class).newInstance(path); } catch(InvocationTargetException e){corrupt=true;}
  ok(corrupt,"corrupt store refused"); ok(Arrays.equals(bytes,Files.readAllBytes(path)),"corrupt file not overwritten");
  System.out.println("PASS invite accept deny remove expiration reload corruption");
 }
}
