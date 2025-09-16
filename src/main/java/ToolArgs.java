import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections4.map.HashedMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.CharEncoding;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Created by Teo on 9/3/2016.
 */
public class ToolArgs implements Cloneable {
    private static final String PATH_PREFIX = "$.";
    private static final String PATH_SEPARATOR = ".";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String JOB_URL = "jobUrl";
    private static final String NEW_URL_PREFIX = "newUrlPrefix";
    private static final String JOB_URL2 = "jobUrl2";
    private static final String NEW_URL_PREFIX2 = "newUrlPrefix2";
    private static final String SEARCH_IN_JUNIT_REPORTS = "searchInJUnitReports";
    private static final String THREAD_POOL_SIZE = "threadPoolSize";
    private static final String BUILDS = "builds";
    private static final String LAST_BUILDS_COUNT = "lastBuildsCount";
    private static final String BUILDS_FROM_LAST_X_HOURS = "buildsFromLastXHours";
    private static final String REFERENCE_BUILDS_FROM_LAST_X_HOURS = "referenceBuildsFromLastXHours";
    private static final String PREVIOUS_BUILDS_ONLY = "previousBuildsOnly";
    private static final String ARTIFACTS = "artifactsFilters";
    private static final String BUILD_PARAMS_FILTER = "buildParamsFilter";
    private static final String REFERENCE_BUILD_PARAMS_FILTER = "referenceBuildParamsFilter";
    private static final String NODE_URL_FILTER = "nodeUrlFilter";
    private static final String SEARCHED_TEXT = "searchedText";
    private static final String GROUP_TESTS_FAILURES = "groupTestsFailures";
    private static final String DIFF_THRESHOLD = "diffThreshold";
    private static final String BACKUP_JOB = "backupJob";
    private static final String USE_BACKUP = "useBackup";
    private static final String REMOVE_BACKUP = "removeBackup";
    private static final String BACKUP_PATH = "backupPath";
    private static final String BACKUP_RETENTION = "backupRetention";
    private static final String REFERENCE_BUILDS = "referenceBuilds";
    private static final String LAST_REFERENCE_BUILDS_COUNT = "lastReferenceBuildsCount";
    private static final String SHOW_TESTS_DIFFERENCES = "showTestsDifferences";
    private static final String HTML_REPORT_FILE = "htmlReportFile";
    private static final String STABILITY_LIST_FILE = "stabilityListFile";
    private static final String STABLE_REPORT = "stableReport";
    private static final String COMPUTE_STABILITY_LIST = "computeStabilityList";
    private static final String EXCLUDE_SELF_BUILDS_FROM_REFERENCE_JOB = "excludeSelfBuildsFromReferenceJob";
    private static final String STABILITY_RATE = "stabilityRate";
    private static final String MIN_TEST_RUNS = "minTestRuns";
    private static final String LAST_STABLE_RUNS = "lastStableRuns";
    private static final String JIRA = "jira";
    private static final String API_URL = "apiUrl";
    private static final String HEADERS = "headers";
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private static final String JQL = "jql";
    private static final String URL = "url";

