import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.map.HashedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.apache.commons.lang3.math.NumberUtils.min;

/**
 * Created by Teo on 7/30/2016.
 * A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.
 * Command line e.g:
 * java -DjobUrl="$jobUrl" -DnewUrlPrefix="$newUrlPrefix=" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".*outputFile.*" -DsearchedText=".*textToFind.*" -cp searchinjenkinslogs.jar Main
 */
public class Main {

    private static final String runsNumberUrlJsonPath = "$.runs[?(@.number == %d)].url";
    private static final String KEYS_SEPARATOR = "#";
    static final String artifactsRelativePathJsonPath = "$.artifacts[*].relativePath";
    static final String buildsNumberJsonPath = "$.builds[*].number";
    static final String timestampJsonPath = "$.timestamp";
    static final String buildsNumberUrlJsonPath = "$.builds[?(@.number == %d)].url";
    static final String buildParamsJsonPath = "$.actions[*].parameters[*]";
    private static Map<String, String> testsIssuesMap = new HashMap<>();

    static HttpResponse getUrlHttpResponse(HttpGet httpGet, String username, String password) throws IOException {
        URI uri = httpGet.getURI();
        if (username != null) {
            // authenticate
            HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(new AuthScope(uri.getHost(), uri.getPort()), new UsernamePasswordCredentials(username, password));
            // Create AuthCache instance
            AuthCache authCache = new BasicAuthCache();
            // Generate BASIC scheme object and add it to the local auth cache
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(host, basicAuth);
            CloseableHttpClient httpClient = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();
            // Add AuthCache to the execution context
            HttpClientContext localContext = HttpClientContext.create();
            localContext.setAuthCache(authCache);
            HttpResponse response = httpClient.execute(host, httpGet, localContext);
            return response;
        }

        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(httpGet);
        return response;
    }

    static String getUrlResponse(HttpGet httpGet, String username, String password) throws IOException {
        if (username == null) {
            System.out.println("Get response for url: " + httpGet.getURI().toURL().toString());
            return IOUtils.toString(new InputStreamReader(httpGet.getURI().toURL().openStream()));
        }
        HttpResponse response = getUrlHttpResponse( httpGet, username, password);
        return EntityUtils.toString(response.getEntity());
    }

    static String getUrlResponse(String urlString, String username, String password) throws IOException {
        return getUrlResponse(new HttpGet(URI.create(urlString)), username, password);
    }

