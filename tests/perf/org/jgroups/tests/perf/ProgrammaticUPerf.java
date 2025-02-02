package org.jgroups.tests.perf;

import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.*;
import org.jgroups.protocols.*;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.AddressGenerator;
import org.jgroups.stack.NonReflectiveProbeHandler;
import org.jgroups.stack.Protocol;
import org.jgroups.util.*;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.jgroups.tests.perf.PerfUtil.*;


/**
 * Tests the UNICAST by invoking unicast RPCs between a sender and a receiver. Mimicks the DIST mode in Infinispan
 *
 * @author Bela Ban
 */
public class ProgrammaticUPerf implements Receiver, MethodInvoker {
    private JChannel               channel;
    private Address                local_addr;
    private RpcDispatcher          disp;
    static final String            groupname="uperf";
    protected final List<Address>  members=new ArrayList<>();
    protected volatile View        view;
    protected volatile boolean     looping=true;
    protected Thread               event_loop_thread;
    protected final LongAdder      num_reads=new LongAdder();
    protected final LongAdder      num_writes=new LongAdder();



    // ============ configurable properties ==================
    @Property protected boolean sync=true, oob=true;
    @Property protected int     num_threads=100;
    @Property protected int     time=60; // in seconds
    @Property protected int     msg_size=1000;
    @Property protected int     anycast_count=2;
    @Property protected double  read_percentage=0.8; // 80% reads, 20% writes
    @Property protected boolean allow_local_gets=true;
    @Property protected boolean print_invokers;
    @Property protected boolean print_details;
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    private static final short START                 =  0;
    private static final short GET                   =  1;
    private static final short PUT                   =  2;
    private static final short GET_CONFIG            =  3;
    private static final short SET_SYNC              =  4;
    private static final short SET_OOB               =  5;
    private static final short SET_NUM_THREADS       =  6;
    private static final short SET_TIME              =  7;
    private static final short SET_MSG_SIZE          =  8;
    private static final short SET_ANYCAST_COUNT     =  9;
    private static final short SET_READ_PERCENTAGE   = 10;
    private static final short ALLOW_LOCAL_GETS      = 11;
    private static final short PRINT_INVOKERS        = 12;
    private static final short PRINT_DETAILS         = 13;
    private static final short QUIT_ALL              = 14;


    private final AtomicInteger COUNTER=new AtomicInteger(1);
    private byte[]              BUFFER=new byte[msg_size];
    protected static final String format=
      "[1] Start test [2] View [4] Threads (%d) [6] Time (%,ds) [7] Msg size (%s)" +
        "\n[s] Sync (%b) [o] OOB (%b)" +
        "\n[a] Anycast count (%d) [r] Read percentage (%.2f) " +
        "\n[l] local gets (%b) [d] print details (%b)  [i] print invokers (%b)" +
        "\n[v] Version [x] Exit [X] Exit all\n";


    static {
        PerfUtil.init();
    }

    public boolean getSync()                   {return sync;}
    public void    setSync(boolean s)          {this.sync=s;}
    public boolean getOOB()                    {return oob;}
    public void    setOOB(boolean oob)         {this.oob=oob;}
    public int     getNumThreads()             {return num_threads;}
    public void    setNumThreads(int t)        {this.num_threads=t;}
    public int     getTime()                   {return time;}
    public void    setTime(int t)              {this.time=t;}
    public int     getMsgSize()                {return msg_size;}
    public void    setMsgSize(int t)           {this.msg_size=t;}
    public int     getAnycastCount()           {return anycast_count;}
    public void    setAnycastCount(int t)      {this.anycast_count=t;}
    public double  getReadPercentage()         {return read_percentage;}
    public void    setReadPercentage(double r) {this.read_percentage=r;}
    public boolean allowLocalGets()            {return allow_local_gets;}
    public void    allowLocalGets(boolean a)   {this.allow_local_gets=a;}
    public boolean printInvokers()             {return print_invokers;}
    public void    printInvokers(boolean p)    {this.print_invokers=p;}
    public boolean printDetails()              {return print_details;}
    public void    printDetails(boolean p)     {this.print_details=p;}




