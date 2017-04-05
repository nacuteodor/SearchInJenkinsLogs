import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.io.FileUtils;

/**
 * Parse the stability list from a file.
 * The stability list file format:
 * STABLE=TestSuite1&testName1:${PassRate1}:${RunsCount1};TestSuite2&testName2::${PassRate2}:${RunsCount2};
 * UNSTABLE=TestSuite3&testName3:${PassRate3}:${RunsCount3};TestSuite4&testName4::${PassRate4}:${RunsCount4};
 *
 * @author teodor.nacu, 11/16/2016
 */
public class StabilityListParser {
    private Set<String> stableTests = new HashSet<>();
    private Set<String> unstableTests = new HashSet<>();

    public StabilityListParser(File file) throws IOException {
        // parse the file content
        String content = FileUtils.readFileToString(file);
        String[] tokens = content.split("\\n");
        stableTests = extractTests(tokens[0]);
        unstableTests = extractTests(tokens[1]);
    }

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

    public Set<String> getStableTests() {
        return stableTests;
    }

    public Set<String> getUnstableTests() {
        return unstableTests;
    }
}
