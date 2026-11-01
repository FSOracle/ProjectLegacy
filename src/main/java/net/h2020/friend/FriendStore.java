package net.h2020.friend;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Preliminary single-process storage core; not wired to Bukkit. */
public final class FriendStore implements net.h2020.api.friend.FriendView {
 private static final int MAGIC=0x46323031, MAX=100000;
 public static final long DEFAULT_TTL_MILLIS=300000L;
 private final long ttlMillis;
 private final Path path;
 private State state;
 private static final class Pair {
  final UUID a,b;
  Pair(UUID a,UUID b){this.a=Objects.requireNonNull(a);this.b=Objects.requireNonNull(b);}
  public boolean equals(Object o){if(!(o instanceof Pair))return false; Pair p=(Pair)o;return a.equals(p.a)&&b.equals(p.b);}
  public int hashCode(){return 31*a.hashCode()+b.hashCode();}
 }
 private static Pair canonical(UUID a,UUID b){return a.compareTo(b)<0?new Pair(a,b):new Pair(b,a);}
 private static final class State {
  Set<Pair> friends=new HashSet<>(); Map<Pair,Long> requests=new HashMap<>();
  State copy(){State s=new State();s.friends.addAll(friends);s.requests.putAll(requests);return s;}
 }
 public FriendStore(Path path) throws IOException {this(path,DEFAULT_TTL_MILLIS);}
 public FriendStore(Path path,long ttlMillis) throws IOException {if(ttlMillis<=0)throw new IllegalArgumentException("TTL must be positive");this.ttlMillis=ttlMillis;this.path=path.toAbsolutePath();
  boolean exists;try {java.nio.file.attribute.BasicFileAttributes attrs=Files.readAttributes(this.path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!attrs.isRegularFile())throw new IOException("friend store is not a regular file"); exists=true;
  }catch(NoSuchFileException missing){exists=false;}
  state=exists?load():new State();}
 public synchronized boolean areFriends(UUID a,UUID b){return !a.equals(b)&&state.friends.contains(canonical(a,b));}
 public synchronized Set<UUID> friendsOf(UUID id){
  Set<UUID> result=new HashSet<>();for(Pair p:state.friends){if(p.a.equals(id))result.add(p.b);else if(p.b.equals(id))result.add(p.a);}return Collections.unmodifiableSet(result);
 }
 public synchronized Set<UUID> requestsFor(UUID id,long now){
  Set<UUID> result=new HashSet<>();for(Map.Entry<Pair,Long> e:state.requests.entrySet())if(e.getKey().b.equals(id)&&valid(e.getValue(),now))result.add(e.getKey().a);return Collections.unmodifiableSet(result);
 }
 public synchronized boolean invite(UUID from,UUID to,long now)throws IOException {
  if(from.equals(to)||areFriends(from,to))return false;
  Pair p=new Pair(from,to), reverse=new Pair(to,from); State s=state.copy();
  s.requests.entrySet().removeIf(e -> expired(e.getValue(),now));
  if(valid(s.requests.get(p),now)||valid(s.requests.get(reverse),now))return false;
  s.requests.remove(reverse);s.requests.put(p,now);commit(s);return true;
 }
 public synchronized boolean accept(UUID recipient,UUID from,long now)throws IOException {
  Pair p=new Pair(from,recipient); if(recipient.equals(from)||!valid(state.requests.get(p),now))return false;
  State s=state.copy();s.requests.remove(p);s.requests.remove(new Pair(recipient,from));s.friends.add(canonical(recipient,from));commit(s);return true;
 }
 public synchronized boolean deny(UUID recipient,UUID from,long now)throws IOException {
  Pair p=new Pair(from,recipient);if(!valid(state.requests.get(p),now))return false;
  State s=state.copy();s.requests.remove(p);commit(s);return true;
 }
 public synchronized boolean remove(UUID a,UUID b)throws IOException {
  Pair p=canonical(a,b);if(!state.friends.contains(p))return false; State s=state.copy();s.friends.remove(p);commit(s);return true;
 }
 public synchronized int purgeExpired(long now)throws IOException {
  State s=state.copy();int before=s.requests.size();s.requests.entrySet().removeIf(e -> expired(e.getValue(),now));
  int removed=before-s.requests.size();if(removed>0)commit(s);return removed;
 }
 private boolean expired(long time,long now){return now>=time && (now-time<0 || now-time>=ttlMillis);}
 private boolean valid(Long time,long now){return time!=null&&now>=time&&now-time>=0&&now-time<ttlMillis;}
 private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
 private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
 private static byte[] digest(byte[] data)throws IOException{try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
 private void commit(State next)throws IOException {
  if(next.friends.size()>MAX||next.requests.size()>MAX)throw new IOException("store capacity reached");
  ByteArrayOutputStream buf=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buf);
  out.writeInt(MAGIC);out.writeInt(next.friends.size());for(Pair p:next.friends){uuid(out,p.a);uuid(out,p.b);}
  out.writeInt(next.requests.size());for(Map.Entry<Pair,Long> e:next.requests.entrySet()){uuid(out,e.getKey().a);uuid(out,e.getKey().b);out.writeLong(e.getValue());}out.flush();
  byte[] data=buf.toByteArray(),hash=digest(data);Files.createDirectories(path.getParent());Path tmp=Files.createTempFile(path.getParent(),".friends-",".tmp");
  try {try(FileOutputStream f=new FileOutputStream(tmp.toFile())){f.write(data);f.write(hash);f.getFD().sync();}
   Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;
  }finally{Files.deleteIfExists(tmp);}
 }
 private State load()throws IOException {
  long size=Files.size(path);if(size<44||size>8000044)throw new IOException("invalid friend store size");
  byte[] bytes=Files.readAllBytes(path), data=Arrays.copyOf(bytes,bytes.length-32), hash=Arrays.copyOfRange(bytes,bytes.length-32,bytes.length);
  if(!MessageDigest.isEqual(hash,digest(data)))throw new IOException("friend store checksum mismatch");
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));State s=new State();
  if(in.readInt()!=MAGIC)throw new IOException("friend store version");int count=in.readInt();if(count<0||count>MAX)throw new IOException("friend count");
  for(int i=0;i<count;i++){UUID a=uuid(in),b=uuid(in);if(a.equals(b)||!s.friends.add(canonical(a,b)))throw new IOException("invalid friendship");}
  count=in.readInt();if(count<0||count>MAX)throw new IOException("request count");
  for(int i=0;i<count;i++){UUID a=uuid(in),b=uuid(in);long t=in.readLong();if(a.equals(b)||s.friends.contains(canonical(a,b))||s.requests.put(new Pair(a,b),t)!=null)throw new IOException("invalid request");}
  if(in.available()!=0)throw new IOException("trailing friend store data");return s;
 }
}
