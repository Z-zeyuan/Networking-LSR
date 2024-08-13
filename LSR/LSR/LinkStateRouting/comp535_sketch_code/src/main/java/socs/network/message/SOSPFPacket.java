package socs.network.message;

import socs.network.node.RouterDescription;

import java.io.*;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class SOSPFPacket implements Serializable {

    //for inter-process communication
    public String srcProcessIP;
    public short srcProcessPort;

    //simulated IP address
    public String srcIP;
    public String dstIP;

    //common header
    //0 - HELLO, 1 - LinkState Update, 2 - Reject Attach
    // 3 - Start_1, 4 - Start_2, 5 - Start_3
    // 6 - Disconnect
    public short sospfType;
    public String routerID;     // TODO: What's routerID?

    //used by HELLO message to identify the sender of the message
    //e.g. when router A sends HELLO to its neighbor, it has to fill this field with its own
    //simulated IP address
    public String neighborID; //neighbor's simulated IP address

    //used by LSAUPDATE
    public Vector<LSA> lsaArray = null;

    public SOSPFPacket(String srcProcessIP, short srcProcessPort, String srcIP, String dstIP, short sospfType) {
        this.srcProcessIP = srcProcessIP;
        this.srcProcessPort = srcProcessPort;
        this.srcIP = srcIP;
        this.dstIP = dstIP;
        this.sospfType = sospfType;
    }

    public SOSPFPacket(RouterDescription rd, String dstIP, short sospfType){
        this.srcProcessIP = rd.getProcessIPAddress();
        this.srcProcessPort = rd.getProcessPortNumber();
        this.srcIP = rd.getSimulatedIPAddress();
        this.dstIP = dstIP;
        this.sospfType = sospfType;
    }


    public String getMessage() {
        String msg;
        switch (sospfType){
            case 1:
                msg = "Hello";
                break;
            case 2:
                msg = "LSAUPDATE";
                break;
            default:
                msg = "Unknown";
                break;
        }
        return msg;
    }

}
