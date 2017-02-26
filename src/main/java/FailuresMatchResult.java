import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import java.util.List;

/**
 * Created by Teo on 8/14/2016.
 */
public class FailuresMatchResult {
    List<String> matchedFailedTests;
    ArrayListValuedHashMap<String, TestFailure> testsFailures;
    ArrayListValuedHashMap<String, TestStatus> testsStatus;

    FailuresMatchResult(List<String> matchedFailedTests, ArrayListValuedHashMap<String, TestFailure> testsFailures, ArrayListValuedHashMap<String, TestStatus> testsStatus) {
        this.matchedFailedTests = matchedFailedTests;
        this.testsFailures = testsFailures;
        this.testsStatus = testsStatus;
    }
}