    public void init(String name, AddressGenerator generator, String bind_addr, int bind_port,
                     boolean udp, String mcast_addr, int mcast_port,
                     String initial_hosts) throws Throwable {
        InetAddress bind_address=bind_addr != null? Util.getAddress(bind_addr, Util.getIpStackType()) : Util.getLoopback();

        Protocol[] prot_stack={
          null,  // transport
          null,  // discovery protocol
          new MERGE3(),
          new FD_SOCK(),
          new FD_ALL3(),
          new VERIFY_SUSPECT(),
          new NAKACK2(),
          new UNICAST3(),
          new STABLE(),
          new GMS().setJoinTimeout(1000),
          new UFC(),
          new MFC(),
          new FRAG4()};

        if(udp) {
            UDP u=new UDP().setMulticastAddress(InetAddress.getByName(mcast_addr)).setMulticastPort(mcast_port);
            u.getDiagnosticsHandler().setMcastAddress(InetAddress.getByName("224.0.75.75")).enableUdp(true);
            prot_stack[0]=u;
            prot_stack[1]=new PING();
        }
        else {
            if(initial_hosts == null) {
                InetAddress host=bind_addr == null? InetAddress.getLocalHost() : Util.getAddress(bind_addr, Util.getIpStackType());
                initial_hosts=String.format("%s[%d]", host.getHostAddress(), bind_port);
            }
            TCP tcp=new TCP();
            tcp.getDiagnosticsHandler().enableUdp(false).enableTcp(true);
            prot_stack[0]=tcp;
            prot_stack[1]=new TCPPING().setInitialHosts2(Util.parseCommaDelimitedHosts(initial_hosts, 2));
        }

        ((TP)prot_stack[0]).setBindAddress(bind_address).setBindPort(bind_port);

        channel=new JChannel(prot_stack).addAddressGenerator(generator).setName(name);
        TP transport=channel.getProtocolStack().getTransport();
        // todo: remove default ProbeHandler for "jmx" and "op"
        NonReflectiveProbeHandler h=new NonReflectiveProbeHandler(channel);
        transport.registerProbeHandler(h);
        h.initialize(channel.getProtocolStack().getProtocols());
        // System.out.printf("contents:\n%s\n", h.dump());
        disp=new RpcDispatcher(channel, this).setReceiver(this).setMethodInvoker(this);
        channel.connect(groupname);
        local_addr=channel.getAddress();
        if(members.size() < 2)
            return;
        Address coord=members.get(0);
        Config config=disp.callRemoteMethod(coord, new CustomCall(GET_CONFIG), new RequestOptions(ResponseMode.GET_ALL, 5000));
        if(config != null) {
            applyConfig(config);
            System.out.println("Fetched config from " + coord + ": " + config + "\n");
        }
        else
            System.err.println("failed to fetch config from " + coord);
    }

    void stop() {
        Util.close(disp, channel);
    }

    protected void startEventThread() {
        event_loop_thread=new Thread(ProgrammaticUPerf.this::eventLoop, "EventLoop");
        event_loop_thread.setDaemon(true);
        event_loop_thread.start();
    }

    protected void stopEventThread() {
        Thread tmp=event_loop_thread;
        looping=false;
        if(tmp != null)
            tmp.interrupt();
        Util.close(channel);
    }

    public void viewAccepted(View new_view) {
        this.view=new_view;
        System.out.println("** view: " + new_view);
        members.clear();
        members.addAll(new_view.getMembers());
    }

