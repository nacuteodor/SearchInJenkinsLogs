import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

/**
 * Generates an HTML triage report from downloaded GitHub Actions JUnit artifacts.
 * Uses the shared failure grouping and comparison logic without querying Jenkins.
 */
public class GitHubActionsReport {
    /**
     * Reads current and reference manifests and overwrites the configured HTML report.
     * Configuration is supplied through JVM properties and the optional JSON config file.
     *
     * @param args unused; options are read by {@link ToolArgs}
     * @throws Exception if configuration, manifest parsing, XML parsing, or report writing fails
     */
    public static void main(String[] args) throws Exception {
        ToolArgs toolArgs = new ToolArgs();
        if (!Double.isFinite(toolArgs.diffThreshold) || toolArgs.diffThreshold < 0 || toolArgs.diffThreshold > 100) {
            throw new IllegalArgumentException("diffThreshold must be a finite percentage between 0 and 100");
        }
        toolArgs.builds.clear();
        toolArgs.referenceBuilds.clear();
        toolArgs.groupTestsFailures = true;
        toolArgs.showTestsDifferences = true;
        toolArgs.computeStabilityList = false;
        toolArgs.htmlGenerator = new HtmlGenerator();
        toolArgs.htmlGenerator.addText("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>GitHub Actions Tests Failures Triage Report</title>"
                + "<style>body{font-family:sans-serif;margin:20px}table{border-collapse:collapse;width:100%}"
                + "td{padding:6px;overflow-wrap:anywhere}tr:nth-child(even){background:#f2f2f2}</style>"
                + "</head><body><h2>GitHub Actions Tests Failures Triage Report</h2>");
            MultiValuedMap<String, TestFailure> current = readReports(
                requiredProperty("reportsManifest"), toolArgs, false);
            MultiValuedMap<String, TestFailure> reference = readReports(
                requiredProperty("referenceReportsManifest"), toolArgs, true);
            MultiValuedMap<String, TestFailure> grouped = new ArrayListValuedHashMap<>();
            for (Map.Entry<String, TestFailure> entry : current.entries()) {
                TestFailure failure = entry.getValue();
                grouped.put(failure.buildNumber + "#" + entry.getKey() + "#" + failure.failureToCompare, failure);
            }
            Main.printTheCommonFailures(toolArgs, grouped);
        Main.printTheTestFailuresDifference(toolArgs, current, reference);
        toolArgs.htmlGenerator.addText("</body></html>");
        Path output = toolArgs.htmlReportFile.toPath().toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, toolArgs.htmlGenerator.getContent().getBytes(StandardCharsets.UTF_8));
        System.out.println("Saved triage report: " + output);
    }

    /**
     * Loads JUnit failures from the directories listed in a UTF-8 JSON manifest.
     * Each entry contains {@code directory}, {@code artifactUrl}, and {@code runNumber}.
     * Records builds that supply XML and adds a notice when reference XML is unavailable.
    * Current failures may link to a published Allure 2 report when {@code allureReportUrl} is set.
     *
     * @param manifest path to the JSON array of downloaded artifact entries
     * @param toolArgs parsing options and report state to update
     * @param reference whether the entries belong to the historical baseline
     * @return failures keyed by the structured suite, class, and test identity
     * @throws Exception if a manifest or XML report cannot be read or parsed
     * @throws IllegalArgumentException if an artifact URL is not HTTPS or current XML is missing
     */
    static MultiValuedMap<String, TestFailure> readReports(String manifest, ToolArgs toolArgs,
                                                          boolean reference) throws Exception {
        List<Map<String, Object>> reports = JsonPath.read(
                new String(Files.readAllBytes(new File(manifest).toPath()), StandardCharsets.UTF_8), "$");
        MultiValuedMap<String, TestFailure> failures = new ArrayListValuedHashMap<>();
        int xmlCount = 0;
        String fileFilter = System.getProperty("junitFilesFilter", ".*\\.xml");
        for (Map<String, Object> report : reports) {
            String build = report.get("runNumber").toString();
            int buildNumber = Integer.parseInt(build);
            String artifactUrl = report.get("artifactUrl").toString();
            if (!artifactUrl.startsWith("https://")) {
                throw new IllegalArgumentException("Artifact URLs must use HTTPS");
            }
            Path directory = new File(report.get("directory").toString()).toPath();
            List<Path> files;
            try (Stream<Path> paths = Files.walk(directory)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(path -> directory.relativize(path).toString().matches(fileFilter))
                        .sorted().collect(Collectors.toList());
            }
            for (Path file : files) {
                String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                FailuresMatchResult result = Main.matchJUnitReportFailures(xml, build, artifactUrl, toolArgs, true);
                xmlCount++;
                failures.putAll(result.testsFailures);
            }
            if (!files.isEmpty()) {
                (reference ? toolArgs.referenceBuilds : toolArgs.builds).add(buildNumber);
            }
        }
        if (!reference && xmlCount == 0) {
            throw new IllegalArgumentException("No current JUnit XML reports found. Enable the JUnit reporter "
                    + "and check ARTIFACT_NAME_FILTER / JUNIT_FILES_FILTER.");
        }
        if (!reference) {
            applyAllureLinks(reports, failures, toolArgs);
        }
        if (reference && xmlCount == 0) {
            toolArgs.htmlGenerator.addParagraph("No reference JUnit reports were available; "
                    + "all current failures are shown as new.");
        }
        return failures;
    }

    /**
     * Replaces current artifact links with per-test links to a published Allure 2 report.
     * Reads generated {@code data/test-cases/*.json} files, not raw result UUIDs.
     * Only unique, exact full-name matches are linked; other observations retain artifact URLs.
    * Unreadable or malformed metadata leaves all current links unchanged to avoid an incomplete index.
     *
     * @param reports current artifact manifest entries, including any generated Allure report
     * @param failures current failure observations whose links may be updated
     * @throws Exception if the configured URL is invalid or report metadata cannot be read
     */
    private static void applyAllureLinks(List<Map<String, Object>> reports,
                                         MultiValuedMap<String, TestFailure> failures,
                                         ToolArgs toolArgs) throws Exception {
        String baseUrl = System.getProperty("allureReportUrl", "");
        if (baseUrl.isEmpty()) {
            return;
        }
        URI reportUri = new URI(baseUrl);
        if ((!"https".equalsIgnoreCase(reportUri.getScheme()) && !"http".equalsIgnoreCase(reportUri.getScheme()))
                || reportUri.getHost() == null || reportUri.getRawFragment() != null || reportUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("allureReportUrl must be an HTTP(S) report URL without a fragment "
                    + "or embedded credentials");
        }
        Map<String, Set<String>> testIds = new HashMap<>();
        Map<String, Set<String>> fileAndTitleIds = new HashMap<>();
        Map<String, Map<?, ?>> testCasesByUid = new HashMap<>();
        for (Map<String, Object> report : reports) {
            Path directory = Paths.get(report.get("directory").toString());
            List<Path> caseFiles;
            try (Stream<Path> paths = Files.walk(directory)) {
                caseFiles = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json")
                                && path.getParent().endsWith(Paths.get("data", "test-cases")))
                        .sorted().collect(Collectors.toList());
            }
            for (Path file : caseFiles) {
                Object metadata;
                try {
                        metadata = Configuration.defaultConfiguration().jsonProvider().parse(
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                } catch (IOException | InvalidJsonException e) {
                    System.err.println("Cannot read Allure test metadata " + file + "; retaining artifact links.");
                    return;
                }
                if (!(metadata instanceof Map)) {
                    System.err.println("Allure test metadata must be a JSON object: " + file + "; retaining artifact links.");
                    return;
                }
                Map<?, ?> testCase = (Map<?, ?>) metadata;
                if (Boolean.TRUE.equals(testCase.get("hidden"))) {
                    // Earlier retry attempts share the final result's name and would make it ambiguous.
                    continue;
                }
                Object fullName = testCase.get("fullName");
                Object uid = testCase.get("uid");
                if (!(fullName instanceof String) || ((String) fullName).isEmpty()
                        || !(uid instanceof String) || ((String) uid).isEmpty()) {
                    System.err.println("Allure test metadata is missing a fullName or uid: " + file
                            + "; retaining artifact links.");
                    return;
                }
                testIds.computeIfAbsent((String) fullName, ignored -> new HashSet<>()).add((String) uid);
                testCasesByUid.put((String) uid, testCase);
                Object name = testCase.get("name");
                if (name instanceof String && !((String) name).isEmpty()) {
                    // Playwright's Allure fullName is file:line:column, so also index by file and title.
                    String specFile = ((String) fullName).replaceFirst(":\\d+:\\d+$", "");
                    fileAndTitleIds.computeIfAbsent(specFile + "\u0000" + name, ignored -> new HashSet<>()).add((String) uid);
                }
            }
        }
        Map<String, String> parentSuiteIds = new HashMap<>();
        for (Map<String, Object> report : reports) {
            Path suitesDirectory = Paths.get(report.get("directory").toString());
            List<Path> suitesFiles;
            try (Stream<Path> paths = Files.walk(suitesDirectory)) {
                suitesFiles = paths.filter(path -> path.getFileName().toString().equals("suites.json")
                        && path.getParent().endsWith("data")).collect(Collectors.toList());
            }
            for (Path suitesFile : suitesFiles) {
                try {
                    collectParentSuites(Configuration.defaultConfiguration().jsonProvider().parse(
                            new String(Files.readAllBytes(suitesFile), StandardCharsets.UTF_8)), null, parentSuiteIds);
                } catch (IOException | InvalidJsonException e) {
                    System.err.println("Cannot read Allure suites " + suitesFile + "; using test result links.");
                }
            }
        }
        Map<String, Set<String>> junitIdentities = new HashMap<>();
        for (Map.Entry<String, TestFailure> entry : failures.entries()) {
            junitIdentities.computeIfAbsent(entry.getValue().testName, ignored -> new HashSet<>()).add(entry.getKey());
        }
        int linked = 0;
        for (TestFailure failure : failures.values()) {
            Set<String> ids = testIds.get(failure.testName);
            if (ids == null) {
                ids = findByFileAndTitle(fileAndTitleIds, failure.testName);
            }
            if (ids != null && ids.size() == 1 && junitIdentities.get(failure.testName).size() == 1) {
                String uid = URLEncoder.encode(ids.iterator().next(), StandardCharsets.UTF_8.name()).replace("+", "%20");
                String parentSuite = parentSuiteIds.get(uid);
                failure.testUrl = reportUri.resolve(parentSuite == null ? "#testresult/" + uid
                        : "#suites/" + parentSuite + "/" + uid + "/").toASCIIString();
                linked++;
                addJiraLinks(toolArgs, failure, testCasesByUid.get(ids.iterator().next()));
            }
        }
        System.out.println("Linked " + linked + " of " + failures.size() + " failure observations to Allure; "
                + "unmatched or ambiguous tests retain artifact links.");
    }

    /**
     * Writes a copy of an Allure test-case file with its Jira issues as issue links.
     * The copy goes to {@code allureLinksOutputDir/data/test-cases} so it can overlay the published report.
     *
     * @param toolArgs Jira settings
     * @param failure the failed test whose name is matched against Jira issues
     * @param testCase the parsed Allure test-case JSON object
     * @throws IOException if the copy cannot be written
     */
    @SuppressWarnings("unchecked")
    private static void addJiraLinks(ToolArgs toolArgs, TestFailure failure, Map<?, ?> testCase) throws IOException {
        String outputDir = System.getProperty("allureLinksOutputDir", "");
        if (outputDir.isEmpty() || !Boolean.TRUE.equals(toolArgs.integrateJira) || testCase == null) {
            return;
        }
        List<String> issueKeys = Main.findIssueKeys(toolArgs, failure.shortTestName);
        if (issueKeys.isEmpty()) {
            return;
        }
        Map<Object, Object> updated = new java.util.LinkedHashMap<>(testCase);
        List<Object> links = new java.util.ArrayList<>();
        Object existing = updated.get("links");
        if (existing instanceof List) {
            links.addAll((List<Object>) existing);
        }
        for (String issueKey : issueKeys) {
            Map<String, String> link = new java.util.LinkedHashMap<>();
            link.put("name", issueKey);
            link.put("url", toolArgs.jiraUrl + "/browse/" + issueKey);
            link.put("type", "issue");
            links.add(link);
        }
        updated.put("links", links);
        Path destination = Paths.get(outputDir, "data", "test-cases", testCase.get("uid") + ".json");
        Files.createDirectories(destination.getParent());
        Files.write(destination, Configuration.defaultConfiguration().jsonProvider().toJson(updated)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Maps each test UID in an Allure suites tree to the UID of its immediate parent suite.
     *
     * @param node parsed suites.json node (a map or list)
     * @param parentUid UID of the enclosing suite, or null at the root
     * @param parentSuiteIds output map from test UID to parent suite UID
     */
    private static void collectParentSuites(Object node, String parentUid, Map<String, String> parentSuiteIds) {
        if (node instanceof List) {
            for (Object child : (List<?>) node) {
                collectParentSuites(child, parentUid, parentSuiteIds);
            }
        } else if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            Object uid = map.get("uid");
            Object children = map.get("children");
            if (children == null && uid instanceof String && parentUid != null) {
                parentSuiteIds.put((String) uid, parentUid);
            } else if (children != null) {
                collectParentSuites(children, uid instanceof String ? (String) uid : parentUid, parentSuiteIds);
            }
        }
    }

    /**
     * Finds Allure UIDs for a Playwright JUnit name of the form {@code file.Describe \u203a title}.
     *
     * @param fileAndTitleIds Allure UIDs keyed by {@code file\0title}
     * @param junitName JUnit class name and test name joined by a dot
     * @return the UIDs of matching Allure tests, or null when none match
     */
    private static Set<String> findByFileAndTitle(Map<String, Set<String>> fileAndTitleIds, String junitName) {
        Set<String> matches = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : fileAndTitleIds.entrySet()) {
            String[] fileAndTitle = entry.getKey().split("\u0000", 2);
            if (junitName.startsWith(fileAndTitle[0] + ".")
                    && (junitName.endsWith(" \u203a " + fileAndTitle[1])
                    || junitName.equals(fileAndTitle[0] + "." + fileAndTitle[1]))) {
                matches.addAll(entry.getValue());
            }
        }
        return matches.isEmpty() ? null : matches;
    }

    /**
     * Reads a mandatory, nonempty JVM system property.
     *
     * @param name property name without the {@code -D} prefix
     * @return the configured property value
     * @throws IllegalArgumentException if the property is absent or empty
     */
    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("-D" + name + " is required");
        }
        return value;
    }
}