
import java.io.Serializable;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Node implements Runnable, Serializable, INode {
    private final List<Edge> edges;
    private final Integer id;
    private NodeState state = NodeState.SLEEPING;
    private Integer fragmentLevel = 0; // the level of the fragment this node belongs to
    private Weight fragmentName = null; // the name of the fragment this node belongs to
    private Edge inBranch = null;  // the edge that leads to the fragment core
    private Integer findCount; // the number of reports still expected
    private Edge bestEdge; // the edge leading towards the best candidate for the moe
    private Weight bestWeight; // the weight of the best candidate for the moe
    private Edge testEdge; // the edge this node is currently testing for the moe
    private Map<Edge, EdgeState> edgeStates;
    private final Integer NETWORK_DELAY = 150;
    private final ArrayList<ReportMessage> reportQueue;
    private final ArrayList<ConnectMessage> connectQueue;
    private final ArrayList<TestMessage> testQueue;

    public Node(Integer id, List<Edge> edges) {
        this.id = id;
        this.reportQueue = new ArrayList<>();
        this.connectQueue = new ArrayList<>();
        this.testQueue = new ArrayList<>();
        // Create list of edges connected to this node
        Collections.sort(edges);
        this.edges = Collections.unmodifiableList(edges);
        System.out.println(this.id + ": Created with edge list " + this.edges);

        // Keep a list of states corresponding to the above edges
        HashMap<Edge, EdgeState> edgeStates = new HashMap<>();
        this.edges.forEach(e -> edgeStates.put(e, EdgeState.UNKNOWN));
        this.edgeStates = Collections.unmodifiableMap(edgeStates);
    }

    @Override
    public void receiveInitiate(Integer id, Integer L, Weight F, NodeState S) throws RemoteException {
        // Fragment IV
        Edge j = identifyEdge(id);
        this.fragmentLevel = L;
        this.fragmentName = F;
        this.state = S;
        checkAllQueues();
        System.out.println(this.id + ": State: " + this.state + ": " + this.findCount);
        this.inBranch = j;
        this.bestEdge = null;
        this.bestWeight = Weight.INFINITE;
        for (Edge i : this.edges) {
            if (!i.equals(j) && this.edgeStates.get(i) == EdgeState.IN_MST) {
                sendInitiate(i, L, F, S);
                if (this.state == NodeState.FIND) {
                    findCount = findCount + 1;
                }
            }
        }
        if (this.state == NodeState.FIND) {
            // Maybe switch these around?

            test();
        }
    }

    private void checkAllQueues() throws RemoteException {
        removeFromConnectQueue();
        removeFromTestQueue();
        removeFromReportQueue();
    }

    private synchronized void removeFromReportQueue() {
        if (!reportQueue.isEmpty() && this.state != NodeState.FIND) {
            System.out.println(this.id + ": Popping a message from the report queue");
            ReportMessage m = reportQueue.get(0);
            reportQueue.remove(m);

            this.receiveReport(m.from, m.weight);
            removeFromReportQueue();
        }
    }

    private synchronized void removeFromTestQueue() throws RemoteException {
            if (!testQueue.isEmpty()) {
                TestMessage tm = testQueue.get(0);
                if (tm.level <= this.fragmentLevel) {
                    System.out.println(this.id + ": Popping a message from the test queue");
                    testQueue.remove(tm);
                    this.receiveTest(tm.from, tm.level, tm.weight);
                    removeFromTestQueue();
                }
            }
    }

    private synchronized void removeFromConnectQueue() {
        if (!connectQueue.isEmpty()) {
            ConnectMessage cm = null;
            for (ConnectMessage m : connectQueue) {
                if (this.edgeStates.get(m) != EdgeState.UNKNOWN) {
                    cm = m;
                    break;
                }
            }
            if (cm != null) {
                System.out.println("Popping a message from the connect queue");
                connectQueue.remove(cm);
                this.receiveConnect(cm.from, cm.value);
                removeFromConnectQueue();
            }
        }
    }

    @Override
    public void receiveTest(Integer from, Integer l, Weight FN) throws RemoteException {
        // Fragment VI
        if (this.state == NodeState.SLEEPING) {
            wakeup();
        }
        if (l > this.fragmentLevel) {
            this.testQueue.add(new TestMessage(from, l, FN));
        } else {
            Edge j = identifyEdge(from);
            if (!FN.equals(this.fragmentName)) {
                sendAccept(j);
            } else {
                if (this.edgeStates.get(j) == EdgeState.UNKNOWN) {
                    this.updateEdgeState(j, EdgeState.NOT_IN_MST);
                } else if (!j.equals(this.testEdge)) {
                    sendReject(j);
                } else {
                    test();
                }
            }
            checkAllQueues();
        }
    }

    private void sendReject(Edge j) {
        Integer receiveID = getReceiver(j);
        INode receiver = findNode(receiveID);
        if (receiver == null) {
            return;
        }
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveReject(this.id);
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (InterruptedException ignored) {
            }
        };
        new Thread(send).start();
    }

    private void sendAccept(Edge j) {
        Integer receiverId = getReceiver(j);
        INode receiver = findNode(receiverId);
        if (receiver == null) {
            return;
        }
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveAccept(this.id);
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (InterruptedException ignored) {
            }
        };
        new Thread(send).start();
    }

    @Override
    public void receiveAccept(Integer from) {
        // Fragment VIII
        Edge j = identifyEdge(from);
        this.testEdge = null;
        if (j.weight.compareTo(bestWeight) < 0) {
            this.bestEdge = j;
            this.bestWeight = j.weight;
        }
        report();

    }

    private void report() {
        // Fragment IX
        if (this.findCount == 0 && this.testEdge == null) {
            this.state = NodeState.FOUND;
            System.out.println(this.id + ": State: " + this.state + ": " + this.findCount);
            Integer receiver = getReceiver(inBranch);
            sendReport(receiver, this.bestWeight);
            try {
                checkAllQueues();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendReport(Integer receiverId, Weight bestWeight) {
        INode receiver = findNode(receiverId);
        if (receiver == null) {
            return;
        }
        Weight W = new Weight(bestWeight);
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(NETWORK_DELAY);
                receiver.receiveReport(this.id, W);
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (InterruptedException ignored) {
            }
        };
        new Thread(send).start();
    }

    @Override
    public void receiveReject(Integer from) {
        // Fragment VII
        Edge j = identifyEdge(from);
        if (this.edgeStates.get(j) == EdgeState.UNKNOWN) {
            this.updateEdgeState(j, EdgeState.NOT_IN_MST);
        }
        try {
            checkAllQueues();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        test();
    }

    @Override
    public void receiveReport(Integer from, Weight w) {
        // Fragment X
        Edge j = identifyEdge(from);
        if (!j.equals(inBranch)) {
            this.findCount -= 1;
            if (w.compareTo(bestWeight) < 0) {
                bestWeight = w;
                bestEdge = j;
            }
            report();
        } else {
            if (this.state == NodeState.FIND) {
                this.reportQueue.add(new ReportMessage(from, w));
            } else {
                if (w.compareTo(bestWeight) > 0) {
                    changeRoot();
                } else {
                    if (w.equals(bestWeight) && bestWeight.equals(Weight.INFINITE)) {
                        HALT();
                    } else {
                        System.out.println("Report: nothing happened");
                    }
                }
            }
        }
        try {
            checkAllQueues();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void changeRoot() {
        // Fragment XI
        if (this.edgeStates.get(bestEdge) == EdgeState.IN_MST) {
            sendChangeRoot(bestEdge);
        } else {
            this.sendConnect(bestEdge, this.fragmentLevel);
            updateEdgeState(bestEdge, EdgeState.IN_MST);
        }
    }

    private void sendChangeRoot(Edge j) {
        Integer receiverID = getReceiver(j);
        INode receiver = findNode(receiverID);
        if (receiver == null) {
            return;
        }
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveChangeRoot(this.id);
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (InterruptedException ignored) {
            }
        };
        new Thread(send).start();
    }

    private void HALT() {
        // TODO
        // misschien iets van een flag zodat er niet meer iets gedaan wordt met berichten?
//        reportQueue.clear();

        System.out.println("HALT!");
        this.state = NodeState.SLEEPING;
        System.out.println(this.id + ": State: " + this.state + ": " + this.findCount);
//        this.printStatus();
//        Thread.currentThread().interrupt();
        System.exit(100);
    }

    @Override
    public void receiveConnect(Integer from, Integer value) {
        // Fragment III
        if (this.state == NodeState.SLEEPING) {
            wakeup();
        }
        Edge j = identifyEdge(from);
        if (value < this.fragmentLevel) {
            updateEdgeState(j, EdgeState.IN_MST);
            sendInitiate(j, fragmentLevel, fragmentName, state);
            if (this.state == NodeState.FIND) {
                this.findCount += 1;
            }
        } else {
            if (this.edgeStates.get(j) == EdgeState.UNKNOWN) {
                this.connectQueue.add(new ConnectMessage(from, value, j));
            } else {
                sendInitiate(j, fragmentLevel + 1, j.weight, NodeState.FIND);
            }
        }
        try {
            checkAllQueues();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void sendInitiate(Edge j, Integer fragmentLevel, Weight fragmentName, NodeState state) {
        Integer receiverID = getReceiver(j);
        INode receiver = findNode(receiverID);
        if (receiver == null) {
            return;
        }
        Weight FN = new Weight(fragmentName);
        NodeState NS = state;

        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveInitiate(this.id, fragmentLevel, FN, NS);
            } catch (InterruptedException ignored) {
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        };
        new Thread(send).start();
    }

    @Override
    public void receiveChangeRoot(Integer from) throws RemoteException {
        changeRoot();
        try {
            checkAllQueues();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        // Code Fragment I : spontaneously starting
        Random random = new Random();
        try {
            Thread.sleep(random.nextInt(1000));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (this.state == NodeState.SLEEPING) {
            System.out.println(this.id + ": Starting up");
            wakeup();
        }
    }

    private void wakeup() {
        // Code Fragment II : Waking up
        Edge j = this.edges.get(0); // Edge list should be sorted on increasing weight
        updateEdgeState(j, EdgeState.IN_MST);
        this.findCount = 0;
        sendConnect(j, 0);
    }

    private void sendConnect(Edge e, Integer value) {
        Integer receiverId = getReceiver(e);
        INode receiver = findNode(receiverId);
        if (receiver == null) {
            return; // is just nothing doing anything reasonable? what is a reasonable fallback?
        }
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveConnect(this.id, value);
            } catch (InterruptedException ignored) {
            } catch (RemoteException e1) {
                e1.printStackTrace();
            }
        };
        new Thread(send).start();
    }

    private void test() {
        // Fragment V
        Weight minimumWeight = new Weight(0, 0, 0);
        Edge minimumEdge = null;
        for (Edge e : this.edges) {
            if (this.edgeStates.get(e) == EdgeState.UNKNOWN && e.weight.compareTo(minimumWeight) < 0) {
                minimumWeight = e.weight;
                minimumEdge = e;
            }
        }
        if (minimumEdge != null) {
            sendTest(minimumEdge, fragmentLevel, fragmentName);
        } else {
            this.testEdge = null;
            report();
        }
    }

    private void sendTest(Edge e, Integer fragmentLevel, Weight fragmentName) {
        Integer receiverId = getReceiver(e);
        INode receiver = findNode(receiverId);
        if (receiver == null) {
            return;
        }
        Weight FN = new Weight(fragmentName);
        Runnable send = () -> {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(NETWORK_DELAY));
                receiver.receiveTest(this.id, fragmentLevel, FN);
            } catch (InterruptedException ignored) {
            } catch (RemoteException e1) {
                e1.printStackTrace();
            }
        };
        new Thread(send).start();
    }

    private void updateEdgeState(Edge edge, EdgeState state) {
        Map<Edge, EdgeState> tempMap = new HashMap<>(this.edgeStates);
        tempMap.put(edge, state);
        this.edgeStates = Collections.unmodifiableMap(tempMap);
    }

    private Integer getReceiver(Edge e) {
        if (e.source.equals(this.id)) {
            return e.target;
        } else if (e.target.equals(this.id)) {
            return e.source;
        } else {
            // this should never happen
            throw new RuntimeException("Current node " + this.id + " is not connected to edge");
        }
    }

    private INode findNode(Integer nodeId) {
        INode node = null;
        try {
            node = (INode) Naming.lookup("//localhost:1099/p" + nodeId);
        } catch (NotBoundException e) {
            try {
                node = (INode) Naming.lookup("//ip:1099/p" + nodeId);
            } catch (NotBoundException | MalformedURLException | RemoteException ignored) {
            }
        } catch (MalformedURLException | RemoteException ignored) {
        }
        return node;
    }

    private Edge identifyEdge(Integer from) {
//        System.out.println(this.id + ": Trying to find an edge to or from " + from);
        for (Edge e : this.edges) {
            if (e.source.equals(from) || e.target.equals(from)) {
                return e;
            }
        }
        throw new RuntimeException("Node " + this.id + " is not familiar with an edge to " + from);
    }

    public void printStatus() {
//        System.out.println("[Node: " + this.id + ", Level: " + this.fragmentLevel + ", Core: " + this.fragmentName + "]");
//        System.out.println("All edges:");
//        for (Edge edge : this.edges) {
//            if (this.id.equals(edge.source)) {
//            System.out.println(edge.source + " - [" + edge.weight + "] - " + edge.target);
//            }
//        }
//        System.out.println("Edges in MST:");
        for (Edge edge : this.edgeStates.keySet()) {
            if (this.edgeStates.get(edge).equals(EdgeState.IN_MST)) {
                if (this.id.equals(edge.source)) {
                    System.out.println(edge.source + " " + edge.target);
                }
            }
        }
    }

}
