import com.jayway.jsonpath.Criteria;
import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONObject;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Teo on 7/30/2016.
 * A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.
 * Command line e.g:
 * java -DjobUrl="$jobUrl" -DnewUrlPrefix="$newUrlPrefix=" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".*outputFile.*" -DsearchedText=".*textToFind.*" -cp searchinjenkinslogs.jar Main
 */
public class Main {

    private static final String buildNumbersJsonPath = "$.builds[?]";
    private static final String urlJsonPath = "$url";
    private static final String buildNumberJsonPath = "number";
    private static final String runsNumberUrlJsonPath = "$.runs[?(@.number == %d)].url";
    static final String artifactsRelativePathJsonPath = "$.artifacts[*].relativePath";
    private static final String KEYS_SEPARATOR = "#";

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
        for (String filter : filters) {
            if (artifactUrl.matches(filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return an expanded Set of @builds parameter value
     * For e.g 1,2,3-5, it will return a Set [1, 2, 3, 4, 5]
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

    static Boolean findSearchedTextInContent(String searchedText, String content) {
        // replaces end of line chars with empty to be able to match all file contain with regular expression
        return (searchedText.isEmpty() || content.replaceAll("\\r\\n", "").replaceAll("\\n", "").matches(searchedText));
    }

    /**
     * Encodes "\n" characters between <failure...</failure> with "&#10;", so those won't be replaced with space char when parsing the xml document
     *
     * @param xml the xml as String, we need to transform
     * @return a new xml
     */
    private static String encodeNewLineCharInFailureElement(String xml, String failureTag, String failureEndTag) {
        String newXml = "";
        boolean replace = false;
        while (!xml.isEmpty()) {
            int newLinePosition = xml.indexOf("\n");
            if (newLinePosition == -1) {
                newXml += xml;
                break;
            }
            String newLine = xml.substring(0, newLinePosition);
            if (!newLine.contains(failureEndTag)) {
                if (newLine.contains(failureTag)) {
                    newLine += "&#10;";
                    replace = true;
                } else if (replace) {
                    newLine += "&#10;";
                } else {
                    newLine += "\n";
                }
            } else {
                replace = false;
                newLine += "\n";
            }
            newXml += newLine;
            xml = xml.substring(newLinePosition + 1);
        }
        return newXml;
    }

    private static String encodeNewLineCharInFailureElements(String xml) {
        String newXml = encodeNewLineCharInFailureElement(xml, "<failure message=", "</failure>");
        newXml = encodeNewLineCharInFailureElement(newXml, "<error message=", "</error>");
        return newXml;
    }

    private static String buildTestReportLink(String node, String test) {
        return node + "testReport/junit/" + test;
    }

    /**
     * Matches the @searchedText in each test failure for a test and return a list with the Jenkins links to the failed tests reports
     *
     * @param failureNodes the xml failure/error nodes for a test case element
     * @param testUrl the test url
     * @param buildNumber build number
     * @param nodeUrl node URL
     * @param searchInJUnitReports true if we search a regular expression in failure messages
     * @param groupTestsFailures true if it groups failures based on stacktrace and failure message
     * @param searchedText the regular expression to match with the failure message
     * @return a list with the Jenkins links to the failed tests reports
     */
   private static FailuresMatchResult matchTestCaseFailures(NodeList failureNodes, String testUrl, String buildNumber, String nodeUrl, boolean searchInJUnitReports, boolean groupTestsFailures, String searchedText) {
       List<String> matchedFailedTests = new ArrayList<>();
       ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
       for (int failureNodeIndex = 0; failureNodeIndex < failureNodes.getLength(); failureNodeIndex++) {
           Element failureElement = (Element) failureNodes.item(failureNodeIndex);
           String message = failureElement.getAttribute("message");
           if (searchInJUnitReports && Main.findSearchedTextInContent(searchedText, message)) {
               matchedFailedTests.add(testUrl);
           }
           if (groupTestsFailures) {
               String stacktrace = failureElement.getTextContent();
               String failureToCompare = stacktrace + ": " + message.split("\\n")[0];
               testsFailures.put(testUrl, new TestFailure(buildNumber, nodeUrl, buildTestReportLink(nodeUrl, testUrl), failureToCompare, failureToCompare.length() >= 200 ? failureToCompare.substring(0, 200) + " ..." : failureToCompare));
           }
       }
       return new FailuresMatchResult(matchedFailedTests, testsFailures);
    }

    /**
     * Matches the @searchedText in each test failure and return a list with the Jenkins links to the failed tests reports
     *
     * @param jUnitReportXml the JUnit xml report as a String
     * @param buildNumber build number
     * @param nodeUrl node URL
     * @param searchInJUnitReports true if we search a regular expression in failure messages
     * @param groupTestsFailures true if it groups failures based on stacktrace and failure message
     * @param searchedText the regular expression to match with the failure message
     * @return a list with the Jenkins links to the failed tests reports
     * @throws ParserConfigurationException
     * @throws IOException
     * @throws SAXException
     */
    static FailuresMatchResult matchJUnitReportFailures(String jUnitReportXml, String buildNumber, String nodeUrl,
                                                        boolean searchInJUnitReports, boolean groupTestsFailures, String searchedText) throws ParserConfigurationException, IOException, SAXException {
        List<String> matchedFailedTests = new ArrayList<>();
        ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
        DocumentBuilderFactory dbFactory
                = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        jUnitReportXml = encodeNewLineCharInFailureElements(jUnitReportXml);
        Document doc = dBuilder.parse(new ByteArrayInputStream(jUnitReportXml.getBytes()));
        doc.getDocumentElement().normalize();
        NodeList testCasesList = doc.getElementsByTagName("testcase");
        for (int testCaseIndex = 0; testCaseIndex < testCasesList.getLength(); testCaseIndex++) {
            Node testCaseNode = testCasesList.item(testCaseIndex);
            if (testCaseNode.getNodeType() == Node.ELEMENT_NODE) {
                Element testCaseElement = (Element) testCaseNode;
                String testUrl = testCaseElement.getAttribute("classname").replace(".", "/") + "/" + testCaseElement.getAttribute("name").replaceAll("[.\\\\()\\[\\]/-]", "_");
                NodeList failureNodes = testCaseElement.getElementsByTagName("failure");
                FailuresMatchResult failuresMatchResult = matchTestCaseFailures(failureNodes, testUrl, buildNumber, nodeUrl, searchInJUnitReports, groupTestsFailures, searchedText);
                matchedFailedTests.addAll(failuresMatchResult.matchedFailedTests);
                testsFailures.putAll(failuresMatchResult.testsFailures);
                NodeList errorNodes = testCaseElement.getElementsByTagName("error");
                FailuresMatchResult errorsMatchResult = matchTestCaseFailures(errorNodes, testUrl, buildNumber, nodeUrl, searchInJUnitReports, groupTestsFailures, searchedText);
                matchedFailedTests.addAll(errorsMatchResult.matchedFailedTests);
                testsFailures.putAll(errorsMatchResult.testsFailures);
            }
        }
        return new FailuresMatchResult(matchedFailedTests, testsFailures);
    }

    public static void main(String[] args) throws IOException {
        // ======== GET AND PARSE THE ARGUMENTS VALUES ========
        String jobUrl = System.getProperty("jobUrl");
        if (StringUtils.isEmpty(jobUrl)) {
            throw new IllegalArgumentException("-DjobUrl parameter cannot be empty. Please, provide a valid URL!");
        }
        System.out.println("Parameter jobUrl=" + jobUrl);
        String newUrlPrefix = System.getProperty("newUrlPrefix");
        System.out.println("Parameter newUrlPrefix=" + newUrlPrefix);
        newUrlPrefix = StringUtils.isEmpty(newUrlPrefix) ? jobUrl : newUrlPrefix;
        Boolean searchInJUnitReports = StringUtils.isEmpty(System.getProperty("searchInJUnitReports")) ? false : Boolean.valueOf(System.getProperty("searchInJUnitReports"));
        System.out.println("Parameter searchInJUnitReports=" + searchInJUnitReports);
        String threadPoolSizeString = System.getProperty("threadPoolSize");
        Integer threadPoolSize = StringUtils.isEmpty(System.getProperty("threadPoolSize")) ? 0 : Integer.parseInt(threadPoolSizeString);
        System.out.println("Parameter threadPoolSize=" + threadPoolSize);
        Set<Integer> builds = parseBuilds(System.getProperty("builds"));
        // in case there is no build number specified, search in the last job build artifacts
        Integer lastBuildsCount = builds.size() == 0 ? 1 : 0;
        lastBuildsCount = StringUtils.isEmpty(System.getProperty("lastBuildsCount")) ? lastBuildsCount : Integer.parseInt(System.getProperty("lastBuildsCount"));
        System.out.println("Parameter lastBuildsCount=" + lastBuildsCount);
        String artifactsFilters = System.getProperty("artifactsFilters") == null ? "" : System.getProperty("artifactsFilters");
        String searchedText = System.getProperty("searchedText") == null ? "" : System.getProperty("searchedText");
        System.out.println("Parameter searchedText=" + searchedText);
        Boolean groupTestsFailures = StringUtils.isEmpty(System.getProperty("groupTestsFailures")) ? false : Boolean.valueOf(System.getProperty("groupTestsFailures"));
        System.out.println("Parameter groupTestsFailures=" + groupTestsFailures);
        // the maximum difference threshold as a percentage of difference distance between 2 failures and the maximum possible distance for the shorter failure
        double diffThreshold = StringUtils.isEmpty(System.getProperty("diffThreshold")) ? 10 : Double.valueOf(System.getProperty("diffThreshold"));
        System.out.println("Parameter diffThreshold=" + diffThreshold);

        final String lastNBuildNumbersJsonPath = "$.builds[:" + lastBuildsCount + "].number";

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

        System.out.println("\nPrint the nodes matching the searched text \"" + searchedText + "\" in artifacts: ");
        List<JSONObject> buildsJsons = JsonPath.read(jobResponse, buildNumbersJsonPath, buildsFilter);
        ExecutorService executorService;
        if (threadPoolSize <= 0) {
            executorService = Executors.newWorkStealingPool();
        } else {
            executorService = Executors.newFixedThreadPool(threadPoolSize);
        }
        CompletionService<JenkinsNodeArtifactsFilter> completionService = new ExecutorCompletionService<>(
                executorService);
        Integer processCount = 0;
        for (JSONObject buildJson : buildsJsons) {
            Integer buildNumber = JsonPath.read(buildJson, buildNumberJsonPath);
            String buildUrl = JsonPath.read(buildJson, urlJsonPath);
            String buildApiResp = getUrlResponse(buildUrl.replace(jobUrl, newUrlPrefix) + "/api/json");
            List<String> nodesUrls = JsonPath.read(buildApiResp, String.format(runsNumberUrlJsonPath, buildNumber));

            for (String nodeUrl : nodesUrls) {
                completionService.submit(new JenkinsNodeArtifactsFilter(
                        jobUrl,
                        newUrlPrefix,
                        buildNumber.toString(),
                        nodeUrl,
                        artifactsFilters,
                        searchInJUnitReports,
                        groupTestsFailures,
                        searchedText));

            }
            processCount += nodesUrls.size();
        }
        ArrayListValuedHashMap<String, String> buildNodesArtifacts = new ArrayListValuedHashMap<>();
        ArrayListValuedHashMap<String, TestFailure> buildNodesFailures = new ArrayListValuedHashMap<>();
        // now see if there are exceptions while computing the builds nodes and extract the results
        for (int process = 0; process < processCount; process++) {
            try {
                JenkinsNodeArtifactsFilter completedProcess = completionService.take().get();
                if (completedProcess.matchedArtifacts.size() > 0) {
                    buildNodesArtifacts.putAll(String.valueOf(completedProcess.buildNumber).concat(KEYS_SEPARATOR).concat(completedProcess.nodeUrl), completedProcess.matchedArtifacts);
                }
                if (completedProcess.matchedFailedTests.size() > 0) {
                    buildNodesArtifacts.putAll(String.valueOf(completedProcess.buildNumber).concat(KEYS_SEPARATOR).concat(completedProcess.nodeUrl), completedProcess.matchedFailedTests);
                }
                if (completedProcess.testsFailures.size() > 0) {
                    for (Map.Entry<String, TestFailure> entry : completedProcess.testsFailures.entries()) {
                        buildNodesFailures.putAll(String.valueOf(completedProcess.buildNumber).concat(KEYS_SEPARATOR).concat(entry.getKey()).concat(entry.getValue().failureToDisplay), entry.getValue());
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("Got interrupt exception when processing nodes: " + e);
                throw new IOException("Got interrupt exception when processing nodes.", e);
            } catch (ExecutionException e) {
                System.err.println("Got execution exception when processing nodes: " + e);
                throw new IOException("Got execution exception when processing nodes.", e);
            }
        }
        executorService.shutdown();

        // ======== PRINT THE NODES/TESTS MATCHING THE SEARCHED TEXT ========
        // sort the results in ascending buildNumber_nodeUrl order
        Map.Entry<String, String>[] buildNodesArtifactsArray = buildNodesArtifacts.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesArtifactsArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastBuild = "";
        String lastNode = "";

        System.out.println("-> Found the searched text in " + buildNodesArtifacts.keySet().size() + " build nodes.");
        for (Map.Entry<String, String> buildNodeArtifact : buildNodesArtifactsArray) {
            String[] buildNodeTokens = buildNodeArtifact.getKey().split(KEYS_SEPARATOR);
            String currentBuild = buildNodeTokens[0];
            String currentNode = buildNodeTokens[1];
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("\nBuild: " + currentBuild);
            }
            if (!currentNode.equals(lastNode)) {
                lastNode = currentNode;
                System.out.println("\tNode: " + currentNode);
            }
            if (searchInJUnitReports) {
                System.out.println("\t\tFailed test report: " + buildTestReportLink(currentNode, buildNodeArtifact.getValue()));
            } else {
                System.out.println("\t\tArtifact relative path: " + buildNodeArtifact.getValue());
            }
        }

        if (!groupTestsFailures) {
            return;
        }

        // ======== PRINT THE COMMON FAILURES ========
        System.out.println("\nGroup common failures from tests reports: ");
        ArrayListValuedHashMap<String, TestFailure> groupedBuildNodesFailures = new ArrayListValuedHashMap<>();
        for (Map.Entry<String, TestFailure> buildNodeFailure : buildNodesFailures.entries()) {
            String failureKey = buildNodeFailure.getKey();
            String groupedBuildNodeFailureKey = null;
            for (Map.Entry<String, TestFailure> groupedBuildNodeFailure : groupedBuildNodesFailures.entries()) {
                Integer maxDistance = Math.min(buildNodeFailure.getValue().failureToCompare.length(), groupedBuildNodeFailure.getValue().failureToCompare.length());
                if (maxDistance == 0) {
                    groupedBuildNodeFailureKey = groupedBuildNodeFailure.getKey();
                    break;
                }
                int distanceThreshold = diffThreshold < 0 ? 0 : (int) Math.ceil((maxDistance * diffThreshold) / 100);
                double currentThreshold = (double) StringUtils.getLevenshteinDistance(buildNodeFailure.getValue().failureToCompare, groupedBuildNodeFailure.getValue().failureToCompare, distanceThreshold);
                currentThreshold = currentThreshold == -1 ? maxDistance : currentThreshold;
                currentThreshold = currentThreshold * 100 / maxDistance;
                if (currentThreshold <= diffThreshold) {
                    groupedBuildNodeFailureKey = groupedBuildNodeFailure.getKey();
                    break;
                }
            }
            if (groupedBuildNodeFailureKey != null) {
                groupedBuildNodesFailures.put(groupedBuildNodeFailureKey, buildNodeFailure.getValue());
            } else {
                groupedBuildNodesFailures.put(failureKey, buildNodeFailure.getValue());
            }
        }

        // add failure tests count to be able to sort in the most frequent failures
        ArrayListValuedHashMap<String, TestFailure> groupedBuildFailures = new ArrayListValuedHashMap<>();
        String failuresCountFormat = "%0" + String.valueOf(groupedBuildNodesFailures.size()).length() + "d";
        for (String key : groupedBuildNodesFailures.keySet()) {
            List<TestFailure> values = groupedBuildNodesFailures.get(key);
            TestFailure[] valuesArray = values.toArray(new TestFailure[0]);
            Arrays.parallelSort(valuesArray, (o1, o2) -> o1.testUrl.compareTo(o2.testUrl));
            groupedBuildFailures.putAll(String.format(failuresCountFormat, values.size()).concat(KEYS_SEPARATOR).concat(key), Arrays.asList(valuesArray));
        }
        System.out.println("-> Found " + groupedBuildFailures.keySet().size() + " common failures.");

        // sort the results by count#failure#firstTestUrl#buildNumber ascending order
        Map.Entry<String, TestFailure>[] groupedBuildFailuresArray = groupedBuildFailures.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(groupedBuildFailuresArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastKey = "";
        lastBuild = "";
        for (Map.Entry<String, TestFailure> buildFailure : groupedBuildFailuresArray) {
            String currentKey = buildFailure.getKey();
            String currentBuild = buildFailure.getValue().buildNumber;
            if (!currentKey.equals(lastKey)) {
                lastKey = currentKey;
                lastBuild = "";
                System.out.println("\n\tFailure: " + buildFailure.getValue().failureToDisplay);
                System.out.println("\t-> Found " + groupedBuildFailures.get(currentKey).size() + " failures.");
            }
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("\t\tBuild: " + currentBuild);
            }
            System.out.println("\t\t\tFailed test report: " + buildFailure.getValue().failureToDisplay);
        }
    }
}