    public Object invoke(Object target, short method_id, Object[] args) throws Exception {
        ProgrammaticUPerf uperf=(ProgrammaticUPerf)target;
        Boolean bool_val;
        switch(method_id) {
            case START:
                return uperf.startTest();
            case GET:
                Integer key=(Integer)args[0];
                return uperf.get(key);
            case PUT:
                key=(Integer)args[0];
                byte[] val=(byte[])args[1];
                uperf.put(key, val);
                return null;
            case GET_CONFIG:
                return uperf.getConfig();
            case SET_SYNC:
                uperf.setSync((Boolean)args[0]);
                return null;
            case SET_OOB:
                bool_val=(Boolean)args[0];
                uperf.setOOB(bool_val);
                return null;
            case SET_NUM_THREADS:
                uperf.setNumThreads((Integer)args[0]);
                return null;
            case SET_TIME:
                uperf.setTime((Integer)args[0]);
                return null;
            case SET_MSG_SIZE:
                uperf.setMsgSize((Integer)args[0]);
                return null;
            case SET_ANYCAST_COUNT:
                uperf.setAnycastCount((Integer)args[0]);
                return null;
            case SET_READ_PERCENTAGE:
                uperf.setReadPercentage((Double)args[0]);
                return null;
            case ALLOW_LOCAL_GETS:
                uperf.allowLocalGets((Boolean)args[0]);
                return null;
            case PRINT_INVOKERS:
                uperf.printInvokers((Boolean)args[0]);
                return null;
            case PRINT_DETAILS:
                uperf.printDetails((Boolean)args[0]);
                return null;
            case QUIT_ALL:
                uperf.quitAll();
                return null;
            default:
                throw new IllegalArgumentException("method with id=" + method_id + " not found");
        }
    }

    // =================================== callbacks ======================================

    public Results startTest() throws Exception {
        BUFFER=new byte[msg_size];

        System.out.printf("running for %d seconds\n", time);
        final CountDownLatch latch=new CountDownLatch(1);
        num_reads.reset(); num_writes.reset();

        Invoker[] invokers=new Invoker[num_threads];
        for(int i=0; i < invokers.length; i++) {
            invokers[i]=new Invoker(members, latch);
            invokers[i].start(); // waits on latch
        }

        long start=System.currentTimeMillis();
        latch.countDown();
        long interval=(long)((time * 1000.0) / 10.0);
        for(int i=1; i <= 10; i++) {
            Util.sleep(interval);
            System.out.printf("%d: %s\n", i, printAverage(start));
        }

        for(Invoker invoker: invokers)
            invoker.cancel();
        for(Invoker invoker: invokers)
            invoker.join();
        long total_time=System.currentTimeMillis() - start;

        System.out.println();
        AverageMinMax avg_gets=null, avg_puts=null;
        for(Invoker invoker: invokers) {
            if(print_invokers)
                System.out.printf("invoker %s: gets %s puts %s\n", invoker.getId(),
                                  print(invoker.avgGets(), print_details), print(invoker.avgPuts(), print_details));
            if(avg_gets == null)
                avg_gets=invoker.avgGets();
            else
                avg_gets.merge(invoker.avgGets());
            if(avg_puts == null)
                avg_puts=invoker.avgPuts();
            else
                avg_puts.merge(invoker.avgPuts());
        }
        if(print_invokers)
            System.out.printf("\navg over all invokers: gets %s puts %s\n",
                              print(avg_gets, print_details), print(avg_puts, print_details));

        System.out.printf("\ndone (in %s ms)\n", total_time);
        return new Results((int)num_reads.sum(), (int)num_writes.sum(), total_time, avg_gets, avg_puts);
    }

    public void quitAll() {
        System.out.println("-- received quitAll(): shutting down");
        stopEventThread();
    }

    protected String printAverage(long start_time) {
        long tmp_time=System.currentTimeMillis() - start_time;
        long reads=num_reads.sum(), writes=num_writes.sum();
        double reqs_sec=(reads+writes) / (tmp_time / 1000.0);
        return String.format("%,.0f reqs/sec (%,d reads %,d writes)", reqs_sec, reads, writes);
    }


