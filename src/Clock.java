public interface Clock {
    void increment();

    void update(Timestamp other);

    Timestamp stamp();
}
