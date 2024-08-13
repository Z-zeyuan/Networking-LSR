package socs.network.node;

import java.io.*;
import java.net.*;
import java.util.AbstractMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

public class Router {

    protected LinkStateDatabase lsd;

    RouterDescription rd;

    private ServerSocket serverSocket;

    boolean down = false;

    private volatile boolean started;

    ExecutorService executor = Executors.newFixedThreadPool(5);
    Listener[] listeners;
    Thread requestHandlerThread;

    // Flags for request handler
    private volatile boolean hasRequest;
    private volatile int acceptRequest; // 0: no response   1: accept   -1: reject

    //assuming that all routers are with 4 ports
    Link[] ports = new Link[4];
    Thread[] portListener = new Thread[4];

    public Router(Configuration config) throws IOException {
        rd = new RouterDescription(InetAddress.getLocalHost().getHostAddress(),
                config.getShort("socs.network.router.port"),
                config.getString("socs.network.router.ip"));
        // TODO: Do we initialize Router Status?
        lsd = new LinkStateDatabase(rd);

        serverSocket = new ServerSocket(rd.processPortNumber);

        requestHandlerThread = new Thread(new RequestHandler());
        started = false;
        hasRequest = false;
        acceptRequest = 0;


        System.out.println("Initializing Router......");
        System.out.println("Port Number: " + rd.processPortNumber);
        System.out.println("IP Address: " + rd.processIPAddress);
        System.out.println("Simulated IP Address: " + rd.simulatedIPAddress);
        System.out.println("######################################");

        requestHandlerThread.start();
    }