    String username;
    String password;
    String jobUrl;
    String jobUrl2;
    String newUrlPrefix;
    String newUrlPrefix2;
    String backupPath;
    String artifactsFilters;
    String searchedText;
    Integer threadPoolSize;
    private String threadPoolSizeString;
    Integer lastBuildsCount;
    private String lastBuildsCountString;
    Integer buildsFromLastXHours;
    private String buildsFromLastXHoursString;
    Integer referenceBuildsFromLastXHours;
    private String referenceBuildsFromLastXHoursString;
    Boolean previousBuildsOnly;
    private String previousBuildsOnlyString;
    Integer lastReferenceBuildsCount;
    private String lastReferenceBuildsCountString;
    Integer backupRetention;
    private String backupRetentionString;
    Double diffThreshold;
    private String diffThresholdString;
    Boolean searchInJUnitReports;
    private String searchInJUnitReportsString;
    Boolean groupTestsFailures;
    private String groupTestsFailuresString;
    Boolean showTestsDifferences;
    private String showTestsDifferencesString;
    Boolean backupJob;
    private String backupJobString;
    Boolean useBackup;
    private String useBackupString;
    Boolean removeBackup;
    private String removeBackupString;
    Boolean stableReport;
    private String stableReportString;
    Boolean computeStabilityList;
    private String computeStabilityListString;
    Boolean excludeSelfBuildsFromReferenceJob;
    private String excludeSelfBuildsFromReferenceJobString;
    File backupJobDirFile;
    File htmlReportFile;
    private String htmlReportFileString;
    File stabilityListFile;
    private String stabilityListFileString;
    File configFile;
    Set<Integer> builds;
    private String buildsString;
    Set<Integer> referenceBuilds;
    private String referenceBuildsString;
    Map<String, String> buildParamsFilter;
    private String buildParamsFilterString;
    Map<String, String> referenceBuildParamsFilter;
    private String referenceBuildParamsFilterString;
    String nodeUrlFilter;
    HtmlGenerator htmlGenerator;
    StabilityListParser stabilityListParser;
    Double stabilityRate;
    private String stabilityRateString;
    Integer minTestRuns;
    private String minTestRunsString;
    Integer lastStableRuns;
    private String lastStableRunsString;
    String jiraApiUrl;
    Boolean integrateJira;
    String jiraUsername;
    String jiraPassword;
    Map<String, String> jiraHeaders;
    String jiraJql;
    String jiraUrl;
    Map<String, String> issueDescriptionMap;

