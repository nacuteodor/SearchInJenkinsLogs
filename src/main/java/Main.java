import com.jayway.jsonpath.JsonPath;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.map.HashedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Created by Teo on 7/30/2016.
 * A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.
 * Command line e.g:
 * java -DjobUrl="$jobUrl" -DnewUrlPrefix="$newUrlPrefix=" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".*outputFile.*" -DsearchedText=".*textToFind.*" -cp searchinjenkinslogs.jar Main
 */
public class Main {

    private static final String buildsJsonPath = "$.builds[?]";
    private static final String urlJsonPath = "$url";
    private static final String buildNumberJsonPath = "number";
    private static final String runsNumberUrlJsonPath = "$.runs[?(@.number == %d)].url";
    private static final String KEYS_SEPARATOR = "#";
    static final String artifactsRelativePathJsonPath = "$.artifacts[*].relativePath";
    static final String buildsNumberJsonPath = "$.builds[*].number";
    static final String buildsNumberUrlJsonPath = "$.builds[?(@.number == %d)].url";

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
                newXml = newXml.concat(xml);
                break;
            }
            String newLine = xml.substring(0, newLinePosition);
            if (!newLine.contains(failureEndTag)) {
                if (newLine.contains(failureTag)) {
                    newLine = newLine.concat("&#10;");
                    replace = true;
                } else if (replace) {
                    newLine = newLine.concat("&#10;");
                } else {
                    newLine = newLine.concat("\n");
                }
            } else {
                replace = false;
                newLine = newLine.concat("\n");
            }
            newXml = newXml.concat(newLine);
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
        return node.concat("testReport/junit/").concat(test);
    }

    /**
     * Matches the @searchedText in each test failure for a test and return a list with the Jenkins links to the failed tests reports
     *
     * @param failureNodes the xml failure/error nodes for a test case element
     * @param testUrl      the test url
     * @param buildNumber  build number
     * @param nodeUrl      node URL
     * @param toolArgs:    toolArgs.searchInJUnitReports true if we search a regular expression in failure messages
     *                     toolArgs.groupTestsFailures true if it groups failures based on stacktrace and failure message
     *                     toolArgs.searchedText the regular expression to match with the failure message
     * @return a list with the Jenkins links to the failed tests reports
     */
    private static FailuresMatchResult matchTestCaseFailures(NodeList failureNodes, String testUrl, String testName, String buildNumber, String nodeUrl, ToolArgs toolArgs) {
        List<String> matchedFailedTests = new ArrayList<>();
        ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
        for (int failureNodeIndex = 0; failureNodeIndex < failureNodes.getLength(); failureNodeIndex++) {
            Element failureElement = (Element) failureNodes.item(failureNodeIndex);
            String message = failureElement.getAttribute("message");
            if (toolArgs.searchInJUnitReports && Main.findSearchedTextInContent(toolArgs.searchedText, message)) {
                matchedFailedTests.add(testUrl);
            }
            if (toolArgs.groupTestsFailures || toolArgs.showTestsDifferences) {
                String stacktrace = failureElement.getTextContent();
                String failureToCompare = stacktrace.concat(": ").concat(message.split("\\n")[0]);
                testsFailures.put(testUrl, new TestFailure(buildNumber, nodeUrl, buildTestReportLink(nodeUrl, testUrl), testName, failureToCompare, failureToCompare.length() >= 200 ? failureToCompare.substring(0, 200) + " ..." : failureToCompare));
            }
        }
        return new FailuresMatchResult(matchedFailedTests, testsFailures);
    }

    /**
     * Matches the @searchedText in each test failure and return a list with the Jenkins links to the failed tests reports
     *
     * @param jUnitReportXml the JUnit xml report as a String
     * @param buildNumber    build number
     * @param nodeUrl        node URL
     * @param toolArgs:      toolArgs.searchInJUnitReports true if we search a regular expression in failure messages
     *                       toolArgs.groupTestsFailures true if it groups failures based on stacktrace and failure message
     *                       toolArgs.searchedText the regular expression to match with the failure message
     * @return a list with the Jenkins links to the failed tests reports
     * @throws ParserConfigurationException
     * @throws IOException
     * @throws SAXException
     */
    static FailuresMatchResult matchJUnitReportFailures(String jUnitReportXml, String buildNumber, String nodeUrl, ToolArgs toolArgs) throws ParserConfigurationException, IOException, SAXException {
        List<String> matchedFailedTests = new ArrayList<>();
        Map<String, Integer> testsCount = new HashedMap<>();
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
                String testName = testCaseElement.getAttribute("classname").concat(".").concat(testCaseElement.getAttribute("name"));
                String testUrl = testCaseElement.getAttribute("classname").replace(".", "/").concat("/").concat(testCaseElement.getAttribute("name").replaceAll("[.\\\\()\\[\\]/-]", "_"));
                Integer testCount = testsCount.get(testUrl);
                testCount = testCount == null ? 0 : testCount;
                testsCount.put(testUrl, ++testCount);
                testUrl = testCount < 2 ? testUrl : testUrl.concat("_").concat(String.valueOf(testCount));
                NodeList failureNodes = testCaseElement.getElementsByTagName("failure");
                FailuresMatchResult failuresMatchResult = matchTestCaseFailures(failureNodes, testUrl, testName, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(failuresMatchResult.matchedFailedTests);
                testsFailures.putAll(failuresMatchResult.testsFailures);
                NodeList errorNodes = testCaseElement.getElementsByTagName("error");
                FailuresMatchResult errorsMatchResult = matchTestCaseFailures(errorNodes, testUrl, testName, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(errorsMatchResult.matchedFailedTests);
                testsFailures.putAll(errorsMatchResult.testsFailures);
            }
        }
        return new FailuresMatchResult(matchedFailedTests, testsFailures);
    }

    static String encodeFile(String file) throws UnsupportedEncodingException {
        return URLEncoder.encode(file, Charset.defaultCharset().name());
    }

    static String decodeFile(String file) throws UnsupportedEncodingException {
        return URLDecoder.decode(file, Charset.defaultCharset().name());
    }

    private static Set<Integer> updatedBuildsAndGetBackupBuilds(Set<Integer> builds, List<Integer> lastNBuilds, Integer lastBuildsCount, String jobResponse, File backupJobDirFile, Boolean backupJob, Boolean useBackup) {
        Set<Integer> backupBuilds = new HashSet<>();
        List<Integer> allAvailableBackupBuilds = new ArrayList<>();
        if (!backupJob && useBackup) {
            backupBuilds.addAll(builds);
        }
        if ((backupJob || useBackup) && backupJobDirFile.exists()) {
            allAvailableBackupBuilds.addAll(Arrays.asList(Arrays.asList(Arrays.asList(new FileHelper().getDirFilesList(backupJobDirFile.getAbsolutePath(), "", false)).stream().map(new Function<String, Integer>() {
                @Override
                public Integer apply(String t) {
                    String[] tokens = t.split(KEYS_SEPARATOR);
                    Integer buildNumber = Integer.valueOf(tokens[0]);
                    return buildNumber;
                }
            }).toArray()).toArray(new Integer[0])));
            allAvailableBackupBuilds.removeIf(new Predicate<Integer>() {
                @Override
                public boolean test(Integer integer) {
                    File unfinishedBackupBuildDirFile = new File(backupJobDirFile + File.separator + integer + KEYS_SEPARATOR + "Unfinished");
                    return unfinishedBackupBuildDirFile.exists();
                }
            });
            allAvailableBackupBuilds.sort(null);
            // remove the last backup job build from backup builds list, as this may not be a finished build
            if (allAvailableBackupBuilds.size() > 0) {
                allAvailableBackupBuilds.remove(allAvailableBackupBuilds.size() - 1);
            }
        }
        List<Integer> allAvailableBuildsList = JsonPath.read(jobResponse, buildsNumberJsonPath);
        if (backupJob) {
            builds.clear();
            builds.addAll(allAvailableBuildsList);
            builds.removeAll(allAvailableBackupBuilds);
        } else {
            builds.retainAll(allAvailableBuildsList);
            backupBuilds.retainAll(allAvailableBackupBuilds);
        }
        // remove the last available job build from backup builds list, as this may not be a finished build
        if (allAvailableBuildsList.size() > 0) {
            backupBuilds.remove(allAvailableBuildsList.get(0));
        }
        if (lastNBuilds.size() < lastBuildsCount) {
            allAvailableBackupBuilds.removeAll(lastNBuilds);
            allAvailableBackupBuilds.sort(null);
            if (allAvailableBackupBuilds.size() > 0) {
                List<Integer> otherBuildsFromBackup = allAvailableBackupBuilds.subList(allAvailableBackupBuilds.size() - Math.min(allAvailableBackupBuilds.size(), lastBuildsCount - lastNBuilds.size()), allAvailableBackupBuilds.size());
                backupBuilds.addAll(otherBuildsFromBackup);
            }
        }
        builds.addAll(backupBuilds);
        return backupBuilds;
    }

    private static void computeBuilds(ToolArgs toolArgs, Set<Integer> backupBuilds, String jobResponse) throws IOException {
        if (toolArgs.useBackup || toolArgs.backupJob || toolArgs.removeBackup) {
            Validate.notEmpty(toolArgs.backupPath, "backupPath parameter is empty!");
            toolArgs.backupJobDirFile = new File(toolArgs.backupPath + File.separator + encodeFile(toolArgs.jobUrl));
            toolArgs.backupJobDirFile.mkdirs();
        }
        List<Integer> lastNBuilds = new ArrayList<>();
        try {
            if (toolArgs.lastBuildsCount > 0) {
                lastNBuilds = JsonPath.read(jobResponse, buildsNumberJsonPath);
                lastNBuilds = lastNBuilds.subList(0, Math.min(toolArgs.lastBuildsCount, lastNBuilds.size()));
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Exception when parsing the job api response for URL " + toolArgs.jobUrl + " : " + jobResponse, e);
        }
        toolArgs.builds.addAll(lastNBuilds);
        backupBuilds.addAll(updatedBuildsAndGetBackupBuilds(toolArgs.builds, lastNBuilds, toolArgs.lastBuildsCount, jobResponse, toolArgs.backupJobDirFile, toolArgs.backupJob, toolArgs.useBackup));
        List<Integer> sortedBuilds = new ArrayList<>(toolArgs.builds);
        sortedBuilds.sort(null);
        System.out.println("Parameter builds=" + sortedBuilds);
    }

    private static Integer submitBuildNodes(CompletionService<JenkinsNodeArtifactsFilter> completionService, ToolArgs toolArgs) throws IOException {
        String apiJobUrl = toolArgs.jobUrl.replace(toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json");
        String jobResponse = getUrlResponse(apiJobUrl);
        Set<Integer> backupBuilds = new HashSet<>();
        computeBuilds(toolArgs, backupBuilds, jobResponse);
        Integer processCount = 0;
        for (Integer buildNumber : toolArgs.builds) {
            List<String> nodesUrls;
            File unfinishedBackupBuildDirFile = new File(String.valueOf(toolArgs.backupJobDirFile).concat(File.separator).concat(String.valueOf(buildNumber)).concat(KEYS_SEPARATOR).concat("Unfinished"));
            File finishedBackupBuildDirFile = new File(String.valueOf(toolArgs.backupJobDirFile).concat(File.separator).concat(String.valueOf(buildNumber)));
            File backupBuildDirFile = finishedBackupBuildDirFile;
            if (toolArgs.backupJob) {
                backupBuildDirFile = unfinishedBackupBuildDirFile;
            }
            Boolean useBackup = backupBuilds.contains(buildNumber);
            if (useBackup) {
                nodesUrls = Arrays.asList(new FileHelper().getDirFilesList(backupBuildDirFile.getAbsolutePath(), "", false));
            } else {
                String buildUrl = ((List<String>) JsonPath.read(jobResponse, String.format(buildsNumberUrlJsonPath, buildNumber))).get(0);
                String buildApiResp;
                try {
                    buildApiResp = getUrlResponse(buildUrl.replace(toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json"));
                } catch (IOException e) {
                    System.err.println("Got exception when getting API response for job build URL ".concat(buildUrl).concat(": ").concat(e.toString()));
                    continue;
                }
                nodesUrls = JsonPath.read(buildApiResp, String.format(runsNumberUrlJsonPath, buildNumber));
            }
            if (!useBackup && (toolArgs.removeBackup || toolArgs.backupJob)) {
                FileUtils.deleteQuietly(finishedBackupBuildDirFile);
                FileUtils.deleteQuietly(unfinishedBackupBuildDirFile);
            }
            if (toolArgs.backupJob) {
                backupBuildDirFile.mkdir();
            }

            for (String nodeUrl : nodesUrls) {
                completionService.submit(new JenkinsNodeArtifactsFilter(toolArgs, String.valueOf(buildNumber), nodeUrl, useBackup, backupBuildDirFile));
            }
            processCount += nodesUrls.size();
        }
        return processCount;
    }

    private static void printTheNodesOrTestsMatchingSearchedText(ToolArgs toolArgs, MultiValuedMap<String, String> buildNodesArtifacts) {
        System.out.println("\nPrint the nodes matching the searched text \"" + toolArgs.searchedText + "\" in artifacts: ");
        System.out.println("-> Found the searched text in ".concat(String.valueOf(buildNodesArtifacts.keySet().size())).concat(" build nodes."));
        // sort the results in ascending buildNumber_nodeUrl order
        Map.Entry<String, String>[] buildNodesArtifactsArray = buildNodesArtifacts.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesArtifactsArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastBuild = "";
        String lastNode = "";

        for (Map.Entry<String, String> buildNodeArtifact : buildNodesArtifactsArray) {
            String[] buildNodeTokens = buildNodeArtifact.getKey().split(KEYS_SEPARATOR);
            String currentBuild = buildNodeTokens[0];
            String currentNode = buildNodeTokens[1];
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("\nBuild: ".concat(currentBuild));
            }
            if (!currentNode.equals(lastNode)) {
                lastNode = currentNode;
                System.out.println("\tNode: ".concat(currentNode));
            }
            if (toolArgs.searchInJUnitReports) {
                System.out.println("\t\tFailed test report: ".concat(buildTestReportLink(currentNode, buildNodeArtifact.getValue())));
            } else {
                System.out.println("\t\tArtifact relative path: ".concat(buildNodeArtifact.getValue()));
            }
        }
    }

    private static Boolean failuresAreEqual(String failure1, String failure2, Double diffThreshold) {
        Integer maxDistance = Math.min(failure1.length(), failure2.length());
        if (maxDistance == 0) {
            return true;
        }
        int distanceThreshold = diffThreshold < 0 ? 0 : (int) Math.ceil((maxDistance * diffThreshold) / 100);
        double currentThreshold = (double) StringUtils.getLevenshteinDistance(failure1, failure2, distanceThreshold);
        currentThreshold = currentThreshold == -1 ? maxDistance : currentThreshold;
        currentThreshold = currentThreshold * 100 / maxDistance;
        if (currentThreshold <= diffThreshold) {
            return true;
        }
        return false;
    }

    private static void printTheCommonFailures(ToolArgs toolArgs, MultiValuedMap<String, TestFailure> buildNodesFailures) {
        System.out.println("\nGroup common failures from tests reports: ");
        MultiValuedMap<String, TestFailure> groupedBuildNodesFailures = new ArrayListValuedHashMap<>();
        for (Map.Entry<String, TestFailure> buildNodeFailure : buildNodesFailures.entries()) {
            String failureKey = buildNodeFailure.getKey();
            String groupedBuildNodeFailureKey = null;
            for (Map.Entry<String, TestFailure> groupedBuildNodeFailure : groupedBuildNodesFailures.entries()) {
                if (failuresAreEqual(buildNodeFailure.getValue().failureToCompare, groupedBuildNodeFailure.getValue().failureToCompare, toolArgs.diffThreshold)) {
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
        String failuresCountFormat = "%0".concat(String.valueOf(String.valueOf(groupedBuildNodesFailures.size()).length())).concat("d");
        for (String key : groupedBuildNodesFailures.keySet()) {
            Collection<TestFailure> values = groupedBuildNodesFailures.get(key);
            TestFailure[] valuesArray = values.toArray(new TestFailure[0]);
            Arrays.parallelSort(valuesArray, (o1, o2) -> o1.testUrl.compareTo(o2.testUrl));
            groupedBuildFailures.putAll(String.format(failuresCountFormat, values.size()).concat(KEYS_SEPARATOR).concat(key), Arrays.asList(valuesArray));
        }
        System.out.println("-> Found ".concat(String.valueOf(groupedBuildFailures.keySet().size())).concat(" common failures."));

        // sort the results by count#failure#firstTestUrl#buildNumber ascending order
        Map.Entry<String, TestFailure>[] groupedBuildFailuresArray = groupedBuildFailures.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(groupedBuildFailuresArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastKey = "";
        String lastBuild = "";
        for (Map.Entry<String, TestFailure> buildFailure : groupedBuildFailuresArray) {
            String currentKey = buildFailure.getKey();
            String currentBuild = buildFailure.getValue().buildNumber;
            if (!currentKey.equals(lastKey)) {
                lastKey = currentKey;
                lastBuild = "";
                System.out.println("\n\tFailure: ".concat(buildFailure.getValue().failureToDisplay));
                System.out.println("\t-> Found ".concat(String.valueOf(groupedBuildFailures.get(currentKey).size())).concat(" failures."));
            }
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("\t\tBuild: " + currentBuild);
            }
            System.out.println("\t\t\tFailed test report: ".concat(buildFailure.getValue().testUrl));
        }
    }

    private static void printTheTestFailuresDifference(ToolArgs toolArgs, MultiValuedMap<String, TestFailure> buildNodesTestFailures, MultiValuedMap<String, TestFailure> buildNodesTestFailures2) {
        System.out.println("\nShow the test failures differences between 2 builds: ");
        System.out.println("-> Found ".concat(String.valueOf(buildNodesTestFailures.keySet().size())).concat(" instead of ").concat(String.valueOf(buildNodesTestFailures.keySet().size())).concat(" failed tests."));
        // sort the results by testName in ascending order
        Map.Entry<String, TestFailure>[] buildNodesTestFailuresArray = buildNodesTestFailures.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesTestFailuresArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));

        // compare the tests failures and print the difference
        ArrayListValuedHashMap<String, TestFailure> groupedBuildFailures = new ArrayListValuedHashMap<>();
        String failuresCountFormat = "%0".concat(String.valueOf(String.valueOf(buildNodesTestFailures.size()).length())).concat("d");
        System.out.println("\n#TestUrl\t#Failure\t#PreviousFailure");
        int differencesCount = 0;
        for (Map.Entry<String, TestFailure> buildTestFailure : buildNodesTestFailuresArray) {
            String currentKey = buildTestFailure.getKey();
            if (!buildNodesTestFailures2.keySet().contains(currentKey)) {
                continue;
            }
            List<TestFailure> referenceTestFailures = (List<TestFailure>) buildNodesTestFailures2.get(currentKey);
            boolean failureFound = false;
            for (TestFailure testFailure: referenceTestFailures) {
                if (failuresAreEqual(buildTestFailure.getValue().failureToCompare, testFailure.failureToCompare, toolArgs.diffThreshold)) {
                    failureFound = true;
                    break;
                }
            }
            if (!failureFound) {
                differencesCount++;
                TestFailure currentValue = buildTestFailure.getValue();
                String referenceTestFailure = referenceTestFailures.size() > 0 ? referenceTestFailures.get(0).failureToDisplay : "N/A";
                System.out.println(currentValue.testUrl.concat("\t").concat(currentValue.failureToDisplay).concat("\t").concat(referenceTestFailure));
            }
        }
        System.out.println("-> Found ".concat(String.valueOf(differencesCount)).concat(" new test failures."));
    }

    public static void main(String[] args) throws IOException, CloneNotSupportedException {
        // ======== GET AND PARSE THE ARGUMENTS VALUES ========
        ToolArgs toolArgs = new ToolArgs();
        toolArgs.jobUrl = System.getProperty("jobUrl");
        if (isEmpty(toolArgs.jobUrl)) {
            throw new IllegalArgumentException("-DjobUrl parameter cannot be empty. Please, provide a valid URL!");
        }
        System.out.println("Parameter jobUrl=" + toolArgs.jobUrl);
        toolArgs.newUrlPrefix = System.getProperty("newUrlPrefix");
        System.out.println("Parameter newUrlPrefix=" + toolArgs.newUrlPrefix);
        toolArgs.newUrlPrefix = isEmpty(toolArgs.newUrlPrefix) ? toolArgs.jobUrl : toolArgs.newUrlPrefix;
        toolArgs.jobUrl2 = isEmpty(System.getProperty("jobUrl2")) ? toolArgs.jobUrl : System.getProperty("jobUrl2");
        System.out.println("Parameter jobUrl2=" + toolArgs.jobUrl2);
        toolArgs.newUrlPrefix2 = System.getProperty("newUrlPrefix2");
        toolArgs.newUrlPrefix2 = isEmpty(toolArgs.newUrlPrefix2) ? toolArgs.jobUrl2 : toolArgs.newUrlPrefix2;
        System.out.println("Parameter newUrlPrefix2=" + toolArgs.newUrlPrefix2);
        toolArgs.searchInJUnitReports = isEmpty(System.getProperty("searchInJUnitReports")) ? false : Boolean.valueOf(System.getProperty("searchInJUnitReports"));
        System.out.println("Parameter searchInJUnitReports=" + toolArgs.searchInJUnitReports);
        toolArgs.threadPoolSizeString = System.getProperty("threadPoolSize");
        toolArgs.threadPoolSize = isEmpty(System.getProperty("threadPoolSize")) ? 0 : Integer.parseInt(toolArgs.threadPoolSizeString);
        System.out.println("Parameter threadPoolSize=" + toolArgs.threadPoolSize);
        toolArgs.builds = parseBuilds(System.getProperty("builds"));
        // in case there is no build number specified, search in the last job build artifacts
        toolArgs.lastBuildsCount = toolArgs.builds.size() == 0 ? 1 : 0;
        toolArgs.lastBuildsCount = isEmpty(System.getProperty("lastBuildsCount")) ? toolArgs.lastBuildsCount : Integer.parseInt(System.getProperty("lastBuildsCount"));
        System.out.println("Parameter lastBuildsCount=" + toolArgs.lastBuildsCount);
        toolArgs.artifactsFilters = System.getProperty("artifactsFilters") == null ? "" : System.getProperty("artifactsFilters");
        System.out.println("Parameter artifactsFilters=" + toolArgs.artifactsFilters);
        toolArgs.searchedText = System.getProperty("searchedText") == null ? "" : System.getProperty("searchedText");
        System.out.println("Parameter searchedText=" + toolArgs.searchedText);
        toolArgs.groupTestsFailures = isEmpty(System.getProperty("groupTestsFailures")) ? false : Boolean.valueOf(System.getProperty("groupTestsFailures"));
        System.out.println("Parameter groupTestsFailures=" + toolArgs.groupTestsFailures);
        // the maximum difference threshold as a percentage of difference distance between 2 failures and the maximum possible distance for the shorter failure
        toolArgs.diffThreshold = isEmpty(System.getProperty("diffThreshold")) ? 10 : Double.valueOf(System.getProperty("diffThreshold"));
        System.out.println("Parameter diffThreshold=" + toolArgs.diffThreshold);
        toolArgs.backupJob = isEmpty(System.getProperty("backupJob")) ? false : Boolean.valueOf(System.getProperty("backupJob"));
        System.out.println("Parameter backupJob=" + toolArgs.backupJob);
        toolArgs.useBackup = isEmpty(System.getProperty("useBackup")) ? false : Boolean.valueOf(System.getProperty("useBackup"));
        System.out.println("Parameter useBackup=" + toolArgs.useBackup);
        toolArgs.removeBackup = isEmpty(System.getProperty("removeBackup")) ? false : Boolean.valueOf(System.getProperty("removeBackup"));
        System.out.println("Parameter removeBackup=" + toolArgs.removeBackup);
        toolArgs.backupPath = System.getProperty("backupPath") == null ? "" : System.getProperty("backupPath");
        System.out.println("Parameter backupPath=" + toolArgs.backupPath);
        toolArgs.referenceBuild = isEmpty(System.getProperty("referenceBuild")) ? null : Integer.valueOf(System.getProperty("referenceBuild"));
        System.out.println("Parameter referenceBuild=" + toolArgs.referenceBuild);
        toolArgs.showTestsDifferences = isEmpty(System.getProperty("showTestsDifferences")) ? false : Boolean.valueOf(System.getProperty("showTestsDifferences"));
        System.out.println("Parameter showTestsDifferences=" + toolArgs.showTestsDifferences);
        ToolArgs toolArgs2 = (ToolArgs) toolArgs.clone();
        toolArgs2.builds = toolArgs.referenceBuild == null ? new HashSet<>() : (Set<Integer>) Arrays.asList(toolArgs2.referenceBuild);
        toolArgs2.lastBuildsCount = 0;

        // ======== START PROCESSING THE JOB NODES IN PARALLEL ========
        ExecutorService executorService;
        if (toolArgs.threadPoolSize <= 0) {
            executorService = Executors.newWorkStealingPool();
        } else {
            executorService = Executors.newFixedThreadPool(toolArgs.threadPoolSize);
        }
        CompletionService<JenkinsNodeArtifactsFilter> completionService = new ExecutorCompletionService<>(
                executorService);
        Integer processCount = submitBuildNodes(completionService, toolArgs);
        // submit also the build nodes for jobUrl2, with build @referenceBuild
        processCount += submitBuildNodes(completionService, toolArgs2);

        MultiValuedMap<String, String> buildNodesArtifacts = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesFailures = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesTestFailures = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesTestFailures2 = new ArrayListValuedHashMap<>();
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
                    if (toolArgs.showTestsDifferences) {
                        for (Map.Entry<String, TestFailure> entry : completedProcess.testsFailures.entries()) {
                            if (completedProcess.toolArgs == toolArgs) {
                                buildNodesTestFailures.putAll(entry.getValue().testName, entry.getValue());
                            } else {
                                buildNodesTestFailures2.putAll(entry.getValue().testName, entry.getValue());
                            }
                        }
                    } else {
                        for (Map.Entry<String, TestFailure> entry : completedProcess.testsFailures.entries()) {
                            buildNodesFailures.putAll(String.valueOf(completedProcess.buildNumber).concat(KEYS_SEPARATOR).concat(entry.getKey()).concat(entry.getValue().failureToDisplay), entry.getValue());
                        }
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
        // remove "#Unfinished" from build directories names
        for (Integer buildNumber : toolArgs.builds) {
            File unfinishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile.getAbsolutePath().concat(File.separator).concat(String.valueOf(buildNumber)).concat(KEYS_SEPARATOR).concat("Unfinished"));
            File finishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile.getAbsolutePath().concat(File.separator).concat(String.valueOf(buildNumber)));
            if (toolArgs.backupJob) {
                if (finishedBackupBuildDirFile.exists()) {
                    FileUtils.deleteQuietly(finishedBackupBuildDirFile);
                }
                if (!unfinishedBackupBuildDirFile.renameTo(finishedBackupBuildDirFile)) {
                    throw new IllegalStateException("Couldn't rename " + unfinishedBackupBuildDirFile + " directory to " + finishedBackupBuildDirFile);
                }
            }
        }

        // ======== PRINT THE NODES/TESTS MATCHING THE SEARCHED TEXT ========
        printTheNodesOrTestsMatchingSearchedText(toolArgs, buildNodesArtifacts);

        if (toolArgs.groupTestsFailures) {
            // ======== PRINT THE COMMON FAILURES ========
            printTheCommonFailures(toolArgs, buildNodesFailures);
        }

        if (toolArgs.showTestsDifferences) {
            // ======== PRINT THE TESTS FAILURES DIFFERENCE BETWEEN 2 BUILdS ========
            printTheTestFailuresDifference(toolArgs, buildNodesTestFailures, buildNodesTestFailures2);
        }
    }
}