    public byte[] get(@SuppressWarnings("UnusedParameters")int key) {
        return BUFFER;
    }


    @SuppressWarnings("UnusedParameters")
    public void put(int key, byte[] val) {
    }

    public Config getConfig() {
        Config c=new Config();
        c.add("sync", sync).add("oob", oob).add("num_threads", num_threads).add("time", time).add("msg_size", msg_size)
          .add("anycast_count", anycast_count).add("read_percentage", read_percentage)
          .add("allow_local_gets", allow_local_gets).add("print_invokers", print_invokers).add("print_details", print_details);
        return c;
    }

    protected void applyConfig(Config config) {
        for(Map.Entry<String,Object> e: config.values.entrySet()) {
            String name=e.getKey();
            Object value=e.getValue();
            switch(name) {
                case "sync":
                    setSync((Boolean)value);
                    break;
                case "oob":
                    setOOB((Boolean)value);
                    break;
                case "num_threads":
                    setNumThreads((Integer)value);
                    break;
                case "time":
                    setTime((Integer)value);
                    break;
                case "msg_size":
                    setMsgSize((Integer)value);
                    break;
                case "anycast_count":
                    setAnycastCount((Integer)value);
                    break;
                case "read_percentage":
                    setReadPercentage((Double)value);
                    break;
                case "allow_local_gets":
                    allowLocalGets((Boolean)value);
                    break;
                case "print_invokers":
                    printInvokers((Boolean)value);
                    break;
                case "print_details":
                    printDetails((Boolean)value);
                    break;
                default:
                    throw new IllegalArgumentException("field with name " + name + " not known");
            }
        }
    }

    // ================================= end of callbacks =====================================


    public void eventLoop() {

        while(looping) {
            try {
                int c=Util.keyPress(String.format(format, num_threads, time, Util.printBytes(msg_size),
                                                  sync, oob, anycast_count, read_percentage,
                                                  allow_local_gets, print_details, print_invokers));
                switch(c) {
                    case '1':
                        startBenchmark();
                        break;
                    case '2':
                        printView();
                        break;
                    case '4':
                        invoke(SET_NUM_THREADS, Util.readIntFromStdin("Number of sender threads: "));
                        break;
                    case '6':
                        invoke(SET_TIME, Util.readIntFromStdin("Time (secs): "));
                        break;
                    case '7':
                        invoke(SET_MSG_SIZE, Util.readIntFromStdin("Message size: "));
                        break;
                    case 'a':
                        int tmp=parseAnycastCount();
                        if(tmp >= 0)
                            invoke(SET_ANYCAST_COUNT, tmp);
                        break;
                    case 'o':
                        invoke(SET_OOB, !oob);
                        break;
                    case 's':
                        invoke(SET_SYNC, !sync);
                        break;
                    case 'r':
                        double percentage=parseReadPercentage();
                        if(percentage >= 0)
                            invoke(SET_READ_PERCENTAGE, percentage);
                        break;
                    case 'd':
                        invoke(PRINT_DETAILS, !print_details);
                        break;
                    case 'i':
                        invoke(PRINT_INVOKERS, !print_invokers);
                        break;
                    case 'l':
                        invoke(ALLOW_LOCAL_GETS, !allow_local_gets);
                        break;
                    case 'v':
                        System.out.printf("Version: %s\n", Version.printVersion());
                        break;
                    case 'x':
                    case -1:
                        looping=false;
                        break;
                    case 'X':
                        try {
                            RequestOptions options=new RequestOptions(ResponseMode.GET_NONE, 0)
                              .flags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
                            disp.callRemoteMethods(null, new CustomCall(QUIT_ALL), options);
                            break;
                        }
                        catch(Throwable t) {
                            System.err.println("Calling quitAll() failed: " + t);
                        }
                        break;
                    default:
                        break;
                }
            }
            catch(Throwable t) {
                t.printStackTrace();
            }
        }
        stop();
    }