    public ToolArgs() throws IOException, URISyntaxException {
        configFile = isEmpty(System.getProperty("configFile")) ? null : new File(System.getProperty("configFile"));
        System.out.println("Parameter configFile=" + configFile);
        parseConfig();
        username = getNonEmptyValue(USERNAME, username);
        System.out.println("Parameter ".concat(USERNAME).concat("=").concat(username + ""));
        password = getNonEmptyValue(PASSWORD, password);
        System.out.println("Parameter ".concat(PASSWORD).concat("=*******"));
        jobUrl = getNonEmptyValue(JOB_URL, jobUrl);
        if (isEmpty(jobUrl)) {
            throw new IllegalArgumentException("-D".concat(JOB_URL).concat(" parameter cannot be empty. Please, provide a valid URL!"));
        }
        System.out.println("Parameter ".concat(JOB_URL).concat("=").concat(jobUrl));
        newUrlPrefix = getNonEmptyValue(NEW_URL_PREFIX, newUrlPrefix);
        newUrlPrefix = isEmpty(newUrlPrefix) ? jobUrl : newUrlPrefix;
        System.out.println("Parameter ".concat(NEW_URL_PREFIX).concat("=").concat(newUrlPrefix));
        jobUrl2 = getNonEmptyValue(JOB_URL2, jobUrl2);
        jobUrl2 = isEmpty(jobUrl2) ? jobUrl : jobUrl2;
        System.out.println("Parameter ".concat(JOB_URL2).concat("=").concat(jobUrl2));
        newUrlPrefix2 = getNonEmptyValue(NEW_URL_PREFIX2, newUrlPrefix2);
        newUrlPrefix2 = isEmpty(newUrlPrefix2) ? jobUrl2 : newUrlPrefix2;
        System.out.println("Parameter ".concat(NEW_URL_PREFIX2).concat("=").concat(newUrlPrefix2));
        searchInJUnitReportsString = getNonEmptyValue(SEARCH_IN_JUNIT_REPORTS, searchInJUnitReportsString);
        searchInJUnitReports = isEmpty(searchInJUnitReportsString) ? false : Boolean.valueOf(searchInJUnitReportsString);
        System.out.println("Parameter ".concat(SEARCH_IN_JUNIT_REPORTS).concat("=").concat(searchInJUnitReports.toString()));
        threadPoolSizeString = getNonEmptyValue(THREAD_POOL_SIZE, threadPoolSizeString);
        threadPoolSize = isEmpty(threadPoolSizeString) ? 0 : Integer.parseInt(threadPoolSizeString);
        System.out.println("Parameter ".concat(THREAD_POOL_SIZE).concat("=").concat(threadPoolSize.toString()));
        buildsString = getNonEmptyValue(BUILDS, buildsString);
        builds = parseBuilds(buildsString);
        lastBuildsCountString = getNonEmptyValue(LAST_BUILDS_COUNT, lastBuildsCountString);
        lastBuildsCount = isEmpty(lastBuildsCountString) ? 0 : Integer.parseInt(lastBuildsCountString);
        System.out.println("Parameter ".concat(LAST_BUILDS_COUNT).concat("=").concat(lastBuildsCount.toString()));
        buildsFromLastXHoursString = getNonEmptyValue(BUILDS_FROM_LAST_X_HOURS, buildsFromLastXHoursString);
        buildsFromLastXHours = isEmpty(buildsFromLastXHoursString) ? 0 : Integer.parseInt(buildsFromLastXHoursString);
        System.out.println("Parameter ".concat(BUILDS_FROM_LAST_X_HOURS).concat("=").concat(buildsFromLastXHours.toString()));
        artifactsFilters = getNonEmptyValue(ARTIFACTS, artifactsFilters);
        artifactsFilters = artifactsFilters == null ? "" : artifactsFilters;
        System.out.println("Parameter ".concat(ARTIFACTS).concat("=").concat(artifactsFilters));
        buildParamsFilterString = getNonEmptyValue(BUILD_PARAMS_FILTER, buildParamsFilterString);
        buildParamsFilter = parseKeyValuesIntoMap(buildParamsFilterString == null ? "" : buildParamsFilterString);
        System.out.println("Parameter ".concat(BUILD_PARAMS_FILTER).concat("=").concat(buildParamsFilter.toString()));
        referenceBuildParamsFilterString = getNonEmptyValue(REFERENCE_BUILD_PARAMS_FILTER, referenceBuildParamsFilterString);
        referenceBuildParamsFilter = parseKeyValuesIntoMap(referenceBuildParamsFilterString == null ? "" : referenceBuildParamsFilterString);
        referenceBuildParamsFilter = referenceBuildParamsFilter.size() == 0 ? buildParamsFilter : referenceBuildParamsFilter;
        System.out.println("Parameter ".concat(REFERENCE_BUILD_PARAMS_FILTER).concat("=").concat(referenceBuildParamsFilter.toString()));
        nodeUrlFilter = getNonEmptyValue(NODE_URL_FILTER, nodeUrlFilter);
        nodeUrlFilter = isEmpty(nodeUrlFilter) ? "" : nodeUrlFilter;
        System.out.println("Parameter ".concat(NODE_URL_FILTER).concat("=").concat(nodeUrlFilter));
        searchedText = getNonEmptyValue(SEARCHED_TEXT, searchedText);
        searchedText = searchedText == null ? "" : searchedText;
        System.out.println("Parameter ".concat(SEARCHED_TEXT).concat("=").concat(searchedText));
        groupTestsFailuresString = getNonEmptyValue(GROUP_TESTS_FAILURES, groupTestsFailuresString);
        groupTestsFailures = isEmpty(groupTestsFailuresString) ? false : Boolean.valueOf(groupTestsFailuresString);
        System.out.println("Parameter ".concat(GROUP_TESTS_FAILURES).concat("=").concat(groupTestsFailures.toString()));
        // the maximum difference threshold as a percentage of difference distance between 2 failures and the maximum possible distance for the shorter failure
        diffThresholdString = getNonEmptyValue(DIFF_THRESHOLD, diffThresholdString);
        diffThreshold = isEmpty(diffThresholdString) ? 10 : Double.valueOf(diffThresholdString);
        System.out.println("Parameter ".concat(DIFF_THRESHOLD).concat("=").concat(diffThreshold.toString()));
        backupJobString = getNonEmptyValue(BACKUP_JOB, backupJobString);
        backupJob = isEmpty(backupJobString) ? false : Boolean.valueOf(backupJobString);
        System.out.println("Parameter " + BACKUP_JOB + "=" + backupJob);
        useBackupString = getNonEmptyValue(USE_BACKUP, useBackupString);
        useBackup = isEmpty(useBackupString) ? false : Boolean.valueOf(useBackupString);
        System.out.println("Parameter " + USE_BACKUP + "=" + useBackup);
        removeBackupString = getNonEmptyValue(REMOVE_BACKUP, removeBackupString);
        removeBackup = isEmpty(removeBackupString) ? false : Boolean.valueOf(removeBackupString);
        System.out.println("Parameter " + REMOVE_BACKUP + "=" + removeBackup);
        backupPath = getNonEmptyValue(BACKUP_PATH, backupPath);
        backupPath = backupPath == null ? "" : backupPath;
        System.out.println("Parameter " + BACKUP_PATH + "=" + backupPath);
        backupRetentionString = getNonEmptyValue(BACKUP_RETENTION, backupRetentionString);
        backupRetention = isEmpty(backupRetentionString) ? 20 : Integer.parseInt(backupRetentionString);
        System.out.println("Parameter " + BACKUP_RETENTION + "=" + backupRetention);
        referenceBuildsString = getNonEmptyValue(REFERENCE_BUILDS, referenceBuildsString);
        referenceBuilds = parseBuilds(referenceBuildsString);
        System.out.println("Parameter " + REFERENCE_BUILDS + "=" + referenceBuilds);
        lastReferenceBuildsCountString = getNonEmptyValue(LAST_REFERENCE_BUILDS_COUNT, lastReferenceBuildsCountString);
        lastReferenceBuildsCount = isEmpty(lastReferenceBuildsCountString) ? 0 : Integer.parseInt(lastReferenceBuildsCountString);
        System.out.println("Parameter " + LAST_REFERENCE_BUILDS_COUNT + "=" + lastReferenceBuildsCount);
        referenceBuildsFromLastXHoursString = getNonEmptyValue(REFERENCE_BUILDS_FROM_LAST_X_HOURS, referenceBuildsFromLastXHoursString);
        referenceBuildsFromLastXHours = isEmpty(referenceBuildsFromLastXHoursString) ? 0 : Integer.parseInt(referenceBuildsFromLastXHoursString);
        System.out.println("Parameter " + REFERENCE_BUILDS_FROM_LAST_X_HOURS + "=" + referenceBuildsFromLastXHours);
        previousBuildsOnlyString = getNonEmptyValue(PREVIOUS_BUILDS_ONLY, previousBuildsOnlyString);
        previousBuildsOnly = isEmpty(previousBuildsOnlyString) ? false : Boolean.valueOf(previousBuildsOnlyString);
        System.out.println("Parameter ".concat(PREVIOUS_BUILDS_ONLY).concat("=").concat(previousBuildsOnly.toString()));
        showTestsDifferencesString = getNonEmptyValue(SHOW_TESTS_DIFFERENCES, showTestsDifferencesString);
        showTestsDifferences = isEmpty(showTestsDifferencesString) ? false : Boolean.valueOf(showTestsDifferencesString);
        System.out.println("Parameter " + SHOW_TESTS_DIFFERENCES + "=" + showTestsDifferences);
        htmlReportFileString = getNonEmptyValue(HTML_REPORT_FILE, htmlReportFileString);
        htmlReportFile = isEmpty(htmlReportFileString) ? new File("ResultsReport.html") : new File(htmlReportFileString);
        System.out.println("Parameter " + HTML_REPORT_FILE + "=" + htmlReportFile);
        htmlGenerator = new HtmlGenerator(htmlReportFile);
        stabilityListFileString = getNonEmptyValue(STABILITY_LIST_FILE, stabilityListFileString);
        stabilityListFile = isEmpty(stabilityListFileString) ? null : new File(stabilityListFileString);
        System.out.println("Parameter " + STABILITY_LIST_FILE + "=" + stabilityListFile);
        stableReportString = getNonEmptyValue(STABLE_REPORT, stableReportString);
        stableReport = isEmpty(stableReportString) ? null : Boolean.valueOf(stableReportString);
        System.out.println("Parameter " + STABLE_REPORT + "=" + stableReport);
        stabilityListParser = stableReport == null ? null : new StabilityListParser(stabilityListFile);
        computeStabilityListString = getNonEmptyValue(COMPUTE_STABILITY_LIST, computeStabilityListString);
        computeStabilityList = isEmpty(computeStabilityListString) ? false : Boolean.valueOf(computeStabilityListString);
        System.out.println("Parameter " + COMPUTE_STABILITY_LIST + "=" + computeStabilityList);
        excludeSelfBuildsFromReferenceJobString = getNonEmptyValue(EXCLUDE_SELF_BUILDS_FROM_REFERENCE_JOB, excludeSelfBuildsFromReferenceJobString);
        excludeSelfBuildsFromReferenceJob = isEmpty(excludeSelfBuildsFromReferenceJobString) ? false : Boolean.valueOf(excludeSelfBuildsFromReferenceJobString);
        System.out.println("Parameter " + EXCLUDE_SELF_BUILDS_FROM_REFERENCE_JOB + "=" + excludeSelfBuildsFromReferenceJob);
        stabilityRateString = getNonEmptyValue(STABILITY_RATE, stabilityRateString);
        stabilityRate = isEmpty(stabilityRateString) ? 50 : Double.valueOf(stabilityRateString);
        System.out.println("Parameter " + STABILITY_RATE + "=" + stabilityRate);
        minTestRunsString = getNonEmptyValue(MIN_TEST_RUNS, minTestRunsString);
        minTestRuns = isEmpty(minTestRunsString) ? 1 : Integer.valueOf(minTestRunsString);
        System.out.println("Parameter " + MIN_TEST_RUNS + "=" + minTestRuns);
        lastStableRunsString = getNonEmptyValue(LAST_STABLE_RUNS, lastStableRunsString);
        lastStableRuns = isEmpty(lastStableRunsString) ? 1 : Integer.valueOf(lastStableRunsString);
        System.out.println("Parameter " + LAST_STABLE_RUNS + "=" + lastStableRuns);

        jiraApiUrl = getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(API_URL), jiraApiUrl);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(API_URL) + "=" + jiraApiUrl);
        integrateJira = !isEmpty(jiraApiUrl);
        jiraUsername = getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(USERNAME), jiraUsername);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(USERNAME) + "=" + jiraUsername);
        jiraPassword= getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(PASSWORD), jiraPassword);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(PASSWORD) + "=********");
        jiraHeaders = !isEmpty(System.getProperty(JIRA.concat(PATH_SEPARATOR).concat(HEADERS))) ? parseKeyValuesIntoMap(System.getProperty(JIRA.concat(PATH_SEPARATOR).concat(HEADERS))) : jiraHeaders;
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(HEADERS) + "=" + jiraHeaders);
        jiraJql = getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(JQL), jiraJql);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(JQL) + "=" + jiraJql);
        jiraUrl = getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(URL), jiraUrl);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(URL) + "=" + jiraUrl);
        issueDescriptionMap = integrateJira ? getJiraIssues() : null;
    }

    public void parseConfig() throws IOException {
        if (configFile == null) {
            return;
        }
        String configJson = FileUtils.readFileToString(configFile);

        // parse the values from config
        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS);
        username = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(USERNAME));
        password = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(PASSWORD));
        jobUrl = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JOB_URL));
        newUrlPrefix = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(NEW_URL_PREFIX));
        jobUrl2 = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JOB_URL2));
        newUrlPrefix2 = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(NEW_URL_PREFIX2));
        searchInJUnitReportsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(SEARCH_IN_JUNIT_REPORTS));
        threadPoolSizeString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(THREAD_POOL_SIZE));
        buildsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BUILDS));
        lastBuildsCountString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(LAST_BUILDS_COUNT));
        buildsFromLastXHoursString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BUILDS_FROM_LAST_X_HOURS));
        artifactsFilters = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(ARTIFACTS));
        buildParamsFilterString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BUILD_PARAMS_FILTER));
        referenceBuildParamsFilterString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(REFERENCE_BUILD_PARAMS_FILTER));
        nodeUrlFilter = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(NODE_URL_FILTER));
        searchedText = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(SEARCHED_TEXT));
        groupTestsFailuresString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(GROUP_TESTS_FAILURES));
        diffThresholdString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(DIFF_THRESHOLD));
        backupJobString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BACKUP_JOB));
        useBackupString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(USE_BACKUP));
        removeBackupString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(REMOVE_BACKUP));
        backupPath = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BACKUP_PATH));
        backupRetentionString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BACKUP_RETENTION));
        referenceBuildsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(REFERENCE_BUILDS));
        lastReferenceBuildsCountString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(LAST_REFERENCE_BUILDS_COUNT));
        referenceBuildsFromLastXHoursString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(REFERENCE_BUILDS_FROM_LAST_X_HOURS));
        previousBuildsOnlyString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(PREVIOUS_BUILDS_ONLY));
        showTestsDifferencesString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(SHOW_TESTS_DIFFERENCES));
        htmlReportFileString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(HTML_REPORT_FILE));
        stabilityListFileString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABILITY_LIST_FILE));
        stableReportString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABLE_REPORT));
        computeStabilityListString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(COMPUTE_STABILITY_LIST));
        excludeSelfBuildsFromReferenceJobString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(EXCLUDE_SELF_BUILDS_FROM_REFERENCE_JOB));
        stabilityRateString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABILITY_RATE));
        minTestRunsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(MIN_TEST_RUNS));
        lastStableRunsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(LAST_STABLE_RUNS));

        jiraApiUrl = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(API_URL));
        jiraUsername = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(USERNAME));
        jiraPassword = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(PASSWORD));
        List<Map<String, String>> headersList = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(HEADERS).concat("[*]"));
        jiraHeaders = new HashMap<>();
        for (Map<String, String> header : headersList) {
            jiraHeaders.put(header.get(NAME), header.get(VALUE));
        }
        jiraJql = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(JQL));
        jiraUrl = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(URL));
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
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
                for (Integer buildNumber = Integer.parseInt(buildsRange[0].trim()); buildNumber <= Integer.parseInt(buildsRange[1].trim()); buildNumber++) {
                    buildsSet.add(buildNumber);
                }
            } else {
                Integer intBuild = Integer.parseInt(build.trim());
                buildsSet.add(intBuild);
            }
        }
        return buildsSet;
    }

    /**
     * Used for parsing a parameter with "key1=value1;key2=value2;..." values into a Map
     */
    private static Map<String, String> parseKeyValuesIntoMap(String params) {
        Map<String, String> buildParamsFilter = new HashMap<>();
        if (isEmpty(params)) {
            return buildParamsFilter;
        }
        String[] keyValueMap = params.split(";");
        for (String keyValue : keyValueMap) {
            String[] tokens = keyValue.split("=");
            String key = tokens[0];
            String value = tokens.length > 1 ? tokens[1] : "";
            if (!key.isEmpty()) {
                buildParamsFilter.put(key, value);
            }
        }
        return buildParamsFilter;
    }

    public static String getNonEmptyValue(String systemProperty, String defaultValue) {
        String paramValue = System.getProperty(systemProperty);
        return (!isEmpty(paramValue)) ? paramValue : defaultValue;
    }

    private Map<String, String> getJiraIssues() throws IOException, URISyntaxException {
        String queryUrl = jiraApiUrl.concat("/search/jql?jql=").concat(URLEncoder.encode(jiraJql, CharEncoding.UTF_8).replace("+", "%20"));
        System.out.println("Jira query url: ".concat(queryUrl));

        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS);
        HttpGet request = new HttpGet(queryUrl);

        // add request headers
        for (Map.Entry<String, String> entry : jiraHeaders.entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }
        request.addHeader("Content-Type", "application/json");

        Map<String, String> issueDescriptionMap = new HashedMap<>();
        boolean paginationFinished = false;
        String nextPageToken = "";
        while (!paginationFinished) {
            // added pagination calls (which are limited to 50 maxResults) to be able to get all the jira issues.
            request.setURI(new URI(queryUrl.concat("&").concat("fields=description,labels").concat("&").concat("maxResults=50").concat("&").concat("nextPageToken=").concat(String.valueOf(nextPageToken))));
            HttpResponse response = Main.getUrlHttpResponse(request, jiraUsername, jiraPassword);
            String pageResp = IOUtils.toString(response.getEntity().getContent());
            System.out.println("Jira call response code: " + response.getStatusLine().getStatusCode());
            if (response.getStatusLine().getStatusCode() != 200) {
                System.out.println("Jira call response error: " + pageResp);
                return issueDescriptionMap;
            }
            List<Map<String, Object>> issues = JsonPath.read(pageResp, "$.issues[*]");
            for (Map<String, Object> issue : issues) {
                String issueId = (String) issue.get("key");
                Object fields = issue.get("fields");
                Object descriptionObject = JsonPath.using(conf).parse(fields).read("$.description");
                String description = descriptionObject == null ? "" : descriptionObject.toString();
                String labels = JsonPath.read(fields, "$.labels").toString();
                issueDescriptionMap.put(issueId, labels.concat("\n") + description);
            }
            paginationFinished = JsonPath.read(pageResp, "$.isLast");
            if (!paginationFinished) {
                nextPageToken = JsonPath.read(pageResp, "$.nextPageToken").toString();
            }
        }
        System.out.println("Issues found: ".concat(issueDescriptionMap.keySet().toString()));
        return issueDescriptionMap;
    }
}
