import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import java.util.List;

/**
 * Created by Teo on 8/14/2016.
 */
public class FailuresMatchResult {
    List<String> matchedFailedTests;
    ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();

    FailuresMatchResult(List<String> matchedFailedTests, ArrayListValuedHashMap<String, TestFailure> testsFailures) {
        this.matchedFailedTests = matchedFailedTests;
        this.testsFailures = testsFailures;
    }
}