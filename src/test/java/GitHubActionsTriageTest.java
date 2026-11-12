/**
 * Standalone regression checks for JUnit triage and the artifact downloader.
 * Uses temporary fixtures and a mocked GitHub CLI, so GitHub credentials are unnecessary.
 */
public class GitHubActionsTriageTest {
    /**
     * Runs parser, configuration, reporting, and downloader assertions.
     * Changes JVM properties during execution and fails immediately on a broken assertion.
     *
     * @param args unused
     * @throws Exception if fixture preparation or a checked operation fails
     * @throws AssertionError if a regression check fails
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("jobUrl", "https://github.com/owner/repo/actions/");
        System.setProperty("groupTestsFailures", "true");
        ToolArgs toolArgs = new ToolArgs();
        String xml = "<testsuites><testsuite><testcase classname='Example' name='failed'>"
                + "<failure message='assertion'>trace</failure></testcase>"
                + "<testcase classname='Example' name='errored'><error message='exception'>trace</error></testcase>"
                + "<testcase classname='Example' name='skipped'><skipped message='ignored'/></testcase>"
                + "<testcase classname='Example' name='passed'/></testsuite></testsuites>";
        FailuresMatchResult result = Main.matchJUnitReportFailures(xml, "1", toolArgs.jobUrl, toolArgs);
        check(result.testsFailures.size() == 2, "Failures and errors must be included, not skipped or passed tests");
        boolean rejected = false;
        try {
            Main.matchJUnitReportFailures("<!DOCTYPE testsuite [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>"
                    + "<testsuite/>", "1", toolArgs.jobUrl, toolArgs);
        } catch (org.xml.sax.SAXException expected) {
            rejected = true;
        }
        check(rejected, "External entities must be rejected");
        verifyLinkTargets();
        verifyFailureStacktrace(toolArgs);
        verifyFailureEntities(toolArgs);
        verifyOptionalConfiguration();
        verifyGitHubReports(toolArgs);
        verifyAllureLinks(toolArgs);
        verifyJiraReports();
        verifyJiraPagination();
        verifyDownloader();
        System.out.println("GitHub Actions triage checks passed");
    }

    /** Verifies report links open safely in a new tab while preserving HTML escaping. */
    private static void verifyLinkTargets() {
        String html = new HtmlGenerator().addLink("<test>", "https://reports.example.com/?name=%22test%22&run=1").getContent();
        check(html.contains("target=\"_blank\"") && html.contains("rel=\"noopener noreferrer\""),
                "Report links must open a new tab with opener and referrer protection");
        check(html.contains("&lt;test&gt;") && html.contains("%22test%22&amp;run=1"),
                "New-tab links must retain escaped labels and URL attributes");
        for (String target : new String[] {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\nscript:alert(1)",
            "data:text/html,test", "file:///etc/passwd", "https://user:password@reports.example.com/",
            "https://reports.example.com/\" onclick=\"alert(1)", "//reports.example.com/", null, ""}) {
            String rejected = new HtmlGenerator().addLink("<test>", target).getContent();
            check(rejected.equals("&lt;test&gt;"), "Unsafe links must become escaped, unlinked text");
        }
        for (String target : new String[] {"http://reports.example.com/", "HTTPS://reports.example.com/",
            "report.html#test", "#testresult/123"}) {
            check(new HtmlGenerator().addLink("test", target).getContent().contains("target=\"_blank\""),
                "Valid HTTP(S) and relative links must retain safe new-tab behavior");
        }
    }