    /**
     *  Replace the prefix @prefixUrl from url with @newPrefixUrl
     */
    static String replaceUrlPrefix(String url, String prefixUrl, String newPrefixUrl) throws MalformedURLException {
        URL urlObj = new URL(url);
        URL prefixUrlObj = new URL(prefixUrl);

        if (urlObj.getPort() != prefixUrlObj.getPort()) {
            url = url.replace(":".concat(String.valueOf(urlObj.getPort())).concat("/"), ":".concat(String.valueOf(prefixUrlObj.getPort())).concat("/"));
        }

        return url.replace(prefixUrl, newPrefixUrl);
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
     * @return true if @buildParams matches all parameter values from @buildParamsFilter map
     */
    private static boolean matchesBuildParams(List<Map<String, String>> buildParams, Map<String, String> buildParamsFilter) {
        boolean matches = true;
        for (Map.Entry<String, String> buildParamFilter: buildParamsFilter.entrySet()) {
            String key = buildParamFilter.getKey();
            String value = buildParamFilter.getValue();
            boolean found = false;
            for (Map<String, String> buildParam: buildParams) {
                if (buildParam.get("name").equals(key) && value.equals(buildParam.get("value"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                matches = false;
                break;
            }
        }
        return matches;
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
        StringBuilder newXml = new StringBuilder("");
        boolean replace = false;
        Integer maxNewLinesToReplace = 10;
        Integer replacedNewLinesCount = 0;
        while (!xml.isEmpty()) {
            int newLinePosition = xml.indexOf(failureTag);
            if (newLinePosition == -1) {
                newXml.append(xml);
                break;
            }
            StringBuilder newLine = new StringBuilder(xml.substring(0, newLinePosition));
            newXml.append(newLine);
            xml = xml.substring(newLinePosition);
            boolean finishedReplace = false;
            while (!finishedReplace) {
                newLinePosition = xml.indexOf("\n");
                if (newLinePosition == -1) {
                    newXml.append(xml);
                    return newXml.toString();
                }
                newLine = new StringBuilder(xml.substring(0, newLinePosition));
                if (newLine.indexOf(failureEndTag) == -1 && replacedNewLinesCount < maxNewLinesToReplace) {
                    if (newLine.indexOf(failureTag) != -1) {
                        newLine.append("&#10;");
                        replace = true;
                        replacedNewLinesCount = 1;
                    } else if (replace) {
                        newLine.append("&#10;");
                        replacedNewLinesCount++;
                    } else {
                        newLine.append("\n");
                    }
                } else {
                    replace = false;
                    replacedNewLinesCount = 0;
                    newLine.append("\n");
                    // go to the next failure if we replaced the maximum new lines
                    finishedReplace = true;
                }
                newXml.append(newLine);
                xml = xml.substring(newLinePosition + 1);
            }
        }
        return newXml.toString();
    }

    private static String encodeNewLineCharInFailureElements(String xml) {
        String newXml = encodeNewLineCharInFailureElement(xml, "<failure message=", "</failure>");
        newXml = encodeNewLineCharInFailureElement(newXml, "<error message=", "</error>");
        return newXml;
    }

    private static String buildTestReportLink(String node, String test) {
        return node.concat("testReport/junit/".concat(test).replace("//", "/(empty)/"));
    }

    private static String buildArtifactLink(String node, String artifactPath) {
        return node.concat("artifact/").concat(artifactPath);
    }

    private static String getFirstXLines(String text, int linesCount) {
        String[] tokens = text.split("\\n");
        String firstXLines = "";
        for (int i = 0; i< Math.min(linesCount, tokens.length); i++) {
            firstXLines = firstXLines.concat(tokens[i]).concat("\n");
        }
        return firstXLines;
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
    private static FailuresMatchResult matchTestCaseFailures(NodeList failureNodes, String testUrl, String testName, String shortTestName, String buildNumber, String nodeUrl, ToolArgs toolArgs) {
        List<String> matchedFailedTests = new ArrayList<>();
        ArrayListValuedHashMap<String, TestFailure> testsFailures = new ArrayListValuedHashMap<>();
        if (failureNodes.getLength() > 0 && toolArgs.stableReport != null) {
            if (toolArgs.stableReport && !toolArgs.stabilityListParser.getStableTests().contains(testName) && toolArgs.stabilityListParser.getUnstableTests().contains(testName)
                || !toolArgs.stableReport && !toolArgs.stabilityListParser.getUnstableTests().contains(testName) && toolArgs.stabilityListParser.getStableTests().contains(testName)) {
                return new FailuresMatchResult(matchedFailedTests, testsFailures, null);
            }
        }
        for (int failureNodeIndex = 0; failureNodeIndex < failureNodes.getLength(); failureNodeIndex++) {
            Element failureElement = (Element) failureNodes.item(failureNodeIndex);
            String message = failureElement.getAttribute("message");
            if (toolArgs.searchInJUnitReports && Main.findSearchedTextInContent(toolArgs.searchedText, message)) {
                matchedFailedTests.add(testUrl);
            }
            if (toolArgs.groupTestsFailures || toolArgs.showTestsDifferences) {
                String stacktrace = failureElement.getTextContent();
                String[] stacktraceTokens = stacktrace.replace("&#10;", "\n").split("StackTrace:\n");
                if (stacktraceTokens.length > 1) {
                    stacktrace = stacktraceTokens[stacktraceTokens.length - 1].trim();
                } else {
                    stacktraceTokens = stacktrace.replace("&#10;", "\n").split("Stack Trace:\n");
                    if (stacktraceTokens.length > 1) {
                        stacktrace = stacktraceTokens[stacktraceTokens.length - 1].trim();
                    }
                }
                stacktrace = getFirstXLines(stacktrace, 2);
                stacktrace = stacktrace.trim().isEmpty() ? stacktrace : stacktrace.concat(": ");
                String failureToCompare = stacktrace.concat(getFirstXLines(message, 1));
                testsFailures.put(testUrl, new TestFailure(buildNumber, nodeUrl, buildTestReportLink(nodeUrl, testUrl), testName, shortTestName, failureToCompare, failureToCompare.length() >= 250 ? failureToCompare.substring(0, 250) + " ..." : failureToCompare));
            }
        }
        return new FailuresMatchResult(matchedFailedTests, testsFailures, null);
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
        ArrayListValuedHashMap<String, TestStatus> testsStatus = new ArrayListValuedHashMap<>();
        // if the JUnit report is empty we cannot parse it
        if (jUnitReportXml.isEmpty()) {
            return new FailuresMatchResult(matchedFailedTests, testsFailures, testsStatus);
        }
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        jUnitReportXml = encodeNewLineCharInFailureElements(jUnitReportXml);
        Document doc = dBuilder.parse(new ByteArrayInputStream(jUnitReportXml.getBytes()));
        doc.getDocumentElement().normalize();
        NodeList testCasesList = doc.getElementsByTagName("testcase");
        for (int testCaseIndex = 0; testCaseIndex < testCasesList.getLength(); testCaseIndex++) {
            Node testCaseNode = testCasesList.item(testCaseIndex);
            if (testCaseNode.getNodeType() == Node.ELEMENT_NODE) {
                Element testCaseElement = (Element) testCaseNode;
                String shortTestName = testCaseElement.getAttribute("name");
                String testName = testCaseElement.getAttribute("classname").concat(".").concat(shortTestName);
                String[] classNameTokens  = testCaseElement.getAttribute("classname").split("\\.");
                // the package name from url needs to remain with ".", the class name is delimited using "/"
                String packageName = "";
                for (int i = 0; i < classNameTokens.length - 1; i++) {
                    packageName += classNameTokens[i].concat(".");
                }
                String testCaseClassname = testCaseElement.getAttribute("classname");
                packageName = testCaseClassname.endsWith(".") ? packageName.concat(classNameTokens[classNameTokens.length - 1]).concat(".") : packageName;
                packageName = packageName.length() > 0 ? new StringBuilder(packageName).deleteCharAt(packageName.length() - 1).toString() : packageName;
                packageName = classNameTokens.length < 2 ? "(root)" : packageName;
                String className = testCaseClassname.endsWith(".") ? "" : classNameTokens[classNameTokens.length - 1];
                String testUrl = packageName.replaceAll("[:#]", "_").concat("/").concat(className.replaceAll("[:/<>]", "_")).concat("/").concat(shortTestName.replaceAll("[\\{\\}.:+\\r\\n \\\\()\\[\\]/,\"'&%*^<>@#=-]", "_"));
                Integer testCount = testsCount.get(testUrl);
                testCount = testCount == null ? 0 : testCount;
                testsCount.put(testUrl, ++testCount);
                testUrl = testCount < 2 ? testUrl : testUrl.concat("_").concat(String.valueOf(testCount));
                NodeList failureNodes = testCaseElement.getElementsByTagName("failure");
                FailuresMatchResult failuresMatchResult = matchTestCaseFailures(failureNodes, testUrl, testName, shortTestName, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(failuresMatchResult.matchedFailedTests);
                testsFailures.putAll(failuresMatchResult.testsFailures);
                NodeList errorNodes = testCaseElement.getElementsByTagName("error");
                FailuresMatchResult errorsMatchResult = matchTestCaseFailures(errorNodes, testUrl, testName, shortTestName, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(errorsMatchResult.matchedFailedTests);
                NodeList skippedNodes = testCaseElement.getElementsByTagName("skipped");
                FailuresMatchResult skippedMatchResult = matchTestCaseFailures(skippedNodes, testUrl, testName, shortTestName, buildNumber, nodeUrl, toolArgs);
                matchedFailedTests.addAll(skippedMatchResult.matchedFailedTests);
                testsFailures.putAll(skippedMatchResult.testsFailures);
                if (toolArgs.computeStabilityList) {
                    String stabilityTestName = testCaseElement.getAttribute("classname").concat("&").concat(shortTestName);
                    Boolean failedStatus = failureNodes.getLength() != 0 || errorNodes.getLength() != 0;
                    TestStatus testStatus = new TestStatus(Integer.parseInt(buildNumber), failedStatus);
                    testsStatus.put(stabilityTestName, testStatus);
                }
            }
        }
        return new FailuresMatchResult(matchedFailedTests, testsFailures, testsStatus);
    }

    static String encodeFile(String file) throws UnsupportedEncodingException {
        return URLEncoder.encode(file, Charset.defaultCharset().name());
    }

    static String decodeFile(String file) throws UnsupportedEncodingException {
        return URLDecoder.decode(file, Charset.defaultCharset().name());
    }

    private static Set<Integer> updatedBuildsAndGetBackupBuilds(Set<Integer> builds, List<Integer> lastNBuilds, Integer lastBuildsCount, String jobResponse, File backupJobDirFile, Boolean backupJob, Boolean useBackup, Integer backupRetention) {
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
                    return Integer.valueOf(tokens[0]);
                }
            }).toArray()).toArray(new Integer[0])));
            allAvailableBackupBuilds.removeIf(build -> {
                File unfinishedBackupBuildDirFile = new File(backupJobDirFile + File.separator + build + KEYS_SEPARATOR + "Unfinished");
                return unfinishedBackupBuildDirFile.exists();
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
            Set<Integer> allBuildsSet = new HashSet<>(allAvailableBuildsList);
            allBuildsSet.addAll(allAvailableBackupBuilds);
            List<Integer> allBuilds = new ArrayList<>(allBuildsSet);
            allBuilds.sort(null);
            // backup only the retention builds
            if (backupRetention >= 0 && allBuilds.size() > 0) {
                backupRetention = backupRetention > allBuilds.size() ? allBuilds.size() : backupRetention;
                List<Integer> buildsToRemove = new ArrayList<>();
                if (backupRetention < allBuilds.size()) {
                    buildsToRemove.addAll(allBuilds.subList(0, allBuilds.size() - backupRetention));
                }
                allBuilds.removeAll(buildsToRemove);
                for (Integer backupBuild : buildsToRemove) {
                    FileUtils.deleteQuietly(new File(backupJobDirFile + File.separator + backupBuild));
                    FileUtils.deleteQuietly(new File(backupJobDirFile + File.separator + backupBuild + KEYS_SEPARATOR + "Unfinished"));
                }
            }
            builds.addAll(allBuilds);
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

    private static void computeBuilds(ToolArgs toolArgs, Set<Integer> excludedBuilds, Set<Integer> backupBuilds, String jobResponse) throws IOException {
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
        if (toolArgs.buildsFromLastXHours > 0 || !toolArgs.buildParamsFilter.isEmpty()) {
            List<Integer> allAvailableBuildsList = JsonPath.read(jobResponse, buildsNumberJsonPath);
            final int oneHour = 1000*60*60;
            Set<Integer> validBuilds = new HashSet<>();
            Long lastBuildTimestamp = null; // the timestamp until when to parse builds
            for (Integer buildNumber : allAvailableBuildsList) {
                String buildUrl = ((List<String>) JsonPath.read(jobResponse, String.format(buildsNumberUrlJsonPath, buildNumber))).get(0);
                String buildApiResp;
                try {
                    buildApiResp = getUrlResponse(replaceUrlPrefix(buildUrl, toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json"), toolArgs.username, toolArgs.password);
                } catch (IOException e) {
                    System.err.println("Got exception when getting API response for job build URL ".concat(buildUrl).concat(": ").concat(e.toString()));
                    continue;
                }
                boolean isValidBuild = true;
                if (toolArgs.buildsFromLastXHours > 0) {
                    long timestamp = JsonPath.read(buildApiResp, timestampJsonPath);
                    if (buildNumber.equals(excludedBuilds.iterator().next())) {
                        lastBuildTimestamp = System.currentTimeMillis();
                        if (toolArgs.previousBuildsOnly && !excludedBuilds.isEmpty()) {
                            lastBuildTimestamp = timestamp;
                        }
                    }
                    isValidBuild = lastBuildTimestamp != null && (lastBuildTimestamp - timestamp >=0) && ((lastBuildTimestamp - timestamp) / oneHour <= toolArgs.buildsFromLastXHours);
                }
                if (!isValidBuild) {
                    continue;
                }
                if (!toolArgs.buildParamsFilter.isEmpty()) {
                    List<Map<String, String>> buildParams = JsonPath.read(buildApiResp, buildParamsJsonPath);
                    isValidBuild = matchesBuildParams(buildParams, toolArgs.buildParamsFilter);
                }
                if (isValidBuild) {
                    validBuilds.add(buildNumber);
                }
            }
            if (toolArgs.builds.size() > 0) {
                toolArgs.builds.retainAll(validBuilds);
            } else {
                toolArgs.builds.addAll(validBuilds);
            }
            // no need to compute extra valid builds from backup builds
            lastNBuilds = new ArrayList<>();
            toolArgs.lastBuildsCount = 0;
        }
        toolArgs.builds.removeAll(excludedBuilds);
        backupBuilds.addAll(updatedBuildsAndGetBackupBuilds(toolArgs.builds, lastNBuilds, toolArgs.lastBuildsCount, jobResponse, toolArgs.backupJobDirFile, toolArgs.backupJob, toolArgs.useBackup, toolArgs.backupRetention));
        List<Integer> sortedBuilds = new ArrayList<>(toolArgs.builds);
        sortedBuilds.sort(null);
        System.out.println("Parameter builds=" + sortedBuilds);
    }

    private static Integer submitBuildNodes(CompletionService<JenkinsNodeArtifactsFilter> completionService, ToolArgs toolArgs, Set<Integer> excludedBuilds) throws IOException {
        String apiJobUrl = replaceUrlPrefix(toolArgs.jobUrl, toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json");
        String jobResponse = getUrlResponse(apiJobUrl, toolArgs.username, toolArgs.password);
        Set<Integer> backupBuilds = new HashSet<>();
        computeBuilds(toolArgs, excludedBuilds, backupBuilds, jobResponse);
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
            String buildUrl = null;
            String buildApiResp = null;
            if (!useBackup) {
                buildUrl = ((List<String>) JsonPath.read(jobResponse, String.format(buildsNumberUrlJsonPath, buildNumber))).get(0);
                try {
                    buildApiResp = getUrlResponse(replaceUrlPrefix(buildUrl, toolArgs.jobUrl, toolArgs.newUrlPrefix).concat("/api/json"), toolArgs.username, toolArgs.password);
                } catch (IOException e) {
                    System.err.println("Got exception when getting API response for job build URL ".concat(buildUrl).concat(": ").concat(e.toString()));
                    continue;
                }
            }
            if (useBackup) {
                nodesUrls = Arrays.asList(new FileHelper().getDirFilesList(backupBuildDirFile.getAbsolutePath(), "", false));
            } else {
                Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS);
                nodesUrls = JsonPath.using(conf).parse(buildApiResp).read(String.format(runsNumberUrlJsonPath, buildNumber));
                if (nodesUrls.size() == 0) {
                    nodesUrls = Arrays.asList(buildUrl);
                }
            }
            if (!useBackup && (toolArgs.removeBackup || toolArgs.backupJob)) {
                FileUtils.deleteQuietly(finishedBackupBuildDirFile);
                FileUtils.deleteQuietly(unfinishedBackupBuildDirFile);
            }
            if (toolArgs.backupJob) {
                backupBuildDirFile.mkdir();
            }

            for (String nodeUrl : nodesUrls) {
                nodeUrl = replaceUrlPrefix(nodeUrl, toolArgs.newUrlPrefix, toolArgs.jobUrl);
                if (toolArgs.nodeUrlFilter.isEmpty() || nodeUrl.matches(toolArgs.nodeUrlFilter)) {
                    completionService.submit(new JenkinsNodeArtifactsFilter(toolArgs, String.valueOf(buildNumber), nodeUrl, useBackup, backupBuildDirFile));
                    processCount += 1;
                }
            };
        }
        return processCount;
    }

    private static void printTheNodesOrTestsMatchingSearchedText(ToolArgs toolArgs, MultiValuedMap<String, String> buildNodesArtifacts) throws MalformedURLException {
        toolArgs.htmlGenerator.addParagraph("Print the nodes matching the searched text \"" + StringEscapeUtils.escapeHtml4(toolArgs.searchedText) + "\" in artifacts for ".concat(toolArgs.jobUrl).concat(": "));
        System.out.println("\nPrint the nodes matching the searched text \"" + toolArgs.searchedText + "\" in artifacts: ");
        toolArgs.htmlGenerator.startTable();
        toolArgs.htmlGenerator.startRow().addColumnValue("Build", true).addColumnValue("Nodes", true).addColumnValue("Artifacts", true).endRow();
        System.out.println("-> Found the searched text in <b>".concat(String.valueOf(buildNodesArtifacts.keySet().size())).concat("</b> build nodes."));
        // sort the results in ascending buildNumber_nodeUrl order
        Map.Entry<String, String>[] buildNodesArtifactsArray = buildNodesArtifacts.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesArtifactsArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));
        String lastBuild = "";
        String lastNode = "";
        String artifactsColumnValue = "";
        for (Map.Entry<String, String> buildNodeArtifact : buildNodesArtifactsArray) {
            String[] buildNodeTokens = buildNodeArtifact.getKey().split(KEYS_SEPARATOR);
            String currentBuild = buildNodeTokens[0];
            String currentNode = buildNodeTokens[1];
            boolean isDifferentNode = !currentNode.equals(lastNode);
            if (isDifferentNode && !artifactsColumnValue.isEmpty()) {
                toolArgs.htmlGenerator.addColumnValue(artifactsColumnValue).endRow();
            }
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                toolArgs.htmlGenerator.startRow().addColumnValue("#".concat(currentBuild)).addColumnValue("").addColumnValue("").endRow();
                System.out.println("\nBuild: ".concat(currentBuild));
            }

            if (isDifferentNode) {
                lastNode = currentNode;
                toolArgs.htmlGenerator.startRow().addColumnValue("").addColumnValue(replaceUrlPrefix(currentNode, toolArgs.jobUrl, ""), currentNode);
                artifactsColumnValue = "";
                System.out.println("\tNode: ".concat(currentNode));
            }
            if (toolArgs.searchInJUnitReports) {
                artifactsColumnValue += new HtmlGenerator().addLink(buildNodeArtifact.getValue(), buildTestReportLink(currentNode, buildNodeArtifact.getValue())).addNewLine().getContent();
                System.out.println("\t\tFailed test report: ".concat(buildTestReportLink(currentNode, buildNodeArtifact.getValue())));
            } else {
                artifactsColumnValue += new HtmlGenerator().addLink(buildNodeArtifact.getValue(), buildArtifactLink(currentNode, buildNodeArtifact.getValue())).addNewLine().getContent();
                System.out.println("\t\tArtifact relative path: ".concat(buildNodeArtifact.getValue()));
            }
        }
        toolArgs.htmlGenerator.addColumnValue(artifactsColumnValue).endRow();
        toolArgs.htmlGenerator.startRow().addColumnValue("Nodes count: ".concat(String.valueOf(buildNodesArtifacts.keySet().size())), true).addColumnValue("").addColumnValue("").endRow().endTable().addNewLine();
    }

    private static Boolean failuresAreEqual(String failure1, String failure2, Double diffThreshold) {
        Integer maxDistance = Math.max(failure1.length(), failure2.length());
        if (maxDistance == 0) {
            return true;
        }
        int distanceThreshold = diffThreshold < 0 ? 0 : (int) Math.ceil((maxDistance * diffThreshold) / 100);
        double currentThreshold = (double) StringUtils.getLevenshteinDistance(failure1, failure2, distanceThreshold);
        currentThreshold = currentThreshold == -1 ? maxDistance : currentThreshold;
        currentThreshold = currentThreshold * 100 / maxDistance;
        return currentThreshold <= diffThreshold;
    }

    private static String getIssuesLinks(ToolArgs toolArgs, String shortTestName) {
        if (!testsIssuesMap.containsKey(shortTestName)) {
            Map<String, String> issuesMap = new HashedMap<>();
            for (Map.Entry<String, String> issueDescription : toolArgs.issueDescriptionMap.entrySet()) {
                if (issueDescription.getValue().contains(shortTestName)) {
                    issuesMap.put(issueDescription.getKey(), toolArgs.jiraUrl.concat("/browse/").concat(issueDescription.getKey()));
                }
            }
            String issuesLinks = "";
            for (Map.Entry<String, String> entry : issuesMap.entrySet()) {
                issuesLinks = issuesLinks.concat("<a href=\"").concat(entry.getValue()).concat("\">").concat(entry.getKey()).concat("</a>&#44;&nbsp;");
            }
            if (!issuesLinks.isEmpty()) {
                issuesLinks = issuesLinks.substring(0, issuesLinks.lastIndexOf("&#44;&nbsp;"));
            }
            testsIssuesMap.put(shortTestName, issuesLinks);
        }
        return testsIssuesMap.get(shortTestName);
    }

    private static void printTheCommonFailures(ToolArgs toolArgs, MultiValuedMap<String, TestFailure> buildNodesFailures) {
        toolArgs.htmlGenerator.addParagraph("Group common failures from tests reports for ".concat(toolArgs.jobUrl).concat(", ").concat(toolArgs.jobUrl2.equals(toolArgs.jobUrl) ? "" : toolArgs.jobUrl2).concat(": "));
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
        toolArgs.htmlGenerator.addParagraph("-> Found <b>".concat(String.valueOf(groupedBuildFailures.keySet().size())).concat("</b> common failures."));
        System.out.println("-> Found ".concat(String.valueOf(groupedBuildFailures.keySet().size())).concat(" common failures."));
        toolArgs.htmlGenerator.startTable().startRow().addColumnValue("Failure", true).addColumnValue("Frequency", true).addColumnValue("Test Name/Link", true).addColumnValue("Build", true);
        toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue("Known Issues", true).endRow() : toolArgs.htmlGenerator.endRow();

        // sort the results by count#failure#firstTestUrl#buildNumber ascending order
        Map.Entry<String, TestFailure>[] groupedBuildFailuresArray = groupedBuildFailures.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(groupedBuildFailuresArray, (o1, o2) -> o2.getKey().compareTo(o1.getKey()));
        String lastKey = "";
        String lastBuild = "";
        for (Map.Entry<String, TestFailure> buildFailure : groupedBuildFailuresArray) {
            String currentKey = buildFailure.getKey();
            String currentBuild = buildFailure.getValue().buildNumber;
            if (!currentKey.equals(lastKey)) {
                lastKey = currentKey;
                lastBuild = "";
                toolArgs.htmlGenerator.startRow().addColumnValue(StringEscapeUtils.escapeHtml4(buildFailure.getValue().failureToDisplay)).addColumnValue(String.valueOf(groupedBuildFailures.get(currentKey).size())).addColumnValue("").addColumnValue("");
                toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue("").endRow() : toolArgs.htmlGenerator.endRow();
                System.out.println("\n\tFailure: ".concat(buildFailure.getValue().failureToDisplay));
                System.out.println("\t-> Found ".concat(String.valueOf(groupedBuildFailures.get(currentKey).size())).concat(" failures."));
            }
            if (!currentBuild.equals(lastBuild)) {
                lastBuild = currentBuild;
                System.out.println("\t\tBuild: " + currentBuild);
            }
            String buildNumber = "#".concat(currentBuild);
            buildNumber = toolArgs.builds.contains(Integer.valueOf(currentBuild)) && buildFailure.getValue().testUrl.startsWith(toolArgs.jobUrl) ? "<b>".concat(buildNumber).concat("</b>") : buildNumber;
            toolArgs.htmlGenerator.startRow().addColumnValue("").addColumnValue("").addColumnValue(buildFailure.getValue().testName, buildFailure.getValue().testUrl).addColumnValue(buildNumber);
            toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue(getIssuesLinks(toolArgs, buildFailure.getValue().shortTestName)).endRow() : toolArgs.htmlGenerator.endRow();
            System.out.println("\t\t\tFailed test report: ".concat(buildFailure.getValue().testUrl));
        }
        toolArgs.htmlGenerator.endTable().addNewLine();
    }

    private static void printTheTestFailuresDifference(ToolArgs toolArgs, MultiValuedMap<String, TestFailure> buildNodesTestFailures, MultiValuedMap<String, TestFailure> buildNodesTestFailures2) {
        toolArgs.htmlGenerator.addParagraph("Comparison results for job ".concat(toolArgs.jobUrl).concat(", builds ").concat(toolArgs.builds.toString()).concat(", compared to ").concat(toolArgs.jobUrl2).concat(", builds ").concat(toolArgs.referenceBuilds.toString()).concat(":"));
        System.out.println("\nComparison results for job ".concat(toolArgs.jobUrl).concat(", builds ").concat(toolArgs.builds.toString()).concat(", compared to ").concat(toolArgs.jobUrl2).concat(", builds ").concat(toolArgs.referenceBuilds.toString()).concat(":"));
        toolArgs.htmlGenerator.addParagraph("-> Found <b>".concat(String.valueOf(buildNodesTestFailures.keySet().size())).concat("</b> instead of <b>").concat(String.valueOf(buildNodesTestFailures2.keySet().size())).concat("</b> failed tests."));
        System.out.println("-> Found ".concat(String.valueOf(buildNodesTestFailures.keySet().size())).concat(" instead of ").concat(String.valueOf(buildNodesTestFailures2.keySet().size())).concat(" failed tests."));
        // sort the results by testName in ascending order
        Map.Entry<String, TestFailure>[] buildNodesTestFailuresArray = buildNodesTestFailures.entries().toArray(new Map.Entry[0]);
        Arrays.parallelSort(buildNodesTestFailuresArray, (o1, o2) -> o1.getKey().compareTo(o2.getKey()));

        // compare the tests failures and print the differences
        toolArgs.htmlGenerator.addParagraph("New failed tests:");
        toolArgs.htmlGenerator.startTable().startRow().addColumnValue("Test Name/Link", true).addColumnValue("Failure Message", true);
        toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue("Known Issues", true).endRow(): toolArgs.htmlGenerator.endRow();
        HtmlGenerator differentFailuresReport = new HtmlGenerator();
        differentFailuresReport.addParagraph("Failed tests with different failure:");
        differentFailuresReport.startTable().startRow().addColumnValue("Test Name/Link", true).addColumnValue("Failure Message", true);
        differentFailuresReport = toolArgs.integrateJira ? differentFailuresReport.addColumnValue("Known Issues", true).endRow(): differentFailuresReport.endRow();
        System.out.println("\n#TestUrl\t#Failure\t#ReferenceFailure");
        int differencesCount = 0;
        int newFailuresCount = 0;
        int differentFailuresCount = 0;
        for (Map.Entry<String, TestFailure> buildTestFailure : buildNodesTestFailuresArray) {
            String currentKey = buildTestFailure.getKey();
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
                String referenceTestFailure = "N/A";
                if (referenceTestFailures.size() == 0 ) {
                    newFailuresCount++;
                    toolArgs.htmlGenerator.startRow().addColumnValue(currentValue.testName, currentValue.testUrl).addColumnValue(StringEscapeUtils.escapeHtml4(currentValue.failureToDisplay));
                    toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue(getIssuesLinks(toolArgs, currentValue.shortTestName)).endRow(): toolArgs.htmlGenerator.endRow();
                } else {
                    differentFailuresCount++;
                    differentFailuresReport.startRow().addColumnValue(currentValue.testName, currentValue.testUrl).addColumnValue(StringEscapeUtils.escapeHtml4(currentValue.failureToDisplay));
                    differentFailuresReport = toolArgs.integrateJira ? differentFailuresReport.addColumnValue(getIssuesLinks(toolArgs, currentValue.shortTestName)).endRow(): differentFailuresReport.endRow();
                    referenceTestFailure = referenceTestFailures.get(0).failureToDisplay;
                }
                System.out.println(currentValue.testUrl.concat("\t\"").concat(currentValue.failureToDisplay).concat("\"\t\"").concat(referenceTestFailure).concat("\""));
            }
        }
        toolArgs.htmlGenerator.startRow().addColumnValue("<b>Failures differences count: ".concat(String.valueOf(newFailuresCount)).concat("</b>")).addColumnValue("");
        toolArgs.htmlGenerator = toolArgs.integrateJira ? toolArgs.htmlGenerator.addColumnValue("").endRow().endTable().addNewLine(): toolArgs.htmlGenerator.endRow().endTable().addNewLine();
        differentFailuresReport.startRow().addColumnValue("<b>Failures differences count: ".concat(String.valueOf(differentFailuresCount)).concat("</b>")).addColumnValue("");
        differentFailuresReport = toolArgs.integrateJira ? differentFailuresReport.addColumnValue("").endRow().endTable().addNewLine(): differentFailuresReport.endRow().endTable().addNewLine();
        toolArgs.htmlGenerator.addHtml(differentFailuresReport);
        System.out.println("-> Found ".concat(String.valueOf(differencesCount)).concat(" new test failures."));
    }

   private static void computeStabilityList(ToolArgs toolArgs, ArrayListValuedHashMap<String, TestStatus> testsStatus) throws IOException {
       System.out.println("\nCompute tests stability list file: " + toolArgs.stabilityListFile);
       Map<String, String> stableList = new HashedMap<>();
       Map<String, String> unstableList = new HashedMap<>();
       for (String key : testsStatus.keySet()) {
           Collection<TestStatus> values = testsStatus.get(key);
           TestStatus[] valuesArray = values.toArray(new TestStatus[0]);
           Boolean stableTest = (valuesArray.length != 0) && (valuesArray.length >= toolArgs.minTestRuns);
           Arrays.parallelSort(valuesArray, (o1, o2) -> o2.buildNumber.compareTo(o1.buildNumber));
           // if last tests runs are failed then the test is considered unstable,
           // if the last tests runs are passed then the test is considered stable
           int failedCount = 0;
           int lastStableRuns = min(toolArgs.lastStableRuns, valuesArray.length);
           for (int i = 0; i<lastStableRuns; i++) {
               if (valuesArray[i].failedStatus) {
                   failedCount++;
               }
           }
           if (failedCount == 0) {
               stableTest = stableTest && true;
           }
           if (failedCount == lastStableRuns) {
               stableTest = false;
           }
           boolean includeStabilityRate = stableTest && (failedCount != 0);
           for (int i = lastStableRuns; i<valuesArray.length; i++) {
               if (valuesArray[i].failedStatus) {
                   failedCount++;
               }
           }
           double stabilityRate = (valuesArray.length == 0) ? 0.0 : ((valuesArray.length - failedCount) * 1.0 / valuesArray.length) * 100;
           if (includeStabilityRate) {
               stableTest = stableTest && (stabilityRate > toolArgs.stabilityRate);
           }
           String stabilityValue = String.format("%.2f", stabilityRate).concat(":").concat(String.valueOf(valuesArray.length));
           if (stableTest) {
               stableList.put(key, stabilityValue);
           } else {
               unstableList.put(key, stabilityValue);
           }
       }
       // write the stability data to file
       BufferedWriter bw = null;
       try {
           bw = new BufferedWriter(new FileWriter(toolArgs.stabilityListFile));
           bw.write("STABLE=");
           for (Map.Entry<String, String> entry : stableList.entrySet()) {
               bw.write(entry.getKey().concat(":").concat(entry.getValue()).concat(";"));
           }
           bw.newLine();
           bw.write("UNSTABLE=");
           for (Map.Entry<String, String> entry : unstableList.entrySet()) {
               bw.write(entry.getKey().concat(":").concat(entry.getValue()).concat(";"));
           }
       } finally {
           if (bw != null) {
               bw.close();
           }
       }
   }

    public static void main(String[] args) throws IOException, CloneNotSupportedException, URISyntaxException {
        // ======== GET AND PARSE THE ARGUMENTS VALUES ========
        ToolArgs toolArgs = new ToolArgs();
        ToolArgs toolArgs2 = (ToolArgs) toolArgs.clone();
        toolArgs2.jobUrl = toolArgs.jobUrl2;
        toolArgs2.newUrlPrefix = toolArgs.newUrlPrefix2;
        toolArgs2.builds = toolArgs2.referenceBuilds;
        toolArgs2.lastBuildsCount = toolArgs.lastReferenceBuildsCount;
        toolArgs2.buildsFromLastXHours = toolArgs.referenceBuildsFromLastXHours;
        toolArgs2.buildParamsFilter = toolArgs.referenceBuildParamsFilter;

        // ======== START PROCESSING THE JOB NODES IN PARALLEL ========
        ExecutorService executorService;
        if (toolArgs.threadPoolSize <= 0) {
            executorService = Executors.newWorkStealingPool();
        } else {
            executorService = Executors.newFixedThreadPool(toolArgs.threadPoolSize);
        }
        CompletionService<JenkinsNodeArtifactsFilter> completionService = new ExecutorCompletionService<>(
                executorService);
        Integer processCount = submitBuildNodes(completionService, toolArgs, new HashSet<>());
        if (!toolArgs.referenceBuilds.isEmpty() || toolArgs.lastReferenceBuildsCount > 0 || toolArgs.referenceBuildsFromLastXHours > 0) {
            // submit also the build nodes for jobUrl2, with builds @referenceBuilds
            Set<Integer> excludedBuilds = new HashSet<>();
            if (toolArgs.jobUrl2.equals(toolArgs.jobUrl) && toolArgs.excludeSelfBuildsFromReferenceJob) {
                excludedBuilds = toolArgs.builds;
            }
            processCount += submitBuildNodes(completionService, toolArgs2, excludedBuilds);
        }

        MultiValuedMap<String, String> buildNodesArtifacts = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesFailures = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesTestFailures = new ArrayListValuedHashMap<>();
        MultiValuedMap<String, TestFailure> buildNodesTestFailures2 = new ArrayListValuedHashMap<>();
        ArrayListValuedHashMap<String, TestStatus> testsStatus = new ArrayListValuedHashMap<>();
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
                if (completedProcess.testsStatus.size() > 0) {
                    testsStatus.putAll(completedProcess.testsStatus);
                }
                if (completedProcess.testsFailures.size() > 0) {
                    if (toolArgs.showTestsDifferences) {
                        for (Map.Entry<String, TestFailure> entry : completedProcess.testsFailures.entries()) {
                            String key = entry.getValue().testUrl.substring(entry.getValue().testUrl.indexOf("testReport/junit/"));
                            if (completedProcess.toolArgs == toolArgs) {
                                buildNodesTestFailures.putAll(key, entry.getValue());
                            } else {
                                buildNodesTestFailures2.putAll(key, entry.getValue());
                            }
                        }
                    }
                    if (toolArgs.groupTestsFailures) {
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
            File unfinishedBackupBuildDirFile = new File(String.valueOf(toolArgs.backupJobDirFile).concat(File.separator).concat(String.valueOf(buildNumber)).concat(KEYS_SEPARATOR).concat("Unfinished"));
            File finishedBackupBuildDirFile = new File(String.valueOf(toolArgs.backupJobDirFile).concat(File.separator).concat(String.valueOf(buildNumber)));
            if (toolArgs.backupJob) {
                if (finishedBackupBuildDirFile.exists()) {
                    FileUtils.deleteQuietly(finishedBackupBuildDirFile);
                }
                if (unfinishedBackupBuildDirFile.exists() && !unfinishedBackupBuildDirFile.renameTo(finishedBackupBuildDirFile)) {
                    throw new IllegalStateException("Couldn't rename " + unfinishedBackupBuildDirFile + " directory to " + finishedBackupBuildDirFile);
                }
            }
        }

        if (toolArgs.backupJob || toolArgs.removeBackup) {
            return;
        }

        if (!toolArgs.groupTestsFailures && !toolArgs.showTestsDifferences && !toolArgs.computeStabilityList) {
            // ======== PRINT THE NODES/TESTS MATCHING THE SEARCHED TEXT ========
            printTheNodesOrTestsMatchingSearchedText(toolArgs, buildNodesArtifacts);
        }

        if (toolArgs.showTestsDifferences) {
            // ======== PRINT THE TESTS FAILURES DIFFERENCE BETWEEN 2 BUILdS ========
            printTheTestFailuresDifference(toolArgs, buildNodesTestFailures, buildNodesTestFailures2);
        }

        if (toolArgs.groupTestsFailures) {
            // ======== PRINT THE COMMON FAILURES ========
            printTheCommonFailures(toolArgs, buildNodesFailures);
        }

        if (toolArgs.computeStabilityList) {
            computeStabilityList(toolArgs, testsStatus);
        }

        toolArgs.htmlGenerator.saveHtmlFile();
    }
}