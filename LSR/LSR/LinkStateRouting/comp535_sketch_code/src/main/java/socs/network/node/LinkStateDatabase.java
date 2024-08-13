package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.*;
public class LinkStateDatabase {

  // linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given
   * IP address
   */
  String getShortestPath(String destinationIP) {
    LinkedList<String> N_prime = new LinkedList<>();
    N_prime.add(rd.simulatedIPAddress);
    boolean Done = false;
    //FIrst String is to Node, value string is from Node, int os dist from router to To node
    TreeMap<String, Map.Entry<String, Integer>> Paths = new TreeMap<>();
    //First String To node second From Node
    TreeMap<Integer, Map.Entry<String, String>> NodeHeap = new TreeMap<>();
    Paths.put(rd.simulatedIPAddress, new AbstractMap.SimpleEntry<>(rd.simulatedIPAddress, 0));
    for (Map.Entry<String, LSA> entry : _store.entrySet()) {
      if (this.IsNeighbor(rd.simulatedIPAddress, entry.getKey())) {
        if (!entry.getKey().equals(rd.simulatedIPAddress)) {
          NodeHeap.put(1, new AbstractMap.SimpleEntry<>(entry.getKey(), rd.simulatedIPAddress));
        }
      }
    }

    while (N_prime.size() != _store.size()) {
      //System.out.println(NodeHeap.isEmpty());
      if (NodeHeap.isEmpty()) {
        return  "Warning, " + destinationIP + " is not reachable";
      }
      String Entry_to_Add = NodeHeap.pollFirstEntry().getValue().getKey();
      //System.out.println(Entry_to_Add);
      Map.Entry<String, Map.Entry<String, Integer>> PotentialEntry = new AbstractMap.SimpleEntry<>(Entry_to_Add, this.ShortestNodeToPath(Paths, Entry_to_Add));
      this.AddNodeToPath(Paths, PotentialEntry, N_prime, NodeHeap);
      if (destinationIP.equals(PotentialEntry.getKey())) {
        break;
      }


    }
    
    String PathDiscripString = "The Path to Node " + destinationIP + "IS: \n";
    String PathString = new String(destinationIP);
    String EndN = new String(destinationIP);

    while (true) {
      if (EndN.equals(rd.simulatedIPAddress)) {
        break;
      }
      for (Entry<String, Entry<String, Integer>> e2 : Paths.entrySet()) {

        if (e2.getKey().equals(EndN)) {
          PathString = e2.getValue().getKey() + "  --->  " + PathString;
          EndN = e2.getValue().getKey();
        }

      }
    }
    return PathDiscripString.concat(PathString);
  }

  private boolean IsNeighbor(String IP1,String IP2){
    LinkedList<LinkDescription> Nei = _store.get(IP1).links;
    for (LinkDescription Neilink : Nei) {
      if (Neilink.linkID.equals(IP2)) {
        return true;
      }
    }
    return false;
  }

  private Map.Entry<String, Integer> ShortestNodeToPath(TreeMap<String, Map.Entry<String, Integer>> Paths,String ToNode)
  {
    Map.Entry<String, Integer> Shortest_dist_N = new AbstractMap.SimpleEntry<>("Not Reachable", Integer.MAX_VALUE);
      for (Entry<String, Map.Entry<String, Integer>> PathEdge : Paths.entrySet()) {
        if (this.IsNeighbor(ToNode, PathEdge.getKey()) ) {
          int ThisDist = PathEdge.getValue().getValue();
          ThisDist+=1;
          if (ThisDist < Shortest_dist_N.getValue()) {
            // System.out.println("Found" + PathEdge.getKey());
            // System.out.println("Or" + PathEdge.getValue().getKey());
            Shortest_dist_N  = new AbstractMap.SimpleEntry<>(PathEdge.getKey(), ThisDist);   
          }
        }
      }
      if (Shortest_dist_N.getValue().equals(Integer.MAX_VALUE)) {
        System.err.println(ToNode + "Warning This Node is not reachable");
      }
      return Shortest_dist_N;
  }

  private void AddNodeToPath(TreeMap<String, Map.Entry<String, Integer>> Paths,Map.Entry<String, Map.Entry<String, Integer>> Node, LinkedList<String> N_prime,TreeMap<Integer,Map.Entry<String, String>> NodeHeap ) 
  {
    //System.out.println("Adding Node" + Node.getKey());
    for (Entry<String, Map.Entry<String, Integer>> PathEdge : Paths.entrySet()) {
      if (PathEdge.getKey().equals(Node.getKey())) {
        //as dist = 1 for alll edge this shouldn't be reachable
        if (PathEdge.getValue().getValue() > Node.getValue().getValue()) {
          System.err.println("Warning Something is not right");
          Paths.put(Node.getKey(), Node.getValue());
          return;
        }
      }
    }

    Paths.put(Node.getKey(), Node.getValue());
    String ToNOdeName = Node.getKey();
    N_prime.add(ToNOdeName);
    for ( String NPNodes : N_prime ) {

      for (LinkDescription ToNodeNeis : _store.get(NPNodes).links) {
      String ToNodeNeiName = ToNodeNeis.linkID;
      //System.out.println("Node Link to it " +ToNodeNeiName);
      if ( ! N_prime.contains(ToNodeNeiName)) {
        //System.out.println("NewNei" +ToNodeNeiName);
        Map.Entry<String, Integer> PotentialEntry = this.ShortestNodeToPath(Paths, ToNodeNeiName);
        //System.out.println("ENtry SNTP" +PotentialEntry.toString());
        NodeHeap.put(PotentialEntry.getValue(),new AbstractMap.SimpleEntry<>(ToNodeNeiName,PotentialEntry.getKey()));
      }
    }
  }

    }
  // initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LinkedList<LinkDescription> links = new LinkedList<>();
  
    LSA my_lsa = new LSA(this.rd.simulatedIPAddress, links);
    //this._store.put(this.rd.simulatedIPAddress, my_lsa);
    return my_lsa;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa : _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}



