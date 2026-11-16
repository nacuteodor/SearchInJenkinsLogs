/**
 * Records a test's pass/fail observation for one build when computing stability.
 *
 * Created by Teo on 2/5/2017.
 */
public class TestStatus {
    Integer buildNumber;
    Boolean failedStatus;

    /**
     * Creates a stability observation.
     *
     * @param buildNumber build containing the test execution
     * @param failedStatus whether the execution contains a failure or error
     */
    public TestStatus(Integer buildNumber, Boolean failedStatus) {
        this.buildNumber = buildNumber;
        this.failedStatus = failedStatus;
    }

    /** Returns a hash of the textual build/status representation. */
    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /** Returns the observation in {@code buildNumber:failedStatus} form. */
    @Override
    public String toString() {
        return String.valueOf(buildNumber).concat(":").concat(String.valueOf(failedStatus));
    }
}