    void invoke(short method_id, Object... args) throws Exception {
        MethodCall call=new CustomCall(method_id, args);
        disp.callRemoteMethods(null, call, RequestOptions.SYNC());
    }

    /** Kicks off the benchmark on all cluster nodes */
    void startBenchmark() {
        RspList<Results> responses=null;
        try {
            RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 0);
            options.flags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
            responses=disp.callRemoteMethods(null, new CustomCall(START), options);
        }
        catch(Throwable t) {
            System.err.println("starting the benchmark failed: " + t);
            return;
        }

        long total_reqs=0;
        long total_time=0;
        AverageMinMax avg_gets=null, avg_puts=null;

        System.out.println("\n======================= Results: ===========================");
        for(Map.Entry<Address,Rsp<Results>> entry: responses.entrySet()) {
            Address mbr=entry.getKey();
            Rsp<Results> rsp=entry.getValue();
            Results result=rsp.getValue();
            if(result != null) {
                total_reqs+=result.num_gets + result.num_puts;
                total_time+=result.total_time;
                if(avg_gets == null)
                    avg_gets=result.avg_gets;
                else
                    avg_gets.merge(result.avg_gets);
                if(avg_puts == null)
                    avg_puts=result.avg_puts;
                else
                    avg_puts.merge(result.avg_puts);
            }
            System.out.println(mbr + ": " + result);
        }
        double total_reqs_sec=total_reqs / ( total_time/ 1000.0);
        double throughput=total_reqs_sec * BUFFER.length;
        System.out.println("\n");
        System.out.println(Util.bold(String.format("Throughput: %,.2f reqs/sec/node (%s/sec)\n" +
                                                   "Roundtrip:  gets %s, puts %s\n",
                                                   total_reqs_sec, Util.printBytes(throughput),
                                                   print(avg_gets, print_details), print(avg_puts, print_details))));
        System.out.println("\n\n");
    }
    


    static double parseReadPercentage() throws Exception {
        double tmp=Util.readDoubleFromStdin("Read percentage: ");
        if(tmp < 0 || tmp > 1.0) {
            System.err.println("read percentage must be >= 0 or <= 1.0");
            return -1;
        }
        return tmp;
    }

    int parseAnycastCount() throws Exception {
        int tmp=Util.readIntFromStdin("Anycast count: ");
        View tmp_view=channel.getView();
        if(tmp > tmp_view.size()) {
            System.err.println("anycast count must be smaller or equal to the view size (" + tmp_view + ")\n");
            return -1;
        }
        return tmp;
    }


    protected void printView() {
        System.out.printf("\n-- local: %s, view: %s\n", local_addr, view);
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception ignored) {
        }
    }

    protected static String print(AverageMinMax avg, boolean details) {
        return details? String.format("min/avg/max = %,.2f/%,.2f/%,.2f us",
                                      avg.min() / 1000.0, avg.average() / 1000.0, avg.max() / 1000.0) :
          String.format("avg = %,.2f us", avg.average() / 1000.0);
    }



    private class Invoker extends Thread {
        private final List<Address>  dests=new ArrayList<>();
        private final CountDownLatch latch;
        private final AverageMinMax  avg_gets=new AverageMinMax(), avg_puts=new AverageMinMax(); // in ns
        private final List<Address>  targets=new ArrayList<>(anycast_count);
        private volatile boolean     running=true;


        public Invoker(Collection<Address> dests, CountDownLatch latch) {
            this.latch=latch;
            this.dests.addAll(dests);
            setName("Invoker-" + COUNTER.getAndIncrement());
        }

        
        public AverageMinMax avgGets() {return avg_gets;}
        public AverageMinMax avgPuts() {return avg_puts;}
        public void          cancel()  {running=false;}

        public void run() {
            Object[] put_args={0, BUFFER};
            Object[] get_args={0};
            MethodCall get_call=new GetCall(GET, get_args);
            MethodCall put_call=new PutCall(PUT, put_args);
            RequestOptions get_options=new RequestOptions(ResponseMode.GET_ALL, 40000, false, null);
            RequestOptions put_options=new RequestOptions(sync ? ResponseMode.GET_ALL : ResponseMode.GET_NONE, 40000, true, null);

            if(oob) {
                get_options.flags(Message.Flag.OOB);
                put_options.flags(Message.Flag.OOB);
            }

            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }

            while(running) {
                boolean get=Util.tossWeightedCoin(read_percentage);

                try {
                    if(get) { // sync GET
                        Address target=pickTarget();
                        long start=System.nanoTime();
                        if(allow_local_gets && Objects.equals(target, local_addr))
                            get(1);
                        else {
                            disp.callRemoteMethod(target, get_call, get_options);
                        }
                        long get_time=System.nanoTime()-start;
                        avg_gets.add(get_time);
                        num_reads.increment();
                    }
                    else {    // sync or async (based on value of 'sync') PUT
                        pickAnycastTargets(targets);
                        long start=System.nanoTime();
                        disp.callRemoteMethods(targets, put_call, put_options);
                        long put_time=System.nanoTime()-start;
                        targets.clear();
                        avg_puts.add(put_time);
                        num_writes.increment();
                    }
                }
                catch(Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        }

        private Address pickTarget() {
            return Util.pickRandomElement(dests);
        }

        private void pickAnycastTargets(List<Address> anycast_targets) {
            int index=dests.indexOf(local_addr);
            for(int i=index + 1; i < index + 1 + anycast_count; i++) {
                int new_index=i % dests.size();
                Address tmp=dests.get(new_index);
                if(!anycast_targets.contains(tmp))
                    anycast_targets.add(tmp);
            }
        }
    }



    public static void main(String[] args) throws Exception {
        String  name=null, bind_addr=null, mcast_addr="232.4.5.6";
        boolean run_event_loop=true;
        AddressGenerator addr_generator=null;
        int port=7800, mcast_port=45566;
        boolean udp=true;
        String initial_hosts=null;

        for(int i=0; i < args.length; i++) {
            if("-name".equals(args[i])) {
                name=args[++i];
                continue;
            }
            if("-nohup".equals(args[i])) {
                run_event_loop=false;
                continue;
            }
            if("-uuid".equals(args[i])) {
                addr_generator=new OneTimeAddressGenerator(Long.parseLong(args[++i]));
                continue;
            }
            if("-port".equals(args[i])) {
                port=Integer.parseInt(args[++i]);
                continue;
            }
            if("-bind_addr".equals(args[i])) {
                bind_addr=args[++i];
                continue;
            }
            if("-tcp".equals(args[i])) {
                udp=false;
                continue;
            }
            if("-mcast_addr".equals(args[i])) {
                mcast_addr=args[++i];
                continue;
            }
            if("-mcast_port".equals(args[i])) {
                mcast_port=Integer.parseInt(args[++i]);
                continue;
            }
            if("-initial_hosts".equals(args[i])) {
                initial_hosts=args[++i];
                continue;
            }
            help();
            return;
        }

        ProgrammaticUPerf test=null;
        try {
            test=new ProgrammaticUPerf();
            test.init(name, addr_generator, bind_addr, port, udp, mcast_addr, mcast_port, initial_hosts);
            if(run_event_loop)
                test.startEventThread();
        }
        catch(Throwable ex) {
            ex.printStackTrace();
            if(test != null)
                test.stop();
        }
    }

    static void help() {
        System.out.printf("%s [-name name] [-nohup] [-uuid <UUID>] [-port <bind port>] " +
                             "[-bind_addr bind-address] [-tcp] [-mcast_addr addr] [-mcast_port port]\n" +
                            "[-initial_hosts hosts]",
                          ProgrammaticUPerf.class.getSimpleName());
    }


}