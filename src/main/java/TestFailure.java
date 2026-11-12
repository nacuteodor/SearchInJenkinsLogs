import org.apache.commons.collections4.iterators.ArrayListIterator;

import java.util.Iterator;

/**
 * Describes one failure occurrence, its source report, and comparison/display text.
 * Implements a singleton iterable for the multimap insertion APIs used by the tool.
 *
 * Created by Teo on 8/14/2016.
 */
public class TestFailure implements Iterable<TestFailure> {
    String buildNumber;
    String nodeUrl;
    String failureToCompare;
    String failureToDisplay;
    String testUrl;
    String testName;
    String shortTestName;

    /**
     * Creates a failure observation with its build and report metadata.
     *
     * @param buildNumber Jenkins build number or GitHub workflow run number
     * @param nodeUrl source Jenkins node URL or GitHub artifact URL
     * @param testUrl link shown in the HTML report
     * @param testName qualified test name shown in the report
     * @param shortTestName original test name used for issue matching
     * @param failureToCompare normalized text used for similarity comparisons
     * @param failureToDisplay abbreviated text shown in the report
     */
    TestFailure(String buildNumber, String nodeUrl, String testUrl, String testName, String shortTestName, String failureToCompare, String failureToDisplay) {
        this.buildNumber = buildNumber;
        this.nodeUrl = nodeUrl;
        this.testUrl = testUrl;
        this.testName = testName;
        this.shortTestName = shortTestName;
        this.failureToCompare = failureToCompare;
        this.failureToDisplay = failureToDisplay;
    }

    /** Returns an iterator containing only this failure observation. */
    @Override
    public Iterator<TestFailure> iterator() {
        TestFailure[]  array = new TestFailure[1];
        array[0] = this;
        return new ArrayListIterator<>(array);
    }
}