        /**
         * Checks that displayed and compared failures start at the first stack frame, not a long heading.
         *
         * @param toolArgs parser options with failure grouping enabled
         * @throws Exception if a generated JUnit fixture cannot be parsed
         */
        private static void verifyFailureStacktrace(ToolArgs toolArgs) throws Exception {
        String firstFrame = "at ReservationSummarySectionComponent.clickBookNow "
            + "(/home/runner/work/example/example/qa/src/components/reservationSummarySection.component.ts:117:68)";
        String lastFrame = "at /home/runner/work/example/example/qa/tests/guest-booking.spec.ts:37:5";
        String heading = String.join(" ", java.util.Collections.nCopies(24, "Guest booking scenario"));
        for (String lineBreak : new String[] {"\n", "\r\n", "&#10;"}) {
            String body = heading + lineBreak + lineBreak + "Error: Book now button is enabled"
                + lineBreak + lineBreak + "    " + firstFrame + lineBreak + "    " + lastFrame;
            String xml = "<testsuite><testcase classname='Booking' name='guest'><failure message=\""
                + org.apache.commons.lang3.StringEscapeUtils.escapeXml11(heading + "&#10;Error: Book now button is enabled")
                + "\">" + org.apache.commons.lang3.StringEscapeUtils.escapeXml11(body)
                + "</failure></testcase></testsuite>";
                FailuresMatchResult result = Main.matchJUnitReportFailures(xml, "1", toolArgs.jobUrl, toolArgs, true);
                check(result.testsFailures.size() == 1, "A failure with a long heading must produce one observation");
                for (TestFailure failure : result.testsFailures.values()) {
            check(failure.failureToDisplay.startsWith(firstFrame), "Displayed failure must start with the first stack frame");
            check(failure.failureToCompare.startsWith(firstFrame), "Failure comparisons must retain the first stack frame");
                check(!failure.failureToDisplay.contains(lastFrame) && !failure.failureToCompare.contains(lastFrame),
                    "Only the first stacktrace line may appear in failure summaries and comparisons");
            check(!failure.failureToDisplay.contains("&#10;"), "Encoded newlines must be normalized before truncation");
            }
        }
        for (String marker : new String[] {"StackTrace:", "Stack Trace:"}) {
            String xml = "<testsuite><testcase classname='Booking' name='guest'><error message='booking failed'>"
                + org.apache.commons.lang3.StringEscapeUtils.escapeXml11(
                    marker + "\n    " + firstFrame + "\n" + marker + "\n    " + lastFrame)
                + "</error></testcase></testsuite>";
            FailuresMatchResult result = Main.matchJUnitReportFailures(xml, "1", toolArgs.jobUrl, toolArgs);
            check(result.testsFailures.size() == 1, "A labeled error stacktrace must produce a failure observation");
            check(result.testsFailures.values().iterator().next().failureToDisplay.startsWith(firstFrame),
                "The first labeled stacktrace block must be used instead of the last block");
        }
        }

        /**
         * Verifies numeric, named, and nested failure entities decode without introducing HTML markup.
         *
         * @param toolArgs parser and report options with failure grouping enabled
         * @throws Exception if a generated JUnit report cannot be parsed
         */
        private static void verifyFailureEntities(ToolArgs toolArgs) throws Exception {
        String encodedMessage = "Expected &quot;Book now&quot; &lt;button&gt; &amp; visible &#x2192; state";
        String expectedMessage = "Expected \"Book now\" <button> & visible \u2192 state";
        String firstFrame = "at Page.click (page.ts:12:3)";
        for (String lineBreak : new String[] {"&#10;", "&#xA;", "&#13;&#10;", "&amp;#10;"}) {
            String body = "Error: booking failed" + lineBreak + lineBreak + "    " + firstFrame
                + lineBreak + "    at booking.spec.ts:37:5";
            String xml = "<testsuite><testcase classname='Booking' name='guest'><failure message=\""
                + org.apache.commons.lang3.StringEscapeUtils.escapeXml11(encodedMessage) + "\">"
                + org.apache.commons.lang3.StringEscapeUtils.escapeXml11(body)
                + "</failure></testcase></testsuite>";
            FailuresMatchResult result = Main.matchJUnitReportFailures(xml, "1", toolArgs.jobUrl, toolArgs, true);
            check(result.testsFailures.size() == 1, "Encoded failure fixtures must produce one observation");
            TestFailure failure = result.testsFailures.values().iterator().next();
            check(failure.failureToCompare.startsWith(firstFrame) && failure.failureToCompare.contains(expectedMessage),
                "Entity decoding must preserve the first frame and decode the failure message");
                check(!failure.failureToCompare.contains("at booking.spec.ts:37:5"),
                    "Later stacktrace lines must be excluded after entity decoding");
            check(!failure.failureToDisplay.contains("&#10;") && !failure.failureToDisplay.contains("&#xA;")
                && !failure.failureToDisplay.contains("&amp;#"), "Failure summaries must not retain encoded line breaks");
            toolArgs.htmlGenerator = new HtmlGenerator();
            Main.printTheCommonFailures(toolArgs, result.testsFailures);
            String html = toolArgs.htmlGenerator.getContent();
            check(html.contains("&lt;button&gt;") && !html.contains("<button>"),
                "Decoded failure text must remain escaped when rendered as HTML");
        }
        }

