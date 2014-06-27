package org.perf;


import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.topology.LocalTopologyManager;
import org.infinispan.topology.LocalTopologyManagerImpl;
import org.jgroups.Channel;
import org.jgroups.View;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.util.Util;

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests Infinispan perf with writes only. The key set for the writes are non conflicting, so that we have no
 * (TX) collisions. This is used to measure raw performance. Adding reads should make the test faster. Using a shared
 * key set for all Invokers will slow performance down.
 * @author Bela Ban
 */
public class Test {
    protected EmbeddedCacheManager   mgr;
    protected Cache<Integer,byte[]>  cache;
    protected TransactionManager     txmgr;
    protected Address                local_addr;
    protected boolean                sync=true;
    protected int                    num_threads=1;
    protected int                    num_rpcs=10000, msg_size=1000, print=num_rpcs / 10;
    protected final AtomicInteger    num_requests=new AtomicInteger(0);
    protected final AtomicInteger    num_reads=new AtomicInteger(0);
    protected final AtomicInteger    num_writes=new AtomicInteger(0);
    protected double                 read_percentage=0.8; // 80% reads, 20% writes
    protected static NumberFormat    f;

    static {
        f=NumberFormat.getNumberInstance();
        f.setGroupingUsed(false);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
    }



    protected void start(String config_file, String cache_name, String name, long uuid) throws Exception {
        try {
            mgr=new DefaultCacheManager(config_file);
            mgr.addListener(new MyListener());
            Transport transport=mgr.getTransport();
            if(transport instanceof CustomTransport) {
                if(uuid > 0)
                    ((CustomTransport)transport).setUUID(uuid);
                if(name != null)
                    ((CustomTransport)transport).setLogicalName(name);
            }

            cache=mgr.getCache(cache_name);
            if(transport instanceof CustomTransport) {
                Channel channel=((CustomTransport)transport).getChannel();
                if(channel != null)
                    channel.getProtocolStack().getTransport().registerProbeHandler(new IspnPerfTestProbeHandler());
            }


            txmgr=cache.getAdvancedCache().getTransactionManager();
            local_addr=cache.getAdvancedCache().getRpcManager().getAddress();

            if(!cache.isEmpty()) {
                int size=cache.size();
                if(size < 10)
                    System.out.println("cache already contains elements: " + cache.keySet());
                else
                    System.out.println("cache already contains " + size + " elements");
            }
            eventLoop();
        }
        catch(Throwable t) {
            t.printStackTrace();
        }
        if(cache != null)
            cache.stop();
        if(mgr != null)
            mgr.stop();
    }


    public void eventLoop() throws Throwable {
        int c;

        while(true) {
            System.out.print("[1] Invoke RPCs [2] Print view [3] Set sender threads (" + num_threads +
                               ") [4] Set num RPCs (" + num_rpcs + ") " +
                               "\n[5] Set msg size (" + Util.printBytes(msg_size) + ")" +
                               " [6] Print cache size [7] Print contents [8] Clear cache" +
                               "\n[9] Populate cache [v] Print versions" +
                               "\n[s] Toggle sync (" + sync + ") [r] Set read percentage (" + f.format(read_percentage) + ") " +
                               "\n[q] Quit\n");
            System.out.flush();
            System.in.skip(System.in.available());
            c=System.in.read();
            switch(c) {
                case -1:
                    break;
                case '1':
                    try {
                        invokeRpcs();
                    }
                    catch(Throwable t) {
                        System.err.println(t);
                    }
                    break;
                case '2':
                    printView();
                    break;
                case '3':
                    setSenderThreads();
                    break;
                case '4':
                    setNumMessages();
                    break;
                case '5':
                    setMessageSize();
                    break;
                case '6':
                    printCacheSize();
                    break;
                case '7':
                    printContents();
                    break;
                case '8':
                    clearCache();
                    break;
                case '9':
                    populateCache();
                    break;
                case 'v':
                    System.out.println("JGroups: " + org.jgroups.Version.printVersion() +
                                         ", Infinispan: " + org.infinispan.Version.printVersion() + "\n");
                    break;
                case 'r':
                    setReadPercentage();
                    break;
                case 's':
                    sync=!sync;
                    System.out.println("sync=" + sync);
                    break;
                case 'q': case'x':
                    return;
                default:
                    break;
            }
        }
    }


