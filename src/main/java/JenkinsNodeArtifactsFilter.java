import com.jayway.jsonpath.JsonPath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Created by Teo on 8/8/2016.
 */
class JenkinsNodeArtifactsFilter implements Callable<JenkinsNodeArtifactsFilter> {

    final Integer                        buildNumber;
    final String                         jobUrl;
    final String                         newUrlPrefix;
    final String                         nodeUrl;
    private final String                 artifactsFilters;
    private final String                 searchedText;

    List<String>                         matchedArtifacts = new ArrayList<>();

    JenkinsNodeArtifactsFilter(
            String jobUrl,
            String newUrlPrefix,
            Integer buildNumber,
            String nodeUrl,
            String artifactsFilters,
            String searchedText) {

        this.jobUrl = jobUrl;
        this.buildNumber = buildNumber;
        this.newUrlPrefix = newUrlPrefix;
        this.nodeUrl = nodeUrl;
        this.artifactsFilters = artifactsFilters;
        this.searchedText = searchedText;
    }

    /**
     * @see java.util.concurrent.Callable#call()
     */
    @Override
    public JenkinsNodeArtifactsFilter call() {
        try {
            processNode();
        } catch( IOException e ) {
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
    private void processNode() throws IOException {
        String nodeUrlResp = Main.getUrlResponse(nodeUrl.replace(jobUrl, newUrlPrefix) + "/api/json");
        List<String> artifactsRelativePaths = JsonPath.read(nodeUrlResp, Main.artifactsRelativePathJsonPath);
        String artifactUrlPrefix = newUrlPrefix + "/" + buildNumber + "/" + nodeUrl.replace(jobUrl, "").replace(buildNumber + "/", "") + "/artifact/";
        for (String artifactRelativePath : artifactsRelativePaths) {
            if (!Main.artifactUrlMatchesFilters(artifactRelativePath, artifactsFilters)) {
                continue;
            }
            String artifactUrl =  artifactUrlPrefix + artifactRelativePath;
            String artifactFileContent = Main.getUrlResponse(artifactUrl);
            // replaces end of line chars with empty to be able to match all file contain with regular expression
            if (searchedText.isEmpty() || artifactFileContent.replaceAll("\\r\\n", "").replaceAll("\\n", "").matches(searchedText)) {
                matchedArtifacts.add(artifactRelativePath);
            }
        }
    }
}