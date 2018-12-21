import java.io.FileNotFoundException;
import java.io.FileReader;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws RemoteException, AlreadyBoundException, FileNotFoundException {
        FileReader fileReader = new FileReader(args[0]);
        Scanner scanner = new Scanner(fileReader);

        List<Integer> allIds = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        int amountOfNodes = scanner.nextInt();

        Map<Integer, List<Edge>> edgeMap = new HashMap<>();

        for (int i = 1; i < amountOfNodes + 1; i++) {
            List<Edge> edgeList = new ArrayList<>();
            edgeMap.put(i, edgeList);
        }

        while (scanner.hasNextLine()) {
            int source = scanner.nextInt();
            int target = scanner.nextInt();
            int weight = scanner.nextInt();
            if (source < target) {
                edgeMap.get(source).add(new Edge(source, target, new Weight(weight, source, target)));
            } else {
                edgeMap.get(source).add(new Edge(source, target, new Weight(weight, target, source)));
            }
        }

        for (Integer id : edgeMap.keySet()) {
            List<Edge> edges = edgeMap.get(id);
            Node node = new Node(id, edges);
            nodes.add(node);
            allIds.add(id);

            INode stub = (INode) UnicastRemoteObject.exportObject(node, 0);
            registry.bind("p" + id, stub);
            new Thread(node).start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Node node : nodes) {
                node.printStatus();
            }
            for (Integer id : allIds) {
                try {
                    registry.unbind("p" + id);
                } catch (RemoteException e) {
                    e.printStackTrace();
                } catch (NotBoundException e) {
                    e.printStackTrace();
                }
            }
        }));

    }
}
