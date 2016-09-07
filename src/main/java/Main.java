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
    private static FailuresMatchResult matchTestCaseFailures(NodeList failureNodes, String testUrl, String buildNumber, String nodeUrl, ToolArgs toolArgs) {
        List<String> matchedFailedTests = new ArrayList<>();
        ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
        for (int failureNodeIndex = 0; failureNodeIndex < failureNodes.getLength(); failureNodeIndex++) {
            Element failureElement = (Element) failureNodes.item(failureNodeIndex);
            String message = failureElement.getAttribute("message");
            if (toolArgs.searchInJUnitReports && Main.findSearchedTextInContent(toolArgs.searchedText, message)) {
                matchedFailedTests.add(testUrl);
            }
            if (toolArgs.groupTestsFailures) {
                String stacktrace = failureElement.getTextContent();
                String failureToCompare = stacktrace.concat(": ").concat(message.split("\\n")[0]);
                testsFailures.put(testUrl, new TestFailure(buildNumber, nodeUrl, buildTestReportLink(nodeUrl, testUrl), failureToCompare, failureToCompare.length() >= 200 ? failureToCompare.substring(0, 200) + " ..." : failureToCompare));
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
                String testUrl = testCaseElement.getAttribute("classname").replace(".", "/").concat("/").concat(testCaseElement.getAttribute("name").replaceAll("[.\\\\()\\[\\]/-]", "_"));
                Integer testCount = testsCount.get(testUrl);
                testCount = testCount == null ? 0 : testCount;
                testsCount.put(testUrl, ++testCount);
                testUrl = testCount < 2 ? testUrl : testUrl.concat("_").concat(String.valueOf(testCount));
                NodeList failureNodes = testCaseElement.getElementsByTagName("failure");
                FailuresMatchResult failuresMatchResult = matchTestCaseFailures(failureNodes, testUrl, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(failuresMatchResult.matchedFailedTests);
                testsFailures.putAll(failuresMatchResult.testsFailures);
                NodeList errorNodes = testCaseElement.getElementsByTagName("error");
                FailuresMatchResult errorsMatchResult = matchTestCaseFailures(errorNodes, testUrl, buildNumber, nodeUrl, toolArgs);
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

    public static Set<Integer> updatedBuildsAndGetBackupBuilds(Set<Integer> builds, List<Integer> lastNBuilds, Integer lastBuildsCount, String jobResponse, File backupJobDirFile, Boolean backupJob, Boolean useBackup) {
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
            List<Integer> allAvailableBackupBuildsList = new ArrayList<>();
            allAvailableBackupBuildsList.addAll(allAvailableBackupBuilds);
            allAvailableBackupBuildsList.sort(null);
            if (allAvailableBackupBuilds.size() > 0) {
                List<Integer> otherBuildsFromBackup = allAvailableBackupBuildsList.subList(allAvailableBackupBuildsList.size() - 1, allAvailableBackupBuildsList.size() - Math.min(allAvailableBackupBuildsList.size(), lastBuildsCount - lastNBuilds.size()));
                backupBuilds.addAll(otherBuildsFromBackup);
            }
        }
        builds.addAll(backupBuilds);
        return backupBuilds;
    }

    public static void main(String[] args) throws IOException {
        // ======== GET AND PARSE THE ARGUMENTS VALUES ========
        ToolArgs toolArgs = new ToolArgs();
        toolArgs.jobUrl = System.getProperty("jobUrl");
        if (StringUtils.isEmpty(toolArgs.jobUrl)) {
            throw new IllegalArgumentException("-DjobUrl parameter cannot be empty. Please, provide a valid URL!");
        }
        System.out.println("Parameter jobUrl=" + toolArgs.jobUrl);
        toolArgs.newUrlPrefix = System.getProperty("newUrlPrefix");
        System.out.println("Parameter newUrlPrefix=" + toolArgs.newUrlPrefix);
        toolArgs.newUrlPrefix = StringUtils.isEmpty(toolArgs.newUrlPrefix) ? toolArgs.jobUrl : toolArgs.newUrlPrefix;
        toolArgs.searchInJUnitReports = StringUtils.isEmpty(System.getProperty("searchInJUnitReports")) ? false : Boolean.valueOf(System.getProperty("searchInJUnitReports"));
        System.out.println("Parameter searchInJUnitReports=" + toolArgs.searchInJUnitReports);
        toolArgs.threadPoolSizeString = System.getProperty("threadPoolSize");
        toolArgs.threadPoolSize = StringUtils.isEmpty(System.getProperty("threadPoolSize")) ? 0 : Integer.parseInt(toolArgs.threadPoolSizeString);
        System.out.println("Parameter threadPoolSize=" + toolArgs.threadPoolSize);
        toolArgs.builds = parseBuilds(System.getProperty("builds"));
        // in case there is no build number specified, search in the last job build artifacts
        toolArgs.lastBuildsCount = toolArgs.builds.size() == 0 ? 1 : 0;
        toolArgs.lastBuildsCount = StringUtils.isEmpty(System.getProperty("lastBuildsCount")) ? toolArgs.lastBuildsCount : Integer.parseInt(System.getProperty("lastBuildsCount"));
        System.out.println("Parameter lastBuildsCount=" + toolArgs.lastBuildsCount);
        toolArgs.artifactsFilters = System.getProperty("artifactsFilters") == null ? "" : System.getProperty("artifactsFilters");
        System.out.println("Parameter artifactsFilters=" + toolArgs.artifactsFilters);
        toolArgs.searchedText = System.getProperty("searchedText") == null ? "" : System.getProperty("searchedText");
        System.out.println("Parameter searchedText=" + toolArgs.searchedText);
        toolArgs.groupTestsFailures = StringUtils.isEmpty(System.getProperty("groupTestsFailures")) ? false : Boolean.valueOf(System.getProperty("groupTestsFailures"));
        System.out.println("Parameter groupTestsFailures=" + toolArgs.groupTestsFailures);
        // the maximum difference threshold as a percentage of difference distance between 2 failures and the maximum possible distance for the shorter failure
        toolArgs.diffThreshold = StringUtils.isEmpty(System.getProperty("diffThreshold")) ? 10 : Double.valueOf(System.getProperty("diffThreshold"));
        System.out.println("Parameter diffThreshold=" + toolArgs.diffThreshold);
        toolArgs.backupJob = StringUtils.isEmpty(System.getProperty("backupJob")) ? false : Boolean.valueOf(System.getProperty("backupJob"));
        System.out.println("Parameter backupJob=" + toolArgs.backupJob);
        toolArgs.useBackup = StringUtils.isEmpty(System.getProperty("useBackup")) ? false : Boolean.valueOf(System.getProperty("useBackup"));
        System.out.println("Parameter useBackup=" + toolArgs.useBackup);
        toolArgs.removeBackup = StringUtils.isEmpty(System.getProperty("removeBackup")) ? false : Boolean.valueOf(System.getProperty("removeBackup"));
        System.out.println("Parameter removeBackup=" + toolArgs.removeBackup);
        toolArgs.backupPath = System.getProperty("backupPath") == null ? "" : System.getProperty("backupPath");
        System.out.println("Parameter backupPath=" + toolArgs.backupPath);

        final String lastNBuildNumbersJsonPath = "$.builds[:" + toolArgs.lastBuildsCount + "].number";
        final String buildNumbersJsonPath = "$.builds[*].number";

        // ======== START PROCESSING THE JOB NODES IN PARALLEL ========
        String apiJobUrl = toolArgs.jobUrl.replace(toolArgs.jobUrl, toolArgs.newUrlPrefix) + "/api/json";
        String jobResponse = getUrlResponse(apiJobUrl);
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
            throw new IllegalArgumentException("Exception when parsing the job api response for URL " + apiJobUrl + " : " + jobResponse, e);
        }
        toolArgs.builds.addAll(lastNBuilds);
        Set<Integer> backupBuilds = updatedBuildsAndGetBackupBuilds(toolArgs.builds, lastNBuilds, toolArgs.lastBuildsCount, jobResponse, toolArgs.backupJobDirFile, toolArgs.backupJob, toolArgs.useBackup);
        System.out.println("Parameter builds=" + toolArgs.builds);

        System.out.println("\nPrint the nodes matching the searched text \"" + toolArgs.searchedText + "\" in artifacts: ");
        ExecutorService executorService;
        if (toolArgs.threadPoolSize <= 0) {
            executorService = Executors.newWorkStealingPool();
        } else {
            executorService = Executors.newFixedThreadPool(toolArgs.threadPoolSize);
        }
        CompletionService<JenkinsNodeArtifactsFilter> completionService = new ExecutorCompletionService<>(
                executorService);
        Integer processCount = 0;
        for (Integer buildNumber : toolArgs.builds) {
            List<String> nodesUrls;
            File finishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile + File.separator + buildNumber);
            File unfinishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile + File.separator + buildNumber + KEYS_SEPARATOR + "Unfinished");
            File backupBuildDirFile = finishedBackupBuildDirFile;
            if (toolArgs.backupJob) {
                backupBuildDirFile = unfinishedBackupBuildDirFile;
            }
            Boolean useBackup = backupBuilds.contains(buildNumber);
            if (useBackup) {
                nodesUrls = Arrays.asList(new FileHelper().getDirFilesList(backupBuildDirFile.getAbsolutePath(), "", false));
            } else {
                String buildUrl = ((List<String>) JsonPath.read(jobResponse, String.format(buildsNumberUrlJsonPath, buildNumber))).get(0);
                String buildApiResp = getUrlResponse(buildUrl.replace(toolArgs.jobUrl, toolArgs.newUrlPrefix) + "/api/json");
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
        MultiValuedMap<String, String> buildNodesArtifacts = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesFailures = new ArrayListValuedHashMap<>();
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
        // remove "#Unfinished" from build directories names
        for (Integer buildNumber : toolArgs.builds) {
            File unfinishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile + File.separator + buildNumber + KEYS_SEPARATOR + "Unfinished");
            File finishedBackupBuildDirFile = new File(toolArgs.backupJobDirFile + File.separator + buildNumber);
            if (toolArgs.backupJob) {
                if (!unfinishedBackupBuildDirFile.renameTo(finishedBackupBuildDirFile)) {
                    throw new IllegalStateException("Couldn't rename " + unfinishedBackupBuildDirFile + " directory to " + finishedBackupBuildDirFile);
                }
            }
        }

        // ======== PRINT THE NODES/TESTS MATCHING THE SEARCHED TEXT ========
        // sort the results in ascending buildNumber_nodeUrl order
        Map.Entry<String, String>[] buildNodesArtifactsArray = buildNodesArtifacts.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesArtifactsArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastBuild = "";
        String lastNode = "";

        System.out.println("-> Found the searched text in ".concat(String.valueOf(buildNodesArtifacts.keySet().size())).concat(" build nodes."));
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

        if (!toolArgs.groupTestsFailures) {
            return;
        }

        // ======== PRINT THE COMMON FAILURES ========
        System.out.println("\nGroup common failures from tests reports: ");
        MultiValuedMap<String, TestFailure> groupedBuildNodesFailures = new ArrayListValuedHashMap<>();
        for (Map.Entry<String, TestFailure> buildNodeFailure : buildNodesFailures.entries()) {
            String failureKey = buildNodeFailure.getKey();
            String groupedBuildNodeFailureKey = null;
            for (Map.Entry<String, TestFailure> groupedBuildNodeFailure : groupedBuildNodesFailures.entries()) {
                Integer maxDistance = Math.min(buildNodeFailure.getValue().failureToCompare.length(), groupedBuildNodeFailure.getValue().failureToCompare.length());
                if (maxDistance == 0) {
                    groupedBuildNodeFailureKey = groupedBuildNodeFailure.getKey();
                    break;
                }
                int distanceThreshold = toolArgs.diffThreshold < 0 ? 0 : (int) Math.ceil((maxDistance * toolArgs.diffThreshold) / 100);
                double currentThreshold = (double) StringUtils.getLevenshteinDistance(buildNodeFailure.getValue().failureToCompare, groupedBuildNodeFailure.getValue().failureToCompare, distanceThreshold);
                currentThreshold = currentThreshold == -1 ? maxDistance : currentThreshold;
                currentThreshold = currentThreshold * 100 / maxDistance;
                if (currentThreshold <= toolArgs.diffThreshold) {
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
        lastBuild = "";
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
}