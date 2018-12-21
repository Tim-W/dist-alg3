

public class ConnectMessage {
    final Integer value;
    final Integer from;
    final Edge edge;

    public ConnectMessage(Integer from, Integer value, Edge e) {
        this.from = from;
        this.value = value;
        this.edge = e;
    }
}
