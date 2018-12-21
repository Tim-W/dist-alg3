public class VectorTimestamp implements Timestamp {
    final int[] clock;

    public VectorTimestamp(int[] clock) {
        this.clock = clock;
    }

    @Override
    public boolean leq(Timestamp other) {
        VectorTimestamp vOther = (VectorTimestamp) other;
        boolean leq = true;
        for(int i = 0; i < clock.length; i++) {
            leq = leq && (clock[i] <= vOther.clock[i]);
        }
        return leq;
    }

    public String toString() {
        String output = "";
        for (int item : clock) {
            output += item + " ";
        }
        return output;
    }

    public boolean gt(Timestamp other) {
        VectorTimestamp vOther = (VectorTimestamp) other;
        boolean gt = true;
        for(int i = 0; i < clock.length; i++) {
            gt = gt && (clock[i] > vOther.clock[i]);
        }
        return gt;
    }
}
