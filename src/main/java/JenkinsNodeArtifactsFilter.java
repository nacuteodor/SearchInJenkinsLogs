import com.jayway.jsonpath.JsonPath;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Created by Teo on 8/8/2016.
 */
class JenkinsNodeArtifactsFilter implements Callable<JenkinsNodeArtifactsFilter> {

    private final String jobUrl;
    private final String newUrlPrefix;
    private final String artifactsFilters;
    private final String searchedText;
    private final boolean searchInJUnitReports;
    private final boolean groupTestsFailures;
    final String buildNumber;
    final String nodeUrl;

    List<String>                         matchedArtifacts = new ArrayList<>();
    List<String>                         matchedFailedTests = new ArrayList<>();
    ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();

    JenkinsNodeArtifactsFilter(
            String jobUrl,
            String newUrlPrefix,
            String buildNumber,
            String nodeUrl,
            String artifactsFilters,
            boolean searchInJUnitReports,
            boolean groupTestsFailures,
            String searchedText) {

        this.jobUrl = jobUrl;
        this.buildNumber = buildNumber;
        this.newUrlPrefix = newUrlPrefix;
        this.nodeUrl = nodeUrl;
        this.artifactsFilters = artifactsFilters;
        this.searchInJUnitReports = searchInJUnitReports;
        this.groupTestsFailures = groupTestsFailures;
        this.searchedText = searchedText;
    }

    /**
     * @see java.util.concurrent.Callable#call()
     */
    @Override
    public JenkinsNodeArtifactsFilter call() {
        try {
            processNode();
        } catch( IOException | ParserConfigurationException | SAXException e ) {
            String errorLog = "Exception when when processing node: build: " + buildNumber + " node: " + nodeUrl;
            System.err.println( errorLog + e.getLocalizedMessage() );
            throw new RuntimeException( errorLog, e );
        }
        return this;
    }

    /**
     * Find the searched text @searchedText in the current build node artifacts in a new thread.
     * Saves the artifacts where it finds the @searchedText in matchedArtifacts list
     */
    private void processNode() throws IOException, ParserConfigurationException, SAXException {
        String nodeUrlResp = Main.getUrlResponse(nodeUrl.replace(jobUrl, newUrlPrefix).concat("/api/json"));
        List<String> artifactsRelativePaths = JsonPath.read(nodeUrlResp, Main.artifactsRelativePathJsonPath);
        String artifactUrlPrefix = newUrlPrefix.concat("/").concat(buildNumber).concat("/").concat(nodeUrl.replace(jobUrl, "").replace(buildNumber.concat("/"), "").concat("/artifact/"));
        for (String artifactRelativePath : artifactsRelativePaths) {
            if (!Main.artifactUrlMatchesFilters(artifactRelativePath, artifactsFilters)) {
                continue;
            }
            String artifactUrl =  artifactUrlPrefix + artifactRelativePath;
            String artifactFileContent = Main.getUrlResponse(artifactUrl);
            if (searchInJUnitReports || groupTestsFailures) {
                FailuresMatchResult failuresMatchResult = Main.matchJUnitReportFailures(artifactFileContent, buildNumber, nodeUrl, searchInJUnitReports, groupTestsFailures, searchedText);
                List<String> reportMatchedFailedTests = failuresMatchResult.matchedFailedTests;
                matchedFailedTests.addAll(reportMatchedFailedTests);
                testsFailures = failuresMatchResult.testsFailures;
                continue;
            }
            if (Main.findSearchedTextInContent(searchedText, artifactFileContent))
                matchedArtifacts.add(artifactRelativePath);
        }
    }
}