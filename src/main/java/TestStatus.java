/**
 * Created by Teo on 2/5/2017.
 */
public class TestStatus {
    Integer buildNumber;
    Boolean failedStatus;

    public TestStatus(Integer buildNumber, Boolean failedStatus) {
        this.buildNumber = buildNumber;
        this.failedStatus = failedStatus;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public String toString() {
        return String.valueOf(buildNumber).concat(":").concat(String.valueOf(failedStatus));
    }
}