    protected void invokeRpcs() throws Throwable {
        num_requests.set(0);
        num_reads.set(0);
        num_writes.set(0);

        System.out.println("invoking " + num_rpcs + " RPCs of " + Util.printBytes(msg_size) +
                             ", sync=" + sync + ", transactional=" + (txmgr != null));

        // The first call needs to be synchronous with OOB !
        final CountDownLatch latch=new CountDownLatch(1);
        Invoker[] invokers=new Invoker[num_threads];
        for(int i=0; i < invokers.length; i++) {
            invokers[i]=new Invoker(latch);
            invokers[i].setName("invoker-" + i);
            invokers[i].start();
        }

        long start=System.currentTimeMillis();
        latch.countDown();

        for(Invoker invoker: invokers)
            invoker.join();
        long time=System.currentTimeMillis() - start;

        System.out.println("done invoking " + num_requests + " RPCs");

        double time_per_req=time / (double)num_requests.get();
        double reqs_sec=num_requests.get() / (time / 1000.0);
        double throughput=num_requests.get() * msg_size / (time / 1000.0);
        System.out.println(Util.bold("\ninvoked " + num_requests.get() + " requests in " + time + " ms: " + time_per_req + " ms/req, " +
                                       String.format("%.2f", reqs_sec) + " reqs/sec, " + Util.printBytes(throughput) +
                                       "/sec\n(" + num_reads + " reads, " + num_writes + " writes)\n"));
    }


    protected void printView() {
        Transport transport=cache.getAdvancedCache().getRpcManager().getTransport();
        int view_id=transport.getViewId();
        List<Address> members=transport.getMembers();
        String view=view_id + "|" + members;
        System.out.println("\n-- [" + local_addr + "] view: " + view + '\n');
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }



    protected void printCacheSize() {
        int size=cache.size();
        System.out.println("-- cache has " + size + " elements");
    }

    protected void printContents() {
        int size=cache.size();
        if(size < 500)
            System.out.println(cache.keySet());
        else
            System.out.println(size + " elements");
    }

    protected void clearCache() {
        cache.clear();
    }

