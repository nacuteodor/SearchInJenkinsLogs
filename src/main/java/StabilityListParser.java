import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.io.FileUtils;

/**
 * Parse the stability list from a file.
 * The stability list file format:
 * <pre>{@code
 * STABLE=TestSuite1&testName1:passRate:runsCount;TestSuite2&testName2:passRate:runsCount;
 * UNSTABLE=TestSuite3&testName3:passRate:runsCount;TestSuite4&testName4:passRate:runsCount;
 * }</pre>
 *
 * @author teodor.nacu, 11/16/2016
 */
public class StabilityListParser {
    private Set<String> stableTests = new HashSet<>();
    private Set<String> unstableTests = new HashSet<>();

    /**
     * Reads the stable and unstable test sets from the file's first two lines.
     *
     * @param file stability list produced by the tool
     * @throws IOException if the file cannot be read
     */
    public StabilityListParser(File file) throws IOException {
        // parse the file content
        String content = FileUtils.readFileToString(file);
        String[] tokens = content.split("\\n");
        stableTests = extractTests(tokens[0]);
        unstableTests = extractTests(tokens[1]);
    }

    /**
     * Extracts test identifiers from one stability-list line, ignoring rate/count metadata.
     *
     * @param stringLine labeled, semicolon-separated stability entries
     * @return test names with the suite/test separator converted from ampersand to dot
     */
    private Set<String> extractTests(String stringLine) {
        Set<String> tests = new HashSet<>();
        String[] listTokens = stringLine.split("=");
        if (listTokens.length < 2) {
            return tests;
        }
        String testsString = listTokens[1];
        String[] itemsList = testsString.split(";");
        for (String item: itemsList) {
            if (!item.isEmpty()) {
                tests.add(item.split(":")[0].replace("&", "."));
            }
        }
        return tests;
    }

    /** Returns the mutable set of test identifiers classified as stable. */
    public Set<String> getStableTests() {
        return stableTests;
    }

    /** Returns the mutable set of test identifiers classified as unstable. */
    public Set<String> getUnstableTests() {
        return unstableTests;
    }
}