    private class Listener implements Runnable{
        private Link link;
        private Socket socket;  // Port to listen
        private boolean isOnline;
        Listener(Link link) throws IOException {
            this.link = link;
            this.socket = link.socket;
            isOnline = true;
        }
        @Override
        public void run() {
            while (isOnline){
                try {
                    Object input = link.inputStream.readObject();
                    if (input instanceof SOSPFPacket){
                        SOSPFPacket packet = (SOSPFPacket) input;
                        SOSPFPacket reply;
                        switch (packet.sospfType){
                            case 0:     // HELLO
                                System.out.println("Received Hello");
                                break;
                            case 1:     // LSUPDATE
                                System.out.println("Received LSUPDATE");

                                Vector<LSA> LSAForgien = packet.lsaArray;
                                LinkedList<String> LsaNodesIHave = new LinkedList<>(Router.this.lsd._store.keySet());
                                boolean IsChanged = false;
                                for (LSA lsa : LSAForgien) {
                                    if (!LsaNodesIHave.contains(lsa.linkStateID)) {
                                        Router.this.lsd._store.put(lsa.linkStateID, lsa);
                                        IsChanged = true;
                                    }
                                    else{
                                        LSA lsaIhave = Router.this.lsd._store.get(lsa.linkStateID);
                                        //System.out.println(lsa.linkStateID + " 's LSA Seq num is " + lsa.lsaSeqNumber);
                                        if (lsaIhave.lsaSeqNumber < lsa.lsaSeqNumber) {
                                            //System.out.println(lsa.linkStateID + "stage entered " + lsa.lsaSeqNumber);
                                            Router.this.lsd._store.put(lsa.linkStateID,lsa);
                                            IsChanged = true;
                                        }
                                    }
                                }
                                if(IsChanged){
                                    //System.out.println("Is changed");
                                    for (Link links : ports){
                                        if (links == null) continue;
                                        if(!links.router2.simulatedIPAddress.equals(packet.srcIP)) {
                                            SOSPFPacket packet_forward = new SOSPFPacket(rd, links.router2.simulatedIPAddress, (short) 1);
                                            packet_forward.lsaArray = new Vector<>(lsd._store.values());
                                            sendPacket(links, packet);

                                        }

                                    }
                                }

                                break;
                            case 2:
                                System.out.println("Received Reject");
                                break;
                            case 3:
                                System.out.println("Received HELLO from " + packet.srcIP);
                                if (link.router2.status == null){
                                    link.router2.status = RouterStatus.INIT;
                                    System.out.println("Set " + packet.srcIP + " State to INIT");
                                    reply = new SOSPFPacket(rd,packet.srcIP,(short) 4);
                                    sendPacket(link,reply);
                                }
                                else if(link.router2.status == RouterStatus.TWO_WAY){
                                    updateAndNotify();
                                }
                                if (!Router.this.started) Router.this.started = true;
                                break;
                            case 4:
                                System.out.println("Received HELLO from " + packet.srcIP);
                                if (link.router2.status != RouterStatus.TWO_WAY){
                                    link.router2.status = RouterStatus.TWO_WAY;
                                    System.out.println("Set " + packet.srcIP + " State to TWO_WAY");
                                    reply = new SOSPFPacket(rd,packet.srcIP,(short) 5);
                                    sendPacket(link,reply);
                                    updateAndNotify();
                                }
                                updateAndNotify();
                                if (!Router.this.started) Router.this.started = true;
                                break;
                            case 5:
                                System.out.println("Received HELLO from " + packet.srcIP);
                                if (link.router2.status != RouterStatus.TWO_WAY){
                                    link.router2.status = RouterStatus.TWO_WAY;
                                    System.out.println("Set " + packet.srcIP + " State to TWO_WAY");
                                    updateAndNotify();
                                }
                                break;
                            case 6:
                                String sourceIP = packet.srcIP;
                                for (int i = 0; i < 4; i++){
                                    if (ports[i] != null && ports[i].router2.simulatedIPAddress.equals(sourceIP)){
                                        closeConnection(i);
                                    }
                                }
                                updateAndNotify();

                                isOnline = false;
                                break;
                            default:    // Ignore
                                System.out.println("Received Unknown");
                                break;
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {

                    isOnline = false;
                    System.out.println("Closing thread...");
                }
            }
        }
    }

    private class RequestHandler implements Runnable {
        private Socket socket;

        /**
         * process request from the remote router.
         * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request.
         * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
         */
        private void requestHandler(SOSPFPacket packet, ObjectInputStream in, ObjectOutputStream out) {
            boolean accept = false;
            // Determine to accept or reject
            if (link_num() > 4) {
                System.out.println("Max number of ports reached. Rejecting Incoming Connection...");
            } else {
                hasRequest = true;
                System.out.println("Do you accept this request? (Y/N) ");
                while (acceptRequest==0);
                accept = acceptRequest == 1;
                acceptRequest = 0;
                hasRequest = false;
            }
            // Reply
            try{
                if (!accept) {   // Reject
                    // Send Reject Message
                    SOSPFPacket response = new SOSPFPacket(Router.this.rd,packet.srcIP,(short) 2);
                    out.writeObject(response);
                    // Clean Socket connection
                    socket.close();
                } else {  // Accept
                    // Send Accept Message
                    SOSPFPacket response = new SOSPFPacket(Router.this.rd,packet.srcIP,(short) 0);
                    out.writeObject(response);
                    // Create new Link
                    Link link = new Link(Router.this.rd,new RouterDescription(packet.srcProcessIP,packet.srcProcessPort,packet.srcIP),
                                        socket, in, out);
                    addLink(link);

                }
            }catch (IOException e){
                System.out.println("requestHandler: " + e.getMessage());
            }

        }

        @Override
        public void run() {
            while (!down) { //TODO: Re-check thread termination issue
                try {
                    socket = serverSocket.accept();     // Waiting for connection request
                    System.out.println("Connection Request Detected...");
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
                    objectOutputStream.flush();
                    ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream()); // Read input
                    Object input = objectInputStream.readObject();
                    if (input instanceof SOSPFPacket) {
                        SOSPFPacket packet = (SOSPFPacket) input;
                        System.out.println("Received Message HELLO From " + packet.srcIP);
                        requestHandler(packet,objectInputStream,objectOutputStream);
                    }
                } catch (IOException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * output the shortest path to the given destination ip
     * <p/>
     * format: source ip address  -> ip address -> ... -> destination ip
     *
     * @param destinationIP the ip adderss of the destination simulated router
     */
    private void processDetect(String destinationIP) {
        // Given the topology from LSD, return Shortest path to destination IP
        if (lsd._store.keySet().contains(destinationIP)) {
            //System.out.println(lsd._store.get(rd.simulatedIPAddress));
            System.out.println( this.lsd.getShortestPath(destinationIP));
        }
        else {
            System.err.println("Warning , this node is not in network");
        }
    }

    /**
     * disconnect with the router identified by the given destination ip address
     * Notice: this command should trigger the synchronization of database
     *
     * @param portNumber the port number which the link attaches at
     */
    private void processDisconnect(short portNumber) {
        int i;
        for (i = 0; i < 4; i++){
            if(ports[i] != null && ports[i].router2.getProcessPortNumber()==portNumber){
                SOSPFPacket packet = new SOSPFPacket(rd,ports[i].router2.simulatedIPAddress,(short) 6);
                sendPacket(ports[i],packet);

                break;
            }
        }
        if (i == 4) {
            System.out.println("Can't find the matching portNumber");
            return;
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        closeConnection(i);

        updateAndNotify();
    }

    /**
     * attach the link to the remote router, which is identified by the given simulated ip;
     * to establish the connection via socket, you need to indentify the process IP and process Port;
     * <p/>
     * NOTE: this command should not trigger link database synchronization
     */
    private boolean processAttach(String processIP, short processPort,
                               String simulatedIP) {
        // Establish connection
        try {
            // Initialize Socket
            Socket socket = new Socket(processIP,processPort);
            // Create "hello" packet
            SOSPFPacket packet = new SOSPFPacket(rd.processIPAddress,rd.processPortNumber,rd.simulatedIPAddress,
                                                simulatedIP,(short) 1);
            // Initialize input-output streams
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            // Send "hello" packet to destination
            out.writeObject(packet);
            // Try to read packet from destination
            Object response = in.readObject();
            // Process packet
            if (response instanceof SOSPFPacket) {
                SOSPFPacket p = (SOSPFPacket) response;
                // Process Packet
                // If accepted, Both side create new Link. Otherwise, close socket on both side.
                if (p.sospfType == 0){
                    addLink(new Link(rd,new RouterDescription(p.srcProcessIP,p.srcProcessPort,p.srcIP),socket,in,out));
                }else{
                    socket.close();
                }
                return p.sospfType == 0;
            }
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("processAttach: " + e.getMessage());
        }
        return false;
    }


    /**
     * broadcast Hello to neighbors
     */
    private void processStart() {
        for (Link link: ports){
            if (link == null) continue;
            SOSPFPacket packet = new SOSPFPacket(rd,link.router2.simulatedIPAddress,(short)3);
            sendPacket(link,packet);
        }
        // Give other threads one sec to process (Maybe not necessary?)
        try {
            if (!Router.this.started) Router.this.started = true;
            Thread.sleep(1000);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // updateAndNotify() will be called when TWO_WAY Status are set for both routers.
        // For detail see Listener.run() case 3,4,5
    }

    /**
     * attach the link to the remote router, which is identified by the given simulated ip;
     * to establish the connection via socket, you need to indentify the process IP and process Port;
     * <p/>
     * This command does trigger the link database synchronization
     */
    private void processConnect(String processIP, short processPort,
                                String simulatedIP) {
        // Attach + LSAUPDATE
        if (!started) {
            System.out.println("Router not started.");
            return;
        }
        boolean attach = processAttach(processIP,processPort,simulatedIP);
        for (Link link: ports){
            if (link == null) continue;
            if (link.router2.simulatedIPAddress.equals(simulatedIP)) {
                SOSPFPacket packet = new SOSPFPacket(rd, link.router2.simulatedIPAddress, (short) 3);
                sendPacket(link, packet);
            }
        }
    }

    /**
     * output the neighbors of the routers
     */
    private void processNeighbors() {
        //TODO: Remove log statements when complete the project
        System.out.println("Log: Link number is " + link_num());
        System.out.println("Log: Thread number is " + thread_num());
        LinkedList<LinkDescription> neighbours = lsd._store.get(rd.simulatedIPAddress).links;
        for (LinkDescription ld : neighbours){
            System.out.print(ld.linkID + "\t");
        }
        //System.out.println();
        //System.out.println(lsd._store.keySet().toString());
    }

    /**
     * disconnect with all neighbors and quit the program
     */
    private void processQuit() {
        for (Link l : ports){
            if (l != null) {
                SOSPFPacket packet = new SOSPFPacket(rd,l.router2.simulatedIPAddress,(short) 6);
                sendPacket(l,packet);
            }
        }
        System.exit(0);
    }

    /**
     *  Close the socket connection and the listening thread of link i of ports[]
     * @param i Index of the link to be disconnected
     */
    private void closeConnection(int i) {
        try{
            ports[i].outputStream.close();
            ports[i].inputStream.close();
            ports[i].socket.close();
            portListener[i].interrupt();
            ports[i] = null;
            portListener[i] = null;
        } catch (IOException e) {
            System.out.println("processQuit: " + e.getMessage());
        }
    }

    private void updateAndNotify(){
        // TODO: Initialize LSD (if not done yet), update LSD and send LSUPDATE messages to neighbours ( neighbours in LSD, not in ports[] )


        LSA MyLSA = this.lsd._store.get(this.rd.simulatedIPAddress);
        int seqNum = MyLSA.lsaSeqNumber;
        seqNum+=10;
        LinkedList<LinkDescription> Newlinks = new LinkedList<>();
        for (Link l : this.ports) {
            if (l == null) continue;
            Newlinks.add(new LinkDescription(l.router2.simulatedIPAddress, l.router2.processPortNumber));
        }
        LSA NewLSA = new LSA(this.rd.simulatedIPAddress,Newlinks);

        NewLSA.lsaSeqNumber = seqNum;

        //System.out.println("Updated Links"+Newlinks);
        this.lsd._store.remove(this.rd.simulatedIPAddress);
        this.lsd._store.put(this.rd.simulatedIPAddress,NewLSA);
        //System.out.println(this.lsd._store.get(this.rd.simulatedIPAddress).lsaSeqNumber);
        for (Link l : this.ports) {
            if (l == null) continue;
            SOSPFPacket packet = new SOSPFPacket(rd,l.router2.simulatedIPAddress,(short) 1);
            packet.lsaArray =  new Vector<>(this.lsd._store.values());
            sendPacket(l,packet);
        }


//        try {
//            Thread.sleep(500);
//        }
//        catch (InterruptedException e) {
//            //do nothing
//        }
//        boolean NodeInNet = false;
//        for (Map.Entry<String, LSA> entry : lsd._store.entrySet()) {
//            for (LinkDescription ls : entry.getValue().links){
//                if(ls.linkID.equals())
//            }
//        }

    }




    public void terminal() {
        try {
            InputStreamReader isReader = new InputStreamReader(System.in);
            BufferedReader br = new BufferedReader(isReader);
            System.out.print(">> ");
            String command = br.readLine();
            while (true) {
                if (hasRequest){
                    if (command.equalsIgnoreCase("Y")) {
                        System.out.println("You accepted the attach request.");
                        acceptRequest = 1;
                    } else if (command.equalsIgnoreCase("N")) {
                        System.out.println("You rejected the attach request.");
                        acceptRequest = -1;
                    }else {
                        System.out.print("Invalid answer. Try again. (Y/N) ");
                    }
                }
                else if (command.startsWith("detect ")) {
                    String[] cmdLine = command.split(" ");
                    processDetect(cmdLine[1]);
                } else if (command.startsWith("disconnect ")) {
                    String[] cmdLine = command.split(" ");
                    processDisconnect(Short.parseShort(cmdLine[1]));
                } else if (command.startsWith("quit")) {
                    isReader.close();
                    br.close();
                    processQuit();
                } else if (command.startsWith("attach ")) {
                    String[] cmdLine = command.split(" ");
                    processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
                            cmdLine[3]);
                } else if (command.equals("start")) {
                    processStart();
                } else if (command.startsWith("connect ")) {
                    String[] cmdLine = command.split(" ");
                    processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
                            cmdLine[3]);
                } else if (command.equals("neighbors")) {
                    //output neighbors
                    processNeighbors();
                }
                else {
                    System.out.println("Invalid input");
                }
                System.out.print(">> ");
                command = br.readLine();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addLink(Link link) throws IOException {
        for (int i = 0; i < 4; i++){
            if (ports[i] == null){
                ports[i] = link;
                Thread t = new Thread(new Listener(link));
                portListener[i] = t;
                t.start();
                return;
            }
        }
    }

    private int link_num(){
        int num = 0;
        for (int i = 0; i < 4; i++){
            if (ports[i] != null) num++;
        }
        return num;
    }

    private int thread_num(){
        int num = 0;
        for (int i = 0; i < 4; i++){
            if (portListener[i] != null) num++;
        }
        return num;
    }

    private  synchronized void sendPacket(Link link,SOSPFPacket packet){
        try {
            link.outputStream.writeObject(packet);
            link.outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