    // Creates num_rpcs elements
    protected void populateCache() {
        byte[] buf={'b', 'e', 'l', 'a'};
        Flag[] flags=sync? new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_SYNCHRONOUS} :
          new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_ASYNCHRONOUS};

        for(int i=1; i <= num_rpcs; i++) {
            Transaction tx=null;
            try {
                if(txmgr != null) {
                    txmgr.begin();
                    tx=txmgr.getTransaction();
                }

                cache.getAdvancedCache().withFlags(flags).put(i, buf);
                num_writes.incrementAndGet();
                if(print > 0 && i > 0 && i % print == 0)
                    System.out.println("-- invoked " + i);
                if(tx != null)
                    tx.commit();
            }
            catch(Throwable t) {
                t.printStackTrace();
                if(tx != null) {
                    try {
                        tx.rollback();
                    }
                    catch(SystemException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    protected void setSenderThreads() throws Exception {
        int threads=Util.readIntFromStdin("Number of sender threads: ");
        int old=this.num_threads;
        this.num_threads=threads;
        System.out.println("sender threads set to " + num_threads + " (from " + old + ")");
    }

    protected void setNumMessages() throws Exception {
        num_rpcs=Util.readIntFromStdin("Number of RPCs: ");
        System.out.println("Set num_msgs=" + num_rpcs);
        print=num_rpcs / 10;
    }

    protected void setMessageSize() throws Exception {
        msg_size=Util.readIntFromStdin("Message size: ");
        System.out.println("set msg_size=" + msg_size);
    }

    protected void setReadPercentage() throws Exception {
        double tmp=Util.readDoubleFromStdin("Read percentage: ");
        if(tmp < 0 || tmp > 1.0)
            System.err.println("read percentage must be >= 0 or <= 1.0");
        else
            read_percentage=tmp;
    }


    protected class Invoker extends Thread {
        protected final CountDownLatch latch;


        public Invoker(CountDownLatch latch) {
            this.latch=latch;
        }

        public void run() {
            byte[] buf=new byte[msg_size];
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
                return;
            }

            for(;;) {
                int i=num_requests.incrementAndGet();
                if(i > num_rpcs) {
                    num_requests.decrementAndGet();
                    return;
                }

                // get a random key in range [0 .. num_rpcs-1]
                int key=(int)Util.random(num_rpcs) -1;
                boolean is_this_a_read=Util.tossWeightedCoin(read_percentage);

                // try the operation until it is successful
                while(true) {
                    Transaction tx=null;
                    try {
                        if(txmgr != null) {
                            txmgr.begin();
                            tx=txmgr.getTransaction();
                        }

                        Flag[] flags=sync? new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_SYNCHRONOUS} :
                          new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_ASYNCHRONOUS};
                        if(is_this_a_read) {
                            cache.getAdvancedCache().withFlags(flags).get(key);
                            num_reads.incrementAndGet();
                        }
                        else {
                            cache.getAdvancedCache().withFlags(flags).put(key, buf);
                            num_writes.incrementAndGet();
                        }

                        if(tx != null)
                            tx.commit();

                        if(print > 0 && i % print == 0)
                            System.out.println("-- invoked " + i);
                        break;
                    }
                    catch(Throwable t) {
                        t.printStackTrace();
                        if(tx != null) {
                            try {tx.rollback();} catch(SystemException e) {}
                        }
                    }
                }
            }
        }
    }

    protected class IspnPerfTestProbeHandler implements DiagnosticsHandler.ProbeHandler {
        protected static final String GET_ST="st", ENABLE_ST="enable-st", DISABLE_ST="disable-st",
          CACHE_SIZE="cache-size";

        public Map<String,String> handleProbe(String... keys) {
            Map<String,String> map=new HashMap<String,String>();
            for(String key: keys) {
                if(GET_ST.equals(key))
                    map.put(GET_ST, String.valueOf(isRebalancingEnabled()));
                if(ENABLE_ST.equals(key))
                    setRebalancing(true);
                if(DISABLE_ST.equals(key))
                    setRebalancing(false);
                if(CACHE_SIZE.equals(key))
                    map.put(CACHE_SIZE, String.valueOf(cache.size()));
            }
            return map;
        }

        public String[] supportedKeys() {
            return new String[]{GET_ST, ENABLE_ST, DISABLE_ST, CACHE_SIZE};
        }

        protected boolean isRebalancingEnabled() {
            LocalTopologyManagerImpl topo_mgr=(LocalTopologyManagerImpl)mgr.getGlobalComponentRegistry().getComponent(LocalTopologyManager.class);
            try {
                return topo_mgr.isRebalancingEnabled();
            }
            catch(Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        protected void setRebalancing(boolean flag) {
            LocalTopologyManagerImpl topo_mgr=(LocalTopologyManagerImpl)mgr.getGlobalComponentRegistry().getComponent(LocalTopologyManager.class);
            try {
                topo_mgr.setRebalancingEnabled(flag);
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Listener
    public static class MyListener {
        @ViewChanged
        public static void viewChanged(ViewChangedEvent evt) {
            Transport transport=evt.getCacheManager().getTransport();
            if(transport instanceof JGroupsTransport) {
                Channel ch=((JGroupsTransport)transport).getChannel();
                View view=ch.getView();
                System.out.println("** view: " + view);
            }
            else
                System.out.println("** view: " + evt);
        }
    }


    public static void main(String[] args) throws Exception {
        String config_file="infinispan.xml";
        String cache_name="clusteredCache";
        String name=null;
        long   uuid=0;

        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-cfg")) {
                config_file=args[++i];
                continue;
            }
            if(args[i].equals("-cache")) {
                cache_name=args[++i];
                continue;
            }
            if(args[i].equals("-name")) {
                name=args[++i];
                continue;
            }
            if(args[i].equals("-uuid")) {
                uuid=Long.parseLong(args[++i]);
                continue;
            }
            System.out.println("Test [-cfg <config-file>] [-cache <cache-name>] [-name <name>] [-uuid <UUID>]");
            return;
        }

        Test test=new Test();
        test.start(config_file, cache_name, name, uuid);
    }

}