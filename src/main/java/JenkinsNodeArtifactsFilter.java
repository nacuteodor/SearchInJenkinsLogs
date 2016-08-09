import com.jayway.jsonpath.JsonPath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Created by Teo on 8/8/2016.
 */
public class JenkinsNodeArtifactsFilter implements Callable<JenkinsNodeArtifactsFilter> {

    public final Integer                        buildNumber;
    public final String                         nodeUrl;
    private final String                        artifactsFilters;
    private final String                        searchedText;
    public List<String>                         matchedArtifacts = new ArrayList<String>();

    public JenkinsNodeArtifactsFilter(
            Integer buildNumber,
            String nodeUrl,
            String artifactsFilters,
            String searchedText) {

        this.buildNumber = buildNumber;
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
            String errorLog = "Exception when extracting S3 shard filtered data to local file: ";
            System.err.println( errorLog + e.getLocalizedMessage() );
            throw new RuntimeException( errorLog, e );
        }
        return this;
    }

    /**
     * Find the searched text @searchedText in the current build node artifacts in a new thread.
     * Saves the artifacts where it finds the @searchedText in matchedArtifacts list
     *
     * @throws IOException
     */
    private void processNode() throws IOException {
        String nodeUrlResp = Main.getUrlResponse(nodeUrl);
        List<String> artifactsRelativePaths = JsonPath.read(nodeUrlResp, Main.artifactsRelativePathJsonPath);
        for (String artifactRelativePath : artifactsRelativePaths) {
            if (!Main.artifactUrlMatchesFilters(artifactRelativePath, artifactsFilters)) {
                return;
            }
            String artifactUrl = nodeUrl + artifactRelativePath;
            String artifactFileContent = Main.getUrlResponse(artifactUrl);
            // replaces end of line chars with empty to be able to match all file contain with regular expression
            if (searchedText.isEmpty() || artifactFileContent.replaceAll("\\r\\n", "").replaceAll("\\n", "").matches(searchedText)) {
                matchedArtifacts.add(artifactRelativePath);
            }
        }
    }
}