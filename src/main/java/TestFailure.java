import org.apache.commons.collections4.iterators.ArrayListIterator;

import java.util.Iterator;

/**
 * Created by Teo on 8/14/2016.
 */
public class TestFailure implements Iterable<TestFailure> {
    String buildNumber;
    String nodeUrl;
    String failureToCompare;
    String failureToDisplay;
    String testUrl;

    TestFailure(String buildNumber, String nodeUrl, String testUrl, String failureToCompare, String failureToDisplay) {
        this.buildNumber = buildNumber;
        this.nodeUrl = nodeUrl;
        this.testUrl = testUrl;
        this.failureToCompare = failureToCompare;
        this.failureToDisplay = failureToDisplay;
    }

    @Override
    public Iterator<TestFailure> iterator() {
        TestFailure[]  array = new TestFailure[1];
        array[0] = this;
        return new ArrayListIterator<>(array);
    }
}