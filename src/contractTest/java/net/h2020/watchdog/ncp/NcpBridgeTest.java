package net.h2020.watchdog.ncp;
import java.lang.reflect.*;
import fr.neatmonster.nocheatplus.hooks.*;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.access.IViolationInfo;
public class NcpBridgeTest {
 public static void main(String[] args) throws Exception {
  Class<?> bridge;
  try { bridge=Class.forName("net.h2020.watchdog.ncp.NcpBridge"); }
  catch(ClassNotFoundException e) { throw new AssertionError("NCP hook bridge is not implemented",e); }
  Class<?> sink=Class.forName("net.h2020.watchdog.ncp.NcpBridge$Sink");
  final Object[] seen=new Object[5]; final int[] count={0};
  Object s=Proxy.newProxyInstance(sink.getClassLoader(),new Class<?>[]{sink},(o,m,a)->{ if(m.getName().equals("onViolation")) { count[0]++; System.arraycopy(a,0,seen,0,a.length); } return null; });
  Object b=bridge.getConstructor(ClassLoader.class,sink).newInstance(NcpBridgeTest.class.getClassLoader(),s);
  bridge.getMethod("register").invoke(b);
  if(NCPHookManager.hook==null) throw new AssertionError("missing hook");
  org.bukkit.entity.Player player=new org.bukkit.entity.Player() {}; IViolationInfo info=new IViolationInfo(){public double getTotalVl(){return 19.5;} public double getAddedVl(){return 2.25;}};
  if(NCPHookManager.hook.onCheckFailure(CheckType.FIGHT_REACH,player,info)) throw new AssertionError("bridge must preserve NCP cancellations");
  if(count[0]!=1 || seen[0]!=player || !seen[1].toString().equals("FIGHT_REACH") || !seen[2].equals(19.5) || !seen[3].equals(2.25)) throw new AssertionError("wrong violation mapping");
  IViolationInfo next=new IViolationInfo(){public double getTotalVl(){return 20.0;} public double getAddedVl(){return 0.5;}};
  NCPHookManager.hook.onCheckFailure(CheckType.FIGHT_REACH,player,next);
  if(count[0]!=2 || !seen[2].equals(20.0) || !seen[3].equals(0.5)) throw new AssertionError("NCP totals were accumulated or delta lost");
  if(NCPHookManager.hook.onCheckFailure(CheckType.CHAT_TEXT,player,info)) throw new AssertionError("unknown NCP check must not be suppressed");
  if(count[0]!=3 || !seen[1].toString().equals("NCP_UNMAPPED") || !seen[4].equals("CHAT_TEXT")) throw new AssertionError("unmapped event must preserve native name");
  NCPHookManager.hook.onCheckFailure(CheckType.NET_KEEPALIVEFREQUENCY,player,info);
  if(count[0]!=4 || !seen[1].toString().equals("NET_KEEPALIVE") || !seen[4].equals("NET_KEEPALIVEFREQUENCY")) throw new AssertionError("keepalive alias not mapped");
  bridge.getMethod("unregister").invoke(b);
  // Off-thread hook callbacks enqueue all host/Bukkit work without waiting.
  java.util.List<Runnable> queue=new java.util.ArrayList<>();
  java.util.List<Double> delivered=new java.util.ArrayList<>();
  Object asyncSink=Proxy.newProxyInstance(sink.getClassLoader(),new Class<?>[]{sink},(o,m,a)->{ if(m.getName().equals("onViolation")) delivered.add((Double)a[2]); return null; });
  java.util.function.Consumer<Runnable> dispatch=queue::add;
  Object async=bridge.getConstructor(ClassLoader.class,sink,java.util.function.Consumer.class).newInstance(NcpBridgeTest.class.getClassLoader(),asyncSink,dispatch);
  bridge.getMethod("register").invoke(async);
  if(NCPHookManager.hook.onCheckFailure(CheckType.FIGHT_REACH,player,info)) throw new AssertionError("must preserve NCP cancellation");
  NCPHookManager.hook.onCheckFailure(CheckType.FIGHT_REACH,player,next);
  if(!delivered.isEmpty() || queue.size()!=2) throw new AssertionError("host work executed on producer thread");
  queue.forEach(Runnable::run);
  if(!delivered.equals(java.util.Arrays.asList(19.5,20.0))) throw new AssertionError("lost or reordered authoritative totals");
  bridge.getMethod("unregister").invoke(async);
  if(NCPHookManager.hook!=null) throw new AssertionError("hook leaked");
  Class<?> policy;
  try { policy=Class.forName("net.h2020.watchdog.ncp.PunishmentPolicy"); }
  catch(ClassNotFoundException e) { throw new AssertionError("automatic punishment policy is missing",e); }
  Method allow=policy.getMethod("allowAutomatic",boolean.class,boolean.class);
  if((Boolean)allow.invoke(null,false,false)) throw new AssertionError("real players must be observed by default");
  if(!(Boolean)allow.invoke(null,true,false)) throw new AssertionError("bot punishment must remain enabled");
  if(!(Boolean)allow.invoke(null,false,true)) throw new AssertionError("explicit human opt-in must work");
  Method unknown=policy.getMethod("allowUnmapped",double.class,double.class,boolean.class,boolean.class);
  if((Boolean)unknown.invoke(null,100.0,0.0,true,false)) throw new AssertionError("unknown must be disabled by default");
  if((Boolean)unknown.invoke(null,99.0,100.0,true,false)) throw new AssertionError("below configured threshold");
  if(!(Boolean)unknown.invoke(null,100.0,100.0,true,false)) throw new AssertionError("explicit threshold must punish bots");
  if((Boolean)unknown.invoke(null,100.0,100.0,false,false)) throw new AssertionError("unknown human protection");
  if((Boolean)unknown.invoke(null,Double.POSITIVE_INFINITY,1.0,true,false)) throw new AssertionError("non-finite total");
  // Regression: /wd reset invalidates queued pre-reset callbacks.
  final java.util.List<Double> reset=new java.util.ArrayList<>();
  Object resetSink=Proxy.newProxyInstance(sink.getClassLoader(),new Class<?>[]{sink},(o,m,a)->{ if(m.getName().equals("onViolation")) reset.add((Double)a[2]); return null; });
  final java.util.List<Runnable> resetQueue=new java.util.ArrayList<>();
  Object resetBridge=bridge.getConstructor(ClassLoader.class,sink,java.util.function.Consumer.class).newInstance(NcpBridgeTest.class.getClassLoader(),resetSink,(java.util.function.Consumer<Runnable>)resetQueue::add);
  bridge.getMethod("register").invoke(resetBridge);
  java.util.UUID testId=java.util.UUID.randomUUID();
  org.bukkit.entity.Player resetPlayer=new org.bukkit.entity.Player(){ public java.util.UUID getUniqueId(){ return testId; } };
  IViolationInfo before=new IViolationInfo(){ public CheckType getCheckType(){ return CheckType.MOVING_SURVIVALFLY; } public boolean willCancel(){ return false; } public double getTotalVl(){ return 50.0; } public double getAddedVl(){ return 50.0; } };
  NCPHookManager.hook.onCheckFailure(CheckType.MOVING_SURVIVALFLY,resetPlayer,before);
  if(resetQueue.size()!=1 || !reset.isEmpty()) throw new AssertionError("TEST_RESET setup");
  bridge.getMethod("invalidate",java.util.UUID.class).invoke(resetBridge,testId);
  IViolationInfo after=new IViolationInfo(){ public CheckType getCheckType(){ return CheckType.MOVING_SURVIVALFLY; } public boolean willCancel(){ return false; } public double getTotalVl(){ return 51.0; } public double getAddedVl(){ return 1.0; } };
  NCPHookManager.hook.onCheckFailure(CheckType.MOVING_SURVIVALFLY,resetPlayer,after);
  if(resetQueue.size()!=2) throw new AssertionError("TEST_RESET post-reset not queued");
  resetQueue.forEach(Runnable::run);
  if(!reset.equals(java.util.Arrays.asList(51.0))) throw new AssertionError("TEST_RESET pre-reset callback not dropped: "+reset);
  bridge.getMethod("unregister").invoke(resetBridge);
  // Bukkit can run separately scheduled tasks out of insertion order at a tick boundary.
  Class<?> ordered=Class.forName("net.h2020.watchdog.ncp.OrderedMainDispatcher");
  java.util.List<Runnable> ticks=new java.util.ArrayList<>();
  java.util.List<Integer> result=new java.util.ArrayList<>();
  java.util.function.Consumer<Runnable> scheduler=ticks::add;
  @SuppressWarnings("unchecked") java.util.function.Consumer<Runnable> dispatcher=(java.util.function.Consumer<Runnable>)ordered.getConstructor(java.util.function.Consumer.class).newInstance(scheduler);
  for(int i=0;i<20;i++){ final int n=i; dispatcher.accept(()->result.add(n)); }
  if(ticks.size()!=1 || !result.isEmpty()) throw new AssertionError("expected one asynchronous drain");
  ticks.remove(0).run();
  for(int i=0;i<20;i++) if(result.get(i)!=i) throw new AssertionError("lost/reordered event");
  // Direct-action mediation never invokes Bukkit on the producer thread or permits NCP to act.
  Class<?> direct=Class.forName("net.h2020.watchdog.ncp.HostDirectActionPolicy");
  java.util.List<Runnable> actions=new java.util.ArrayList<>();
  java.util.List<String> reasons=new java.util.ArrayList<>();
  java.util.function.Consumer<Runnable> enqueue=actions::add;
  java.util.function.BiConsumer<org.bukkit.entity.Player,fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Reason> handler=(p,r)->reasons.add(r.name());
  fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Policy actionPolicy=(fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Policy)direct.getConstructor(java.util.function.Consumer.class,java.util.function.BiConsumer.class).newInstance(enqueue,handler);
  for(fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Reason r:fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Reason.values()) if(actionPolicy.allow(player,r)) throw new AssertionError("direct action permitted");
  if(!reasons.isEmpty() || actions.size()!=fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Reason.values().length) throw new AssertionError("direct callback did Bukkit work on producer");
  actions.forEach(Runnable::run);
  if(reasons.size()!=actions.size()) throw new AssertionError("lost direct request");
  java.util.function.Consumer<Runnable> broken=x->{throw new IllegalStateException("scheduler unavailable");};
  actionPolicy=(fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Policy)direct.getConstructor(java.util.function.Consumer.class,java.util.function.BiConsumer.class).newInstance(broken,handler);
  if(actionPolicy.allow(player,fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy.Reason.HOVER_KICK)) throw new AssertionError("failed open");
  System.out.println("PASS NCP hook mapping, authoritative total/added, cancellation preservation, unregister and punishment policy");
 }
}
