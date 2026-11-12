import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.xml.sax.SAXException;

/**
 * Processes one Jenkins build node as a parallel task and retains its matching results.
 * Reads artifacts from Jenkins or a local backup according to the supplied options.
 *
 * Created by Teo on 8/8/2016.
 */
class JenkinsNodeArtifactsFilter implements Callable<JenkinsNodeArtifactsFilter> {

    final ToolArgs toolArgs;
    final String buildNumber;
    final File backupBuildDirFile;
    final Boolean useBackup;
    String nodeUrl;
    List<String> matchedArtifacts = new ArrayList<>();
    List<String> matchedFailedTests = new ArrayList<>();
    ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
    ArrayListValuedHashMap<String, TestStatus> testsStatus = new ArrayListValuedHashMap<>();

    /**
     * Creates a worker for a single build node.
     *
     * @param toolArgs artifact filters, credentials, and requested processing mode
     * @param buildNumber number of the build being processed
     * @param nodeUrl Jenkins node URL, or its encoded backup directory name
     * @param useBackup whether artifacts are read from local disk
     * @param backupBuildDirFile build-level backup directory
     */
    JenkinsNodeArtifactsFilter(ToolArgs toolArgs, String buildNumber, String nodeUrl, Boolean useBackup, File backupBuildDirFile) {
        this.toolArgs = toolArgs;
        this.buildNumber = buildNumber;
        this.nodeUrl = nodeUrl;
        this.useBackup = useBackup;
        this.backupBuildDirFile = backupBuildDirFile;
    }

    /**
        * Processes the node and returns this worker with its collected results.
        *
        * @return this worker after processing completes
        * @throws RuntimeException if artifact reading or JUnit parsing fails
     * @see java.util.concurrent.Callable#call()
     */
    @Override
    public JenkinsNodeArtifactsFilter call() {
        try {
            processNode();
        } catch (IOException | ParserConfigurationException | SAXException e) {
            String errorLog = "Exception when when processing node: build: " + buildNumber + " node: " + nodeUrl;
            System.err.println(errorLog + e.getLocalizedMessage());
            throw new RuntimeException(errorLog, e);
        }
        return this;
    }

    /**
    * Filters and reads node artifacts, then backs them up, scans text, or parses JUnit.
    * Updates the worker's matches, failures, and stability observations in place.
    * Unavailable remote nodes or artifacts are logged and skipped.
    *
    * @throws IOException if backup or local artifact access fails
    * @throws ParserConfigurationException if the XML parser cannot be configured
    * @throws SAXException if a selected JUnit report is malformed
     */
    private void processNode() throws IOException, ParserConfigurationException, SAXException {
        File backupNodeDirFile = new File(backupBuildDirFile + File.separator + nodeUrl);
        if (!useBackup && toolArgs.backupJob) {
            backupNodeDirFile = new File(backupBuildDirFile + File.separator + Main.encodeFile(nodeUrl));
            backupNodeDirFile.mkdir();
        }
        List<String> artifactsRelativePaths;
        String artifactUrlPrefix = null;
        if (useBackup) {
            artifactsRelativePaths = Arrays.asList(new FileHelper().getDirFilesList(backupNodeDirFile.getAbsolutePath(), "", false));
            nodeUrl = Main.decodeFile(nodeUrl);
        } else {
            String nodeUrlResp;
            try {
                nodeUrlResp = Main.getUrlResponse(Main.replaceUrlPrefix(nodeUrl, toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json"), toolArgs.username, toolArgs.password);
            } catch (IOException e) {
                System.err.println("Got exception when getting API response for node ".concat(nodeUrl).concat(": ").concat(e.toString()));
                return;
            }
            Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS);
            artifactsRelativePaths = JsonPath.using(conf).parse(nodeUrlResp).read(Main.artifactsRelativePathJsonPath);
            artifactsRelativePaths = artifactsRelativePaths == null ? new ArrayList<>() : artifactsRelativePaths;
            artifactUrlPrefix = toolArgs.newUrlPrefix.concat("/").concat(buildNumber).concat("/").concat(Main.replaceUrlPrefix(nodeUrl, toolArgs.jobUrl, "").replace(buildNumber.concat("/"), "").concat("/artifact/"));
        }
        for (String artifactRelativePath : artifactsRelativePaths) {
            if (!Main.artifactUrlMatchesFilters(useBackup ? Main.decodeFile(artifactRelativePath) : artifactRelativePath, toolArgs.artifactsFilters)) {
                continue;
            }
            String artifactFileContent;
            if (useBackup) {
                artifactFileContent = IOUtils.toString(new FileReader(backupNodeDirFile.getAbsoluteFile() + File.separator + artifactRelativePath));
                artifactRelativePath = Main.decodeFile(artifactRelativePath);
            } else {
                String artifactUrl = artifactUrlPrefix + artifactRelativePath.replace(" ", "%20").replace("#", "%23");
                try {
                    artifactFileContent = Main.getUrlResponse(artifactUrl, toolArgs.username, toolArgs.password);
                } catch (IOException e) {
                    System.err.println("Got exception when getting API response for artifact URL ".concat(artifactUrl).concat(": ").concat(e.toString()));
                    continue;
                }
            }
            if (toolArgs.backupJob) {
                if (!useBackup) {
                    FileUtils.writeStringToFile(new File(backupNodeDirFile.getAbsolutePath() + File.separator + Main.encodeFile(artifactRelativePath)), artifactFileContent, Charset.defaultCharset());
                }
                continue;
            }
            if (toolArgs.searchInJUnitReports || toolArgs.groupTestsFailures || toolArgs.showTestsDifferences || toolArgs.computeStabilityList) {
                FailuresMatchResult failuresMatchResult = Main.matchJUnitReportFailures(artifactFileContent, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(failuresMatchResult.matchedFailedTests);
                testsFailures.putAll(failuresMatchResult.testsFailures);
                testsStatus.putAll(failuresMatchResult.testsStatus);
                continue;
            }
            if (Main.findSearchedTextInContent(toolArgs.searchedText, artifactFileContent)) {
                matchedArtifacts.add(artifactRelativePath);
            }
        }
    }
}