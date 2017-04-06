import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.io.File;
import java.io.IOException;
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
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Created by Teo on 9/3/2016.
 */
public class ToolArgs implements Cloneable {
    private static final String PATH_PREFIX = "$.";
    private static final String PATH_SEPARATOR = ".";
    private static final String JOB_URL = "jobUrl";
    private static final String NEW_URL_PREFIX = "newUrlPrefix";
    private static final String JOB_URL2 = "jobUrl2";
    private static final String NEW_URL_PREFIX2 = "newUrlPrefix2";
    private static final String SEARCH_IN_JUNIT_REPORTS = "searchInJUnitReports";
    private static final String THREAD_POOL_SIZE = "threadPoolSize";
    private static final String BUILDS = "builds";
    private static final String LAST_BUILDS_COUNT = "lastBuildsCount";
    private static final String ARTIFACTS = "artifactsFilters";
    private static final String BUILDS_PARAMS_FILTER = "buildParamsFilter";
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
    private static final String STABILITY_RATE = "stabilityRate";
    private static final String MIN_TEST_RUNS = "minTestRuns";
    private static final String JIRA = "jira";
    private static final String API_URL = "apiUrl";
    private static final String HEADERS = "headers";
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private static final String JQL = "jql";
    private static final String URL = "url";

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
    HtmlGenerator htmlGenerator;
    StabilityListParser stabilityListParser;
    Double stabilityRate;
    String stabilityRateString;
    Integer minTestRuns;
    String minTestRunsString;
    String jiraApiUrl;
    Boolean integrateJira;
    Map<String, String> jiraHeaders;
    String jiraJql;
    String jiraUrl;
    Map<String, String> issueDescriptionMap;

    public ToolArgs() throws IOException {
        configFile = isEmpty(System.getProperty("configFile")) ? null : new File(System.getProperty("configFile"));
        System.out.println("Parameter configFile=" + configFile);
        parseConfig();
        jobUrl = getNonEmptyValue(JOB_URL, jobUrl);
        if (isEmpty(jobUrl)) {
            throw new IllegalArgumentException("-D".concat(JOB_URL).concat(" parameter cannot be empty. Please, provide a valid URL!"));
        }
        System.out.println("Parameter ".concat(JOB_URL).concat("=").concat(jobUrl));
        newUrlPrefix = getNonEmptyValue(NEW_URL_PREFIX, newUrlPrefix);
        System.out.println("Parameter " + NEW_URL_PREFIX + "=" + newUrlPrefix);
        newUrlPrefix = isEmpty(newUrlPrefix) ? jobUrl : newUrlPrefix;
        jobUrl2 = getNonEmptyValue(JOB_URL2, jobUrl2);
        jobUrl2 = isEmpty(jobUrl2) ? jobUrl : jobUrl2;
        System.out.println("Parameter " + JOB_URL2 + "=" + jobUrl2);
        newUrlPrefix2 = getNonEmptyValue(NEW_URL_PREFIX2, newUrlPrefix2);
        newUrlPrefix2 = isEmpty(newUrlPrefix2) ? jobUrl2 : newUrlPrefix2;
        System.out.println("Parameter " + NEW_URL_PREFIX2 + "=" + newUrlPrefix2);
        searchInJUnitReportsString = getNonEmptyValue(SEARCH_IN_JUNIT_REPORTS, searchInJUnitReportsString);
        searchInJUnitReports = isEmpty(searchInJUnitReportsString) ? false : Boolean.valueOf(searchInJUnitReportsString);
        System.out.println("Parameter " + SEARCH_IN_JUNIT_REPORTS + "=" + searchInJUnitReports);
        threadPoolSizeString = getNonEmptyValue(THREAD_POOL_SIZE, threadPoolSizeString);
        threadPoolSize = isEmpty(threadPoolSizeString) ? 0 : Integer.parseInt(threadPoolSizeString);
        System.out.println("Parameter " + THREAD_POOL_SIZE + "=" + threadPoolSize);
        buildsString = getNonEmptyValue(BUILDS, buildsString);
        builds = parseBuilds(buildsString);
        // in case there is no build number specified, search in the last job build artifacts
        lastBuildsCount = builds.size() == 0 ? 1 : 0;
        lastBuildsCountString = getNonEmptyValue(LAST_BUILDS_COUNT, lastBuildsCountString);
        lastBuildsCount = isEmpty(lastBuildsCountString) ? lastBuildsCount : Integer.parseInt(lastBuildsCountString);
        System.out.println("Parameter " + LAST_BUILDS_COUNT + "=" + lastBuildsCount);
        artifactsFilters = getNonEmptyValue(ARTIFACTS, artifactsFilters);
        artifactsFilters = artifactsFilters == null ? "" : artifactsFilters;
        System.out.println("Parameter " + ARTIFACTS + "=" + artifactsFilters);
        buildParamsFilterString = getNonEmptyValue(BUILDS_PARAMS_FILTER, buildParamsFilterString);
        buildParamsFilter = parseKeyValuesIntoMap(buildParamsFilterString == null ? "" : buildParamsFilterString);
        System.out.println("Parameter " + BUILDS_PARAMS_FILTER + "=" + buildParamsFilter);
        searchedText = getNonEmptyValue(SEARCHED_TEXT, searchedText);
        searchedText = searchedText == null ? "" : searchedText;
        System.out.println("Parameter " + SEARCHED_TEXT + "=" + searchedText);
        groupTestsFailuresString = getNonEmptyValue(GROUP_TESTS_FAILURES, groupTestsFailuresString);
        groupTestsFailures = isEmpty(groupTestsFailuresString) ? false : Boolean.valueOf(groupTestsFailuresString);
        System.out.println("Parameter " + GROUP_TESTS_FAILURES + "=" + groupTestsFailures);
        // the maximum difference threshold as a percentage of difference distance between 2 failures and the maximum possible distance for the shorter failure
        diffThresholdString = getNonEmptyValue(DIFF_THRESHOLD, diffThresholdString);
        diffThreshold = isEmpty(diffThresholdString) ? 10 : Double.valueOf(diffThresholdString);
        System.out.println("Parameter " + DIFF_THRESHOLD + "=" + diffThreshold);
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
        stabilityRateString = getNonEmptyValue(MIN_TEST_RUNS, stabilityRateString);
        stabilityRate = isEmpty(stabilityRateString) ? 50 : Double.valueOf(stabilityRateString);
        System.out.println("Parameter " + STABILITY_RATE + "=" + stabilityRate);
        minTestRunsString = getNonEmptyValue(MIN_TEST_RUNS, minTestRunsString);
        minTestRuns = isEmpty(minTestRunsString) ? 1 : Integer.valueOf(minTestRunsString);
        System.out.println("Parameter " + MIN_TEST_RUNS + "=" + minTestRuns);

        jiraApiUrl = getNonEmptyValue(JIRA.concat(PATH_SEPARATOR).concat(API_URL), jiraApiUrl);
        System.out.println("Parameter " + JIRA.concat(PATH_SEPARATOR).concat(API_URL) + "=" + jiraApiUrl);
        integrateJira = !isEmpty(jiraApiUrl);
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
        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);
        jobUrl = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JOB_URL));
        newUrlPrefix = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(NEW_URL_PREFIX));
        jobUrl2 = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JOB_URL2));
        newUrlPrefix2 = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(NEW_URL_PREFIX2));
        searchInJUnitReportsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(SEARCH_IN_JUNIT_REPORTS));
        threadPoolSizeString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(THREAD_POOL_SIZE));
        buildsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BUILDS));
        lastBuildsCountString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(LAST_BUILDS_COUNT));
        artifactsFilters = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(ARTIFACTS));
        buildParamsFilterString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(BUILDS_PARAMS_FILTER));
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
        showTestsDifferencesString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(SHOW_TESTS_DIFFERENCES));
        htmlReportFileString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(HTML_REPORT_FILE));
        stabilityListFileString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABILITY_LIST_FILE));
        stableReportString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABLE_REPORT));
        computeStabilityListString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(COMPUTE_STABILITY_LIST));
        stabilityRateString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(STABILITY_RATE));
        minTestRunsString = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(MIN_TEST_RUNS));
        jiraApiUrl = JsonPath.using(conf).parse(configJson).read(PATH_PREFIX.concat(JIRA).concat(PATH_SEPARATOR).concat(API_URL));
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

    private Map<String, String> getJiraIssues() throws IOException {
        String queryUrl = jiraApiUrl.concat("/search/?jql=").concat(URLEncoder.encode(jiraJql, CharEncoding.UTF_8).replace("+", "%20"));
        System.out.println("Jira query url: ".concat(queryUrl));

        HttpClient client = HttpClientBuilder.create().build();
        HttpGet request = new HttpGet(queryUrl);

        // add request headers
        for (Map.Entry<String, String> entry : jiraHeaders.entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }
        request.addHeader("Content-Type", "application/json");
        HttpResponse response = client.execute(request);
        String searchResp = IOUtils.toString(response.getEntity().getContent());
        System.out.println("Jira call response code: " + response.getStatusLine().getStatusCode());
        Map<String, String> issueDescriptionMap = new HashedMap<>();
        List<Map<String, Object>> issues = JsonPath.read(searchResp, "$.issues[*]");
        for (Map<String, Object> issue : issues) {
            String issueId = (String) issue.get("key");
            String description = JsonPath.read(issue.get("fields"), "$.description");
            issueDescriptionMap.put(issueId, description);
        }
        System.out.println("Issues found: ".concat(issueDescriptionMap.keySet().toString()));
        return issueDescriptionMap;
    }
}