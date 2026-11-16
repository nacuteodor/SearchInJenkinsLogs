import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import java.util.List;

/**
 * Holds matched test identifiers, extracted failures, and optional stability observations.
 * Collections are shared with callers rather than defensively copied.
 *
 * Created by Teo on 8/14/2016.
 */
public class FailuresMatchResult {
    List<String> matchedFailedTests;
    ArrayListValuedHashMap<String, TestFailure> testsFailures;
    ArrayListValuedHashMap<String, TestStatus> testsStatus;

    /**
     * Collects the outputs of a JUnit report or failure-node scan.
     *
     * @param matchedFailedTests identifiers matching the configured search expression
     * @param testsFailures failure observations indexed by test identity
     * @param testsStatus stability observations, or {@code null} when not collected
     */
    FailuresMatchResult(List<String> matchedFailedTests, ArrayListValuedHashMap<String, TestFailure> testsFailures, ArrayListValuedHashMap<String, TestStatus> testsStatus) {
        this.matchedFailedTests = matchedFailedTests;
        this.testsFailures = testsFailures;
        this.testsStatus = testsStatus;
    }
}