        /**
         * Verifies that optional Jira headers may be omitted, restoring the prior config property.
     *
     * @throws Exception if the temporary configuration cannot be written, loaded, or removed
     */
    private static void verifyOptionalConfiguration() throws Exception {
        java.nio.file.Path config = java.nio.file.Files.createTempFile("triage-config-test-", ".json");
        String previousConfig = System.getProperty("configFile");
        try {
            java.nio.file.Files.write(config, "{\"jira\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.setProperty("configFile", config.toString());
            check(new ToolArgs().jiraHeaders.isEmpty(), "Optional Jira headers may be omitted");
        } finally {
            if (previousConfig == null) {
                System.clearProperty("configFile");
            } else {
                System.setProperty("configFile", previousConfig);
            }
            java.nio.file.Files.deleteIfExists(config);
        }
    }

        /**
         * Exercises artifact filters, run attempts, and paginated history through a mocked CLI.
         * Executes the real downloader script and checks that it produces an HTML report.
         *
         * @throws Exception if fixture setup, subprocess execution, or cleanup fails
         */
        private static void verifyDownloader() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("triage-download-test-");
        try {
            String run = "{\"id\":100,\"run_number\":2,\"run_attempt\":2,\"workflow_id\":10,"
                + "\"head_branch\":\"main\",\"created_at\":\"2026-11-12T12:00:00Z\","
                + "\"updated_at\":\"2026-11-12T13:00:00Z\","
                + "\"html_url\":\"https://github.com/owner/repo/actions/runs/100\"}";
            String previous = run.replace("100", "99").replace("run_number\":2", "run_number\":1")
                .replace("run_attempt\":2", "run_attempt\":1").replace("2026-11-12T", "2026-11-11T");
            String lateRerun = previous.replace("99", "98")
                .replace("2026-11-11T13:00:00Z", "2026-11-12T14:00:00Z");
            writeFixture(directory, "run.json", run);
            writeFixture(directory, "runs.json", "{\"workflow_runs\":[" + run + "," + lateRerun + "]}\n"
                + "{\"workflow_runs\":[" + previous + "]}");
            writeFixture(directory, "current-artifacts.json", "{\"artifacts\":["
                + "{\"id\":1,\"name\":\"junit-tests-100-1\",\"expired\":false},"
                + "{\"id\":2,\"name\":\"junit-tests-100-2\",\"expired\":false},"
                + "{\"id\":3,\"name\":\"other-tests-100-2\",\"expired\":false}]}");
            writeFixture(directory, "reference-artifacts.json", "{\"artifacts\":["
                + "{\"id\":4,\"name\":\"junit-tests-99-1\",\"expired\":false}]}");
            writeFixture(directory, "junit.xml", "<testsuite name='chrome'><testcase classname='Spec' name='known'>"
                + "<failure message='same'>same trace</failure></testcase></testsuite>");
            writeFixture(directory, "allure-case.json", "{\"uid\":\"known-test\",\"fullName\":\"Spec.known\"}");
            String mock = "#!/usr/bin/env bash\nset -euo pipefail\n"
                + "if [[ $1 == api ]]; then\n"
                + "case ${*: -1} in\n"
                + "*/runs/100) cat \"$FIXTURES/run.json\" ;;\n"
                + "*/runs/100/artifacts*) cat \"$FIXTURES/current-artifacts.json\" ;;\n"
                + "*/runs/99/artifacts*) cat \"$FIXTURES/reference-artifacts.json\" ;;\n"
                + "*/workflows/10/runs*) cat \"$FIXTURES/runs.json\" ;;\n"
                + "*) exit 2 ;;\nesac\n"
                + "elif [[ $1 == run && $2 == download ]]; then\n"
                + "printf '%s\\n' \"$*\" >> \"$FIXTURES/downloads.txt\"\n"
                + "mkdir -p \"${*: -1}\"\ncp \"$FIXTURES/junit.xml\" \"${*: -1}/junit.xml\"\n"
                + "if [[ $3 == 100 ]]; then\nmkdir -p \"${*: -1}/allure/data/test-cases\"\n"
                + "cp \"$FIXTURES/allure-case.json\" \"${*: -1}/allure/data/test-cases/known-test.json\"\nfi\n"
                + "else exit 2; fi\n";
            writeFixture(directory, "gh", mock);
            check(directory.resolve("gh").toFile().setExecutable(true), "Mock downloader must be executable");
            ProcessBuilder process = new ProcessBuilder("bash", "generate_github_actions_triage_report.sh");
            process.environment().put("PATH", directory + java.io.File.pathSeparator + System.getenv("PATH"));
            process.environment().put("FIXTURES", directory.toString());
            process.environment().put("GITHUB_REPOSITORY", "owner/repo");
            process.environment().put("RUN_ID", "100");
            process.environment().put("ARTIFACT_NAME_FILTER", "^junit-tests-");
            process.environment().put("OUTPUT_FILE", directory.resolve("report.html").toString());
            process.environment().put("ALLURE_REPORT_URL", "https://reports.example.com/run-100/allure/");
            process.redirectErrorStream(true).redirectOutput(directory.resolve("output.txt").toFile());
            check(process.start().waitFor() == 0, "Mocked downloader failed: "
                + new String(java.nio.file.Files.readAllBytes(directory.resolve("output.txt")),
                java.nio.charset.StandardCharsets.UTF_8));
            String downloads = new String(java.nio.file.Files.readAllBytes(directory.resolve("downloads.txt")),
                java.nio.charset.StandardCharsets.UTF_8);
            check(downloads.contains("junit-tests-100-2") && downloads.contains("junit-tests-99-1"),
                "Current and previous workflow artifacts must be downloaded");
            check(!downloads.contains("junit-tests-100-1") && !downloads.contains("other-tests"),
                "Older attempts and unrelated test artifacts must be excluded");
            check(java.nio.file.Files.exists(directory.resolve("report.html")), "Downloader must generate HTML");
                String html = new String(java.nio.file.Files.readAllBytes(directory.resolve("report.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
                check(html.contains("https://reports.example.com/run-100/allure/#testresult/known-test"),
                    "The script must forward the published Allure URL to report generation");
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(directory.toFile());
        }
        }

        /**
         * Writes a UTF-8 fixture into an existing temporary directory.
         *
         * @param directory fixture directory
         * @param name fixture filename
         * @param content text to write
         * @throws Exception if the fixture cannot be written
         */
        private static void writeFixture(java.nio.file.Path directory, String name, String content) throws Exception {
        java.nio.file.Files.write(directory.resolve(name), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

    /**
     * Checks report tables, escaping, identity separation, and invalid-input rejection.
     * Generates current/reference manifests and JUnit reports on local disk.
     *
     * @param toolArgs parser options used for direct identity and manifest checks
     * @throws Exception if fixture processing or cleanup fails
     */
    private static void verifyGitHubReports(ToolArgs toolArgs) throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("triage-test-");
        try {
            java.nio.file.Path current = java.nio.file.Files.createDirectory(directory.resolve("current"));
            java.nio.file.Path reference = java.nio.file.Files.createDirectory(directory.resolve("reference"));
            String oldXml = "<testsuite name='chrome'><testcase classname='Spec' name='changed'>"
                    + "<failure message='old message'>old trace</failure></testcase>"
                    + "<testcase classname='Spec' name='known'><error message='same'>same trace</error>"
                    + "</testcase></testsuite>";
            String newXml = oldXml.replace("old message", "new exception").replace("old trace", "different stack")
                    .replace("</testsuite>", "<testcase classname='Spec' name='new &lt;test&gt;'>"
                            + "<failure message='same'>same trace</failure></testcase>"
                            + "<testcase classname='Spec' name='ignored'><skipped/></testcase></testsuite>");
            java.nio.file.Files.write(current.resolve("junit.xml"), newXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(reference.resolve("junit.xml"), oldXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Path currentManifest = directory.resolve("current.json");
            java.nio.file.Path referenceManifest = directory.resolve("reference.json");
            writeManifest(currentManifest, current, 2);
            writeManifest(referenceManifest, reference, 1);
            System.setProperty("reportsManifest", currentManifest.toString());
            System.setProperty("referenceReportsManifest", referenceManifest.toString());
            System.setProperty("htmlReportFile", directory.resolve("report.html").toString());
            System.setProperty("diffThreshold", "0");
            GitHubActionsReport.main(new String[0]);
            String html = new String(java.nio.file.Files.readAllBytes(directory.resolve("report.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(html.split("<table", -1).length == 4, "Report must contain all three triage tables");
            check(html.contains("new &lt;test&gt;"), "Test names must be HTML escaped");
            check(!html.contains("testReport/junit/"), "GitHub reports must not contain Jenkins URLs");
            check(!html.contains("Spec.ignored"), "Skipped tests must not appear in triage tables");
            check(html.contains("Found <b>2</b> common failures"), "Identical failures must be grouped");
            check(html.contains("Failures differences count: 1"), "New and changed failures must be identified");
            FailuresMatchResult chrome = Main.matchJUnitReportFailures(oldXml, "1", toolArgs.jobUrl, toolArgs, true);
            FailuresMatchResult firefox = Main.matchJUnitReportFailures(oldXml.replace("chrome", "firefox"),
                    "1", toolArgs.jobUrl, toolArgs, true);
            check(!chrome.testsFailures.keySet().equals(firefox.testsFailures.keySet()),
                    "Different browser suites must have separate test identities");
                String ambiguousNames = "<testsuite name='suite'><testcase classname='a.b' name='c'>"
                    + "<failure/></testcase><testcase classname='a' name='b.c'><failure/></testcase>"
                    + "<testcase classname='.' name='arbitrary class'><failure/></testcase></testsuite>";
                FailuresMatchResult distinct = Main.matchJUnitReportFailures(ambiguousNames, "1", toolArgs.jobUrl,
                    toolArgs, true);
                check(distinct.testsFailures.keySet().size() == 3,
                    "Class and test names must not collide or depend on Java classname syntax");
                String nestedSuite = "<testsuite name='outer'><testsuite name='inner'><testcase classname='Spec' "
                    + "name='test'><failure/></testcase></testsuite></testsuite>";
                String slashSuite = "<testsuite name='outer/inner'><testcase classname='Spec' name='test'>"
                    + "<failure/></testcase></testsuite>";
                check(!Main.matchJUnitReportFailures(nestedSuite, "1", toolArgs.jobUrl, toolArgs, true)
                    .testsFailures.keySet().equals(Main.matchJUnitReportFailures(slashSuite, "1", toolArgs.jobUrl,
                        toolArgs, true).testsFailures.keySet()),
                    "Suite names containing slashes must not collide with nested suites");
            java.nio.file.Files.write(currentManifest, "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean missingRejected = false;
            try {
                GitHubActionsReport.readReports(currentManifest.toString(), toolArgs, false);
            } catch (IllegalArgumentException expected) {
                missingRejected = true;
            }
            check(missingRejected, "Missing current reports must not be reported as a passing run");
            writeManifest(currentManifest, current, 2);
            for (String invalidXml : new String[] {"", "<project/>"}) {
                writeFixture(current, "junit.xml", invalidXml);
                boolean invalidRejected = false;
                try {
                    GitHubActionsReport.readReports(currentManifest.toString(), toolArgs, false);
                } catch (org.xml.sax.SAXException expected) {
                    invalidRejected = true;
                }
                check(invalidRejected, "Empty or non-JUnit XML must not produce a passing report");
            }
            writeFixture(current, "junit.xml", "<testsuite tests='0'/>");
            check(GitHubActionsReport.readReports(currentManifest.toString(), toolArgs, false).isEmpty(),
                    "Valid empty JUnit suites must remain supported");
            java.nio.file.Path unrelated = java.nio.file.Files.createDirectory(directory.resolve("unrelated"));
            writeManifest(referenceManifest, unrelated, 3);
            GitHubActionsReport.readReports(referenceManifest.toString(), toolArgs, true);
            check(!toolArgs.referenceBuilds.contains(3), "Runs without XML must not be listed as reference builds");
            for (String threshold : new String[] {"NaN", "Infinity", "-1", "101"}) {
                System.setProperty("diffThreshold", threshold);
                boolean thresholdRejected = false;
                try {
                    GitHubActionsReport.main(new String[0]);
                } catch (IllegalArgumentException expected) {
                    thresholdRejected = true;
                }
                check(thresholdRejected, "Invalid similarity threshold must be rejected: " + threshold);
            }
            System.setProperty("diffThreshold", "0");
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(directory.toFile());
        }
    }

    /**
     * Checks exact Allure test matches, ambiguity handling, URL encoding, and artifact fallback.
     *
     * @param toolArgs parser options for the current and reference manifest checks
     * @throws Exception if fixture processing or cleanup fails
     */
    private static void verifyAllureLinks(ToolArgs toolArgs) throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("triage-allure-test-");
        String previousUrl = System.getProperty("allureReportUrl");
        try {
            java.nio.file.Path junitArtifact = java.nio.file.Files.createDirectory(directory.resolve("junit-artifact"));
            java.nio.file.Path allureArtifact = java.nio.file.Files.createDirectory(directory.resolve("allure-artifact"));
            writeFixture(junitArtifact, "junit.xml", "<testsuite name='suite'>"
                    + "<testcase classname='Spec' name='mapped'><failure/></testcase>"
                    + "<testcase classname='Spec' name='ambiguous'><failure/></testcase>"
                    + "<testcase classname='Spec' name='missing'><failure/></testcase></testsuite>");
            java.nio.file.Path cases = java.nio.file.Files.createDirectories(allureArtifact.resolve("data/test-cases"));
            writeAllureCase(cases, "mapped", "mapped id/#", "Spec.mapped");
            writeAllureCase(cases, "first", "first-id", "Spec.ambiguous");
            writeAllureCase(cases, "second", "second-id", "Spec.ambiguous");
            java.nio.file.Path manifest = directory.resolve("manifest.json");
                writeManifest(manifest, junitArtifact, 2);
                java.util.List<java.util.Map<String, Object>> manifestEntries = com.jayway.jsonpath.JsonPath.read(
                    new String(java.nio.file.Files.readAllBytes(manifest), java.nio.charset.StandardCharsets.UTF_8), "$");
                java.util.Map<String, Object> allureEntry = new java.util.HashMap<>(manifestEntries.get(0));
                allureEntry.put("directory", allureArtifact.toString());
                manifestEntries.add(allureEntry);
                java.nio.file.Files.write(manifest, net.minidev.json.JSONValue.toJSONString(manifestEntries)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.setProperty("allureReportUrl", "https://reports.example.com/run-100/allure/");
            org.apache.commons.collections4.MultiValuedMap<String, TestFailure> failures =
                    GitHubActionsReport.readReports(manifest.toString(), toolArgs, false);
                check(failures.size() == 3, "Separate JUnit and Allure artifacts must preserve all failure observations");
            for (TestFailure failure : failures.values()) {
                if (failure.testName.equals("Spec.mapped")) {
                    check(failure.testUrl.equals("https://reports.example.com/run-100/allure/#testresult/mapped%20id%2F%23"),
                            "Allure links must use the generated UID and encode fragment components");
                } else {
                    check(failure.testUrl.contains("/artifacts/456"), "Missing or ambiguous Allure matches must fall back");
                }
            }
            for (TestFailure failure : GitHubActionsReport.readReports(manifest.toString(), toolArgs, true).values()) {
                check(failure.testUrl.contains("/artifacts/456"), "Reference runs must not link to the current Allure report");
            }
                for (String invalidMetadata : new String[] {"{invalid", "[]", "null",
                    "{\"fullName\":\"Spec.mapped\"}", "{\"fullName\":\"Spec.mapped\",\"uid\":7}",
                    "{\"fullName\":\"\",\"uid\":\"another-id\"}"}) {
                writeFixture(cases, "invalid.json", invalidMetadata);
                org.apache.commons.collections4.MultiValuedMap<String, TestFailure> fallback =
                    GitHubActionsReport.readReports(manifest.toString(), toolArgs, false);
                check(fallback.size() == 3, "Invalid Allure metadata must not discard JUnit failures");
                for (TestFailure failure : fallback.values()) {
                    check(failure.testUrl.contains("/artifacts/456"),
                            "Invalid optional Allure metadata must preserve the report and all artifact links");
                }
            }
            java.nio.file.Files.delete(cases.resolve("invalid.json"));
            writeFixture(junitArtifact, "junit.xml", "<testsuites><testsuite name='first'>"
                    + "<testcase classname='Spec' name='mapped'><failure/></testcase></testsuite>"
                    + "<testsuite name='second'><testcase classname='Spec' name='mapped'><failure/></testcase>"
                    + "</testsuite></testsuites>");
            for (TestFailure failure : GitHubActionsReport.readReports(manifest.toString(), toolArgs, false).values()) {
                check(failure.testUrl.contains("/artifacts/456"), "Ambiguous JUnit identities must not share an Allure link");
            }
            for (String invalidUrl : new String[] {"javascript:alert(1)", "https://reports.example.com/allure/#old",
                    "https://user:password@reports.example.com/allure/"}) {
                System.setProperty("allureReportUrl", invalidUrl);
                boolean rejected = false;
                try {
                    GitHubActionsReport.readReports(manifest.toString(), toolArgs, false);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                check(rejected, "Unsafe or pre-fragmented Allure URLs must be rejected");
            }
        } finally {
            if (previousUrl == null) {
                System.clearProperty("allureReportUrl");
            } else {
                System.setProperty("allureReportUrl", previousUrl);
            }
            org.apache.commons.io.FileUtils.deleteDirectory(directory.toFile());
        }
    }

        /**
         * Ensures consecutive reports do not reuse defect links from a different Jira configuration.
         *
         * @throws Exception if report options cannot be initialized
         */
        private static void verifyJiraReports() throws Exception {
        ToolArgs first = new ToolArgs();
        first.integrateJira = true;
        first.jiraUrl = "https://first.example.com";
        first.issueDescriptionMap = new java.util.HashMap<>();
        first.issueDescriptionMap.put("QA-101", "checkout is affected");
        String firstHtml = renderJiraReport(first, "checkout");
        check(firstHtml.contains("https://first.example.com/browse/QA-101"), "First report must show its Jira defect");
        ToolArgs second = new ToolArgs();
        second.integrateJira = true;
        second.jiraUrl = "https://second.example.com";
        second.issueDescriptionMap = new java.util.HashMap<>();
        second.issueDescriptionMap.put("QA-202", "checkout is affected");
        String secondHtml = renderJiraReport(second, "checkout");
        check(secondHtml.contains("https://second.example.com/browse/QA-202") && !secondHtml.contains("QA-101"),
            "Jira link caches must not leak across report configurations");
        check(secondHtml.split("Known Issues", -1).length == 4, "All three triage tables must include Jira columns");
        int links = secondHtml.split("<a href=", -1).length - 1;
        check(links >= 4 && secondHtml.split("target=\"_blank\"", -1).length - 1 == links
            && secondHtml.split("rel=\"noopener noreferrer\"", -1).length - 1 == links,
            "Rendered test and Jira links must all retain safe new-tab attributes");
        ToolArgs markers = new ToolArgs();
        markers.integrateJira = true;
        markers.jiraUrl = "https://jira.example.com";
        markers.issueDescriptionMap = new java.util.HashMap<>();
        markers.issueDescriptionMap.put("QA-123", "testcase id: 123");
        markers.issueDescriptionMap.put("QA-1234", "TestCase ID:1234");
        markers.issueDescriptionMap.put("QA-123-other", "TestCase ID:123-other");
        markers.issueDescriptionMap.put("QA-123-slash", "TestCase ID:123/other");
        String markerHtml = renderJiraReport(markers, "TestCase ID:123");
        check(markerHtml.contains("/browse/QA-123\"") && !markerHtml.contains("QA-1234")
            && !markerHtml.contains("QA-123-other") && !markerHtml.contains("QA-123-slash"),
            "Marker-at-start matching must use complete IDs, not prefixes");
        check(!renderJiraReport(markers, "").contains("/browse/"), "An empty test name must not match every defect");
        markers.issueDescriptionMap.put("QA-checkout", "checkoutRegression is affected");
        check(!renderJiraReport(markers, "checkout").contains("QA-checkout"), "Test names must not match larger name tokens");
        markers.issueDescriptionMap.put("QA-<img src=x onerror=alert(1)>", "html-test is affected");
        String escapedHtml = renderJiraReport(markers, "html-test");
        check(escapedHtml.contains("&lt;img") && !escapedHtml.contains("<img"), "Jira link labels and attributes must be escaped");
        }

        /**
         * Verifies Jira token encoding, optional labels, HTTP error handling, and pagination guards.
         *
         * @throws Exception if local server setup or fixture processing fails
         */
        private static void verifyJiraPagination() throws Exception {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            java.nio.file.Path config = java.nio.file.Files.createTempFile("triage-jira-api-", ".json");
            String previousConfig = System.getProperty("configFile");
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean validQueries = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<String> mode = new java.util.concurrent.atomic.AtomicReference<>("success");
            String token = "page+token/=& value#";
            server.createContext("/rest/api/2/search/jql", exchange -> {
                int requestNumber = requests.incrementAndGet();
                java.util.Map<String, String> parameters = new java.util.HashMap<>();
                for (org.apache.http.NameValuePair parameter : org.apache.http.client.utils.URLEncodedUtils.parse(
                        exchange.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8.name())) {
                    parameters.put(parameter.getName(), parameter.getValue());
                }
                if (!"project = QA".equals(parameters.get("jql"))
                        || (requestNumber == 1 ? parameters.containsKey("nextPageToken")
                        : !token.equals(parameters.get("nextPageToken")))) {
                    validQueries.set(false);
                }
                net.minidev.json.JSONObject page = new net.minidev.json.JSONObject();
                net.minidev.json.JSONArray issues = new net.minidev.json.JSONArray();
                net.minidev.json.JSONObject issue = new net.minidev.json.JSONObject();
                issue.put("key", "QA-" + requestNumber);
                net.minidev.json.JSONObject fields = new net.minidev.json.JSONObject();
                    fields.put("description", requestNumber == 1 ? com.jayway.jsonpath.Configuration.defaultConfiguration()
                        .jsonProvider().parse("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                            + "{\"type\":\"text\",\"text\":\"check\"},{\"type\":\"text\",\"text\":\"out is affected\"}]},"
                            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"TestCase \"},"
                            + "{\"type\":\"text\",\"text\":\"ID:123\"}]}]}") : "checkout is affected");
                issue.put("fields", fields);
                issues.add(issue);
                page.put("issues", issues);
                page.put("isLast", "success".equals(mode.get()) && requestNumber == 2);
                if (!"missing-token".equals(mode.get())) {
                    page.put("nextPageToken", token);
                }
                byte[] body = page.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders("http-error".equals(mode.get()) ? 503 : 200, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            try {
                server.start();
                net.minidev.json.JSONObject settings = new net.minidev.json.JSONObject();
                settings.put("apiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/rest/api/2");
                settings.put("url", "https://jira.example.com");
                settings.put("jql", "project = QA");
                net.minidev.json.JSONObject root = new net.minidev.json.JSONObject();
                root.put("jira", settings);
                java.nio.file.Files.write(config, root.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.setProperty("configFile", config.toString());
                ToolArgs result = new ToolArgs();
                check(result.issueDescriptionMap.size() == 2 && requests.get() == 2 && validQueries.get(),
                        "Jira pagination must preserve encoded token values and collect all issues");
                    check(result.issueDescriptionMap.get("QA-1").contains("checkout is affected\nTestCase ID:123"),
                        "Rich Jira descriptions must combine formatted text nodes and preserve paragraph boundaries");
                    check(renderJiraReport(result, "TestCase ID:123").contains("/browse/QA-1\""),
                        "Test-case IDs split across rich-text nodes must match Jira defects");
                for (String failureMode : new String[] {"http-error", "missing-token", "repeated-token"}) {
                    mode.set(failureMode);
                    requests.set(0);
                    boolean rejected = false;
                    try {
                        new ToolArgs();
                    } catch (java.io.IOException expected) {
                        rejected = true;
                    }
                    check(rejected, "Jira must reject failed or incomplete issue queries: " + failureMode);
                }
            } finally {
                server.stop(0);
                if (previousConfig == null) {
                    System.clearProperty("configFile");
                } else {
                    System.setProperty("configFile", previousConfig);
                }
                java.nio.file.Files.deleteIfExists(config);
            }
        }

        /**
         * Renders common and comparison tables with one synthetic failing test.
         *
         * @param toolArgs report and Jira settings
         * @param name short test name used for defect matching
         * @return generated table fragments
         */
        private static String renderJiraReport(ToolArgs toolArgs, String name) {
        toolArgs.htmlGenerator = new HtmlGenerator();
        org.apache.commons.collections4.MultiValuedMap<String, TestFailure> current =
            new org.apache.commons.collections4.multimap.ArrayListValuedHashMap<>();
        current.put("test", new TestFailure("1", toolArgs.jobUrl, toolArgs.jobUrl + "runs/1", "Example." + name,
            name, "failure", "failure"));
        Main.printTheCommonFailures(toolArgs, current);
        Main.printTheTestFailuresDifference(toolArgs, current,
            new org.apache.commons.collections4.multimap.ArrayListValuedHashMap<>());
        return toolArgs.htmlGenerator.getContent();
        }

        /**
         * Writes minimal generated Allure 2 metadata for a test-link fixture.
     *
     * @param cases generated report's test-case directory
     * @param name fixture basename
     * @param uid generated report test identifier
     * @param fullName qualified test name used for matching
     * @throws Exception if the fixture cannot be written
     */
    private static void writeAllureCase(java.nio.file.Path cases, String name, String uid, String fullName) throws Exception {
        net.minidev.json.JSONObject testCase = new net.minidev.json.JSONObject();
        testCase.put("uid", uid);
        testCase.put("fullName", fullName);
        writeFixture(cases, name + ".json", testCase.toJSONString());
    }

    /**
     * Writes a single-artifact JSON manifest for a local report fixture.
     *
     * @param manifest destination JSON file
     * @param reports directory containing the fixture's XML reports
     * @param run workflow run number recorded in the manifest
     * @throws Exception if the manifest cannot be written
     */
    private static void writeManifest(java.nio.file.Path manifest, java.nio.file.Path reports, int run) throws Exception {
        net.minidev.json.JSONArray entries = new net.minidev.json.JSONArray();
        net.minidev.json.JSONObject entry = new net.minidev.json.JSONObject();
        entry.put("directory", reports.toString());
        entry.put("artifactUrl", "https://github.com/owner/repo/actions/runs/123/artifacts/456");
        entry.put("runNumber", run);
        entries.add(entry);
        java.nio.file.Files.write(manifest, entries.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Fails a regression check when its expected condition is false.
     *
     * @param condition expected true condition
     * @param message failure description
     * @throws AssertionError if the condition is false
     */
    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}