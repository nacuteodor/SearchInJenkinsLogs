import com.jayway.jsonpath.Criteria;
import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONObject;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Teo on 7/30/2016.
 * A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.
 * Command line e.g:
 *     java -DjobUrl="$jobUrl" -DnewUrlPrefix="$newUrlPrefix=" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".*outputFile.*" -DsearchedText=".*textToFind.*" -cp searchinjenkinslogs.jar Main
 */
public class Main {

    private static final String buildNumbersJsonPath = "$.builds[?]";
    private static final String urlJsonPath = "$url";
    private static final String buildNumberJsonPath = "number";
    private static final String runsNumberUrlJsonPath = "$.runs[?(@.number == %d)].url";
    static final String artifactsRelativePathJsonPath = "$.artifacts[*].relativePath";

    static String getUrlResponse(String urlString) throws IOException {
        URL url = new URL(urlString);
        return IOUtils.toString(new InputStreamReader(url.openStream()));
    }

    /**
     * @return true if @artifactUrl matches regular expressions @artifactFilters filters separated by comma ","
     */
    static Boolean artifactUrlMatchesFilters(String artifactUrl, String artifactFilters) {
        String[] filters = artifactFilters.split(",");
        if ((filters.length == 0) || (filters[0].isEmpty())) {
            return true;
        }
        for(String filter : filters) {
            if (artifactUrl.matches(filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return an expanded Set of @builds parameter value
     *         For e.g 1,2,3-5, it will return a Set [1, 2, 3, 4, 5]
     */
    private static Set<Integer> parseBuilds(String builds) {
        Set<Integer> buildsSet = new HashSet<>();
        if (builds == null || builds.isEmpty()) {
            return buildsSet;
        }
        String[] buildsAsStrings = builds.split(",");
        for (String build : buildsAsStrings) {
            String[] buildsRange = build.split("-");
            if (buildsRange.length >= 2) {
                for (Integer buildNumber = Integer.parseInt(buildsRange[0]); buildNumber <= Integer.parseInt(buildsRange[1]); buildNumber++) {
                    buildsSet.add(buildNumber);
                }
            } else {
                Integer intBuild = Integer.parseInt(build);
                buildsSet.add(intBuild);
            }
        }
        return buildsSet;
    }

    public static void main(String[] args) throws IOException {
        // ======== GET AND PARSE THE ARGUMENTS VALUES ========
        String jobUrl = System.getProperty("jobUrl");
        if (jobUrl == null || jobUrl.isEmpty()) {
            throw new IllegalArgumentException("-DjobUrl parameter cannot be empty. Please, provide a valid URL!");
        }
        System.out.println("Parameter jobUrl=" + jobUrl);
        String newUrlPrefix = System.getProperty("newUrlPrefix");
        System.out.println("Parameter newUrlPrefix=" + newUrlPrefix);
        newUrlPrefix = newUrlPrefix == null ? jobUrl : newUrlPrefix;
        Integer threadPoolSize = System.getProperty("threadPoolSize") == null ? 15 : Integer.parseInt(System.getProperty("threadPoolSize"));
        System.out.println("Parameter threadPoolSize=" + threadPoolSize);
        Set<Integer> builds = parseBuilds(System.getProperty("builds"));
        // in case there is no build number specified, search in the last job build artifacts
        Integer lastBuildsCount = builds.size() == 0 ? 1 : 0;
        lastBuildsCount = System.getProperty("lastBuildsCount") == null ? lastBuildsCount : Integer.parseInt(System.getProperty("lastBuildsCount"));
        System.out.println("Parameter lastBuildsCount=" + lastBuildsCount);
        String artifactsFilters = System.getProperty("artifactsFilters") == null ? "" : System.getProperty("artifactsFilters");
        String searchedText = System.getProperty("searchedText") == null ? "" : System.getProperty("searchedText");
        System.out.println("Parameter searchedText=" + searchedText);
        String lastNBuildNumbersJsonPath = "$.builds[:" + lastBuildsCount + "].number";

        // ======== START PROCESSING THE JOB NODES IN PARALLEL ========
        String apiJobUrl = jobUrl.replace(jobUrl, newUrlPrefix) + "/api/json";
        String jobResponse = getUrlResponse(apiJobUrl);
        List<Integer> lastNBuilds;
        try {
            lastNBuilds = JsonPath.read(jobResponse, lastNBuildNumbersJsonPath);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Exception when parsing the job api response for URL " + apiJobUrl + " : " + jobResponse, e);
        }

        builds.addAll(lastNBuilds);
        System.out.println("Parameter builds=" + builds);
        Filter buildsFilter = Filter.filter(
                Criteria.where(buildNumberJsonPath).in(builds)
        );

        System.out.println("Print the nodes matching the searched text \"" + searchedText + "\" in artifacts: ");
        List<JSONObject> buildsJsons = JsonPath.read(jobResponse, buildNumbersJsonPath, buildsFilter);
        ExecutorService executorService = Executors.newFixedThreadPool( threadPoolSize );
        CompletionService<JenkinsNodeArtifactsFilter> completionService = new ExecutorCompletionService<>(
                executorService);
        Integer processCount = 0;
        for (JSONObject buildJson : buildsJsons) {
            Integer buildNumber = JsonPath.read(buildJson, buildNumberJsonPath);
            String buildUrl = JsonPath.read(buildJson, urlJsonPath);
            String buildApiResp = getUrlResponse(buildUrl.replace(jobUrl, newUrlPrefix) + "/api/json");
            List<String> nodesUrls = JsonPath.read(buildApiResp, String.format(runsNumberUrlJsonPath, buildNumber));

            for (String nodeUrl : nodesUrls) {
                completionService.submit( new JenkinsNodeArtifactsFilter(
                        jobUrl,
                        newUrlPrefix,
                        buildNumber,
                        nodeUrl,
                        artifactsFilters,
                        searchedText));

            }
            processCount += nodesUrls.size();
        }
        ArrayListValuedHashMap<String,String> buildNodesArtifacts = new ArrayListValuedHashMap<>();
        final String KEYS_SEPARATOR = "#";
        // now see if there are exceptions while computing the builds nodes and extract the results
        for( int process = 0; process < processCount; process++ ) {
            try {
                JenkinsNodeArtifactsFilter completedProcess = completionService.take().get();
                if (completedProcess.matchedArtifacts.size() > 0) {
                    buildNodesArtifacts.putAll(String.valueOf(completedProcess.buildNumber).concat(KEYS_SEPARATOR).concat(completedProcess.nodeUrl), completedProcess.matchedArtifacts);
                }
            } catch( InterruptedException e ) {
                System.err.println("Got interrupt exception when processing nodes: " + e);
                throw new IOException( "Got interrupt exception when processing nodes.", e );
            } catch( ExecutionException e ) {
                System.err.println("Got execution exception when processing nodes: " + e);
                throw new IOException( "Got execution exception when processing nodes.", e );
            }
        }
        executorService.shutdown();

        // ======== PRINT THE NODES MATCHING THE SEARCHED TEXT ========
        List<Map.Entry<String, String>> buildNodesArtifactsList = new ArrayList<>(buildNodesArtifacts.entries());
        // sort the results in ascending buildNumber_nodeUrl order
        buildNodesArtifactsList.sort((o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastBuild = "";
        String lastNode = "";

        System.out.println("-> Found the searched text in " + buildNodesArtifacts.size() + " build nodes.");
        for ( Map.Entry<String, String> buildNodeArtifact : buildNodesArtifactsList) {
            String[] buildNodeTokens = buildNodeArtifact.getKey().split(KEYS_SEPARATOR);
            String currentBuild = buildNodeTokens[0];
            String currentNode = buildNodeTokens[1];
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("Build: " + currentBuild);
            }
            if (!currentNode.equals(lastNode)) {
                lastNode = currentNode;
                System.out.println("\tNode: " + currentNode);
            }
            System.out.println("\t\tArtifact relative path: " + buildNodeArtifact.getValue());
        }
    }
}
