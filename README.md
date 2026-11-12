# SearchInJenkinsLogs
A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts, to group the tests failures and to show the failures differences between 2 or more builds for a tests CI job.

## Benefits:
- searches matching a regular expression with each build artifact file content
- also, with -DsearchInJUnitReports=true you can search only in tests failures and print the failed test reports
- another important feature is that setting -DgroupTestsFailures=true will find the common similar tests failures with a maximum difference threshold set with -DdiffThreshold argument. The tests CI job needs to add the JUnit reports xml files as artifacts and the tool needs -DartifactsFilters=".*xml" as argument to filter just the xml reports.
- showing the failed tests difference, for the build specified in -Dbuilds, is also possible setting -DshowTestsDifferences=true and -DreferenceBuilds=$BuildNumbers or -DlastReferenceBuildsCount=$LastReferenceBuildsCount, the builds with the tests results to be compared with. The results will contain the failed test URL, the failure message and the reference build failure message. Same here, the tests CI job needs to add the JUnit reports xml files as artifacts and the tool needs -DartifactsFilters=".*xml" as argument to filter just the xml reports.
- the job builds, where to search, can be specified as an enumeration and range using -Dbuilds property, or the last builds using -DlastBuildsCount property
- using -DbuildParamsFilter="$param1=$value1;$param2=$value2" you can filter the Jenkins builds based on build parameters values
- the artifacts files can be filtered using regular expressions and more filters could be applied separated with comma ","
- the results can be found in the console output, but also saved in an HTML report file using -DhtmlReportFile property
- a tests stability list with the stable/unstable tests can be provided using -DstabilityListFile property and it can filter the stable/unstable tests using -DstableReport boolean property
- parallel processing, searches the regular expression for each build node in a separated thread for a better performance
- the thread count can be specified in -DthreadPoolSize property
- has disk backup support for saving artifacts for the Jenkins job (-DbackupJob=true and -DbackupPath=$path). Also, you can search in backup files instead of querying Jenkins API (-DuseBackup=true and -DbackupPath=$path), or remove the backup for specified builds (-DremoveBackup=true and -DbackupPath=$path)
- because it's done in Java it works cross platform for Windows, Unix, OS X Jenkins applications

## Usage e.g:
Change the path to out/artifacts/searchinjenkinslogs_jar/ directory and run:

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DsearchInJUnitReports=true -DsearchedText=".* textToFind.* " -DgroupTestsFailures=true -DdiffThreshold=10 -DuseBackup=true -DbackupPath=$backupPath -cp searchinjenkinslogs.jar Main

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/" -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DbackupJob=true -DbackupPath=$backupPath -DbackupRetention=100 -cp searchinjenkinslogs.jar Main

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/"  -DartifactsFilters=".*xml" -DuseBackup=true  -DbackupPath=$backupPath -DjobUrl2="https://jenkins.com/job/$ReferenceJobName/" -DnewUrlPrefix2="http://jenkins-proxy.com/job/$ReferenceJobName/" -Dbuilds=$BuildToCompare -DreferenceBuilds=$ReferenceBuilds -DlastReferenceBuildsCount=$LastReferenceBuildsCount -DdiffThreshold=$DiffThreshold -DshowTestsDifferences=true -cp searchinjenkinslogs.jar Main

- you could use it to find crashes or exceptions that appear in the app logs while running the UI tests. 
For iOS app system logs, you could use this regular expression: -DsearchedText="(.\*Terminating app due to uncaught exception.\*)|(.\*${AppName}\\[[0-9]\*\\]: fatal error:.\*)" , where ${AppName} is the name of the tested iOS app.

## Generating failures triage report:
Use [generate_failures_triage_report.sh](https://github.com/nacuteodor/SearchInJenkinsLogs/blob/master/generate_failures_triage_report.sh) script to create a Jenkins job that will generate a html triage report with all the failures and 3 tables:
 * grouping common failures - the 1st table;
 * comparison of the current build with the runs from the last 5 days: the new failed tests - the 2nd table; the failed tests with different failure message - the 3-rd table.;
  
## GitHub Actions JUnit triage reports

[generate_github_actions_triage_report.sh](generate_github_actions_triage_report.sh) generates the same three triage tables for JUnit XML files uploaded as GitHub Actions artifacts: common failures, new failed tests, and tests with different failures. Test links point to the corresponding GitHub artifact download by default, or to individual tests in a published Allure 2 report when configured. The Jenkins entry point remains available.

Requirements: JDK 8 or newer, [GitHub CLI](https://cli.github.com/), and `jq`. Authenticate `gh` locally, or supply `GH_TOKEN` in CI. The token needs Actions read access to the repository. The script compiles using the bundled libraries; a prebuilt JAR is not required.

From this repository:

```bash
GITHUB_REPOSITORY="your-org/your-repository" \
RUN_ID="123456789" \
ARTIFACT_NAME_FILTER='^junit-tests-' \
REFERENCE_BRANCH="main" \
bash generate_github_actions_triage_report.sh
```

`RUN_ID` is the ID in the workflow run URL, not its displayed run number. By default the script compares against up to 20 earlier completed runs of the **same workflow**, on the current run's branch, during the preceding five days. Both successful and failed reference runs are eligible. It never compares against the current run or newer runs, and excludes earlier runs whose latest attempt finished after the current run's creation time.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `GITHUB_REPOSITORY` | GitHub Actions environment | Repository in `owner/repository` format; required locally |
| `RUN_ID` | `GITHUB_RUN_ID` | Run whose failures are being triaged |
| `ARTIFACT_NAME_FILTER` | `.*` | Regular expression for artifact names; use an app/environment prefix to avoid mixing matrix results |
| `JUNIT_FILES_FILTER` | `.*\.xml` | Java regular expression matching XML paths relative to each downloaded artifact |
| `REFERENCE_BRANCH` | Current run's branch | Branch to use for history |
| `REFERENCE_HOURS` | `120` | Lookback window relative to the current run's creation time |
| `MAX_REFERENCE_RUNS` | `20` | Maximum reference runs; `0` disables history |
| `DIFF_THRESHOLD` | `10` | Failure similarity percentage; must be finite and between 0 and 100 |
| `OUTPUT_FILE` | `./ResultsReport.html` | Destination HTML file, overwritten on each invocation |
| `ALLURE_REPORT_URL` | Unset | Published Allure 2 report root for the current run and attempt; test fragments are appended automatically |
| `CONFIG_FILE` | Unset | Optional existing JSON configuration for Jira integration |

When `CONFIG_FILE` enables Jira integration, all three tables include **Known Issues** links. Matching uses complete test-name tokens or exact `TestCase ID:` values against issue labels and description text, including Jira Cloud rich-text descriptions. Failed Jira requests or invalid pagination stop generation rather than presenting a partial defect index as complete.

Artifacts named `...-<run_id>-<attempt>` are restricted to the run's latest attempt. For other naming conventions, ensure the artifact filter selects the intended attempt. Expired reference artifacts are skipped. Missing or expired current artifacts, download failures, and empty, malformed, or non-JUnit XML fail generation rather than silently producing a passing report. Narrow `JUNIT_FILES_FILTER` if an artifact contains other XML files. Valid JUnit suites with zero tests are accepted. If no reference XML is available, the report explicitly states that all current failures are treated as new. Only runs supplying parsed XML are listed as reference builds. Test identity includes the suite hierarchy, class, and test name as separate fields, keeping different browser projects separate when their JUnit suite names differ.

### Enable JUnit Output

Configure your test runner to write JUnit XML reports and ensure your workflow uploads the directory containing those files as an artifact, for example `junit-tests-<run_id>-<attempt>`.

Older runs without JUnit output cannot serve as JUnit history. This tool consumes existing JUnit XML files; it does not convert other test report formats into JUnit.

### Failure Message Formatting

JUnit failure/error messages and stacktraces are normalized before grouping and historical comparisons:

- HTML entities are decoded, including decimal and hexadecimal references, named entities, and up to four nested escaping layers. For example, `&#10;&#10;` becomes two newline characters, `&#xA;` becomes a newline, and `&quot;` becomes a quotation mark.
- Windows and carriage-return line endings are normalized to newline characters.
- If a `StackTrace:` or `Stack Trace:` section exists, the first section is used. The summary starts at its first `at ...` stack frame, skipping preceding test headings and error prose. Without a stack frame, the selected text's first line is retained.
- The failure signature combines only the first selected stacktrace line with the first line of the decoded message; later stack frames are excluded. Display snippets contain at most 250 characters plus an ellipsis when truncated; grouping and comparison use the untruncated signature.

For example, a summary can begin with the component method where the failure originated rather than the test's final caller:

```text
at BookingPage.submit (booking-page.ts:117:68)
: Booking failed
```

Decoded text is still HTML-escaped when rendered, so message contents such as `<button>` are displayed as text rather than interpreted as markup. These formatting rules also apply to Jenkins JUnit triage because both entry points share the parser.

See [examples/github-actions-triage-report.html](examples/github-actions-triage-report.html) for a sample report with placeholder Allure test links and Jira defects.

### Allure Test Links

To link directly to individual tests, publish the complete generated **Allure 2** report and include its `data/test-cases/*.json` files in the downloaded artifacts. Also include `data/suites.json` to enable links that open the test within its parent suite. These files may be in the same artifact as the JUnit XML or in another artifact selected by `ARTIFACT_NAME_FILTER`.

Set `ALLURE_REPORT_URL` to the published report root, including its report path and trailing slash, not an individual test URL, API endpoint, or artifact download URL. The script adds the appropriate test fragment automatically:

```bash
GITHUB_REPOSITORY="your-org/your-repository" \
RUN_ID="123456789" \
ARTIFACT_NAME_FILTER='^(junit-tests-|allure-report-)' \
ALLURE_REPORT_URL="https://reports.example.com/run-123456789-1/allure/" \
bash generate_github_actions_triage_report.sh
```

The generated link depends on the available metadata:

- With a parent suite resolved from `data/suites.json`: `https://reports.example.com/run-123456789-1/allure/#suites/<parent-suite-uid>/<test-uid>/`.
- Without a resolved parent suite: `https://reports.example.com/run-123456789-1/allure/#testresult/<test-uid>`.

The tool first matches JUnit `classname.name` against the generated Allure test's `fullName`. If no full-name match exists, it tries matching the test file and title using Allure's `fullName` and `name`, removing a trailing `:line:column` from the Allure file reference. Both matching paths require a unique result; missing or ambiguous matches retain their GitHub artifact links. Metadata marked `hidden: true` is ignored so earlier retry attempts do not create false ambiguities.

If any non-hidden generated test-case metadata is unreadable, malformed, or missing its `fullName`/`uid`, all current links retain their artifact URLs rather than trusting an incomplete index. Missing or unreadable suite metadata still permits `#testresult/` links. Raw `*-result.json` UUIDs are not generated report UIDs and cannot supply these links. Allure 3 and single-file report formats are not supported by this resolver.

Use the URL of the report for the **current run and attempt**, not a moving latest-report URL. The URL must use HTTP(S) and have no fragment or embedded credentials. Do not include secret tokens in the URL: generated links are written into the HTML report. The report and its assets must be hosted at that URL; an artifact download URL cannot serve as a browsable Allure report. Reference failures are not linked to the current run's report. A console message reports how many failure observations received Allure links, or a warning explains why an invalid metadata index was not used.

### Integrate Into a Workflow

Run triage in a job **after** the job that uploads JUnit artifacts, so the current artifacts exist before download. For a workflow with a `tests` job that uploads artifacts named `junit-tests-<run_id>-<attempt>`, an additional job can look like this (once the tool changes are available in the tool repository):

```yaml
triage:
  needs: tests
  if: ${{ always() && needs.tests.result != 'cancelled' }}
  runs-on: ubuntu-latest
  permissions:
    actions: read
    contents: read
  steps:
    - uses: actions/checkout@v4
      with:
        repository: nacuteodor/SearchInJenkinsLogs
        path: triage-tool
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: '17'
    - name: Generate JUnit triage report
      env:
        GH_TOKEN: ${{ github.token }}
        ARTIFACT_NAME_FILTER: '^junit-tests-'
        # Optional: publish the Allure report and include its generated metadata in this artifact.
        # ALLURE_REPORT_URL: https://reports.example.com/run-${{ github.run_id }}-${{ github.run_attempt }}/allure/
      run: bash triage-tool/generate_github_actions_triage_report.sh
    - name: Upload triage report
      uses: actions/upload-artifact@v4
      with:
        name: junit-triage-${{ github.run_id }}-${{ github.run_attempt }}
        path: ResultsReport.html
        if-no-files-found: error
```

Pin the action versions and tool checkout revision according to your repository's CI policy. GitHub-hosted Ubuntu runners include `gh` and `jq`; install them separately on self-hosted runners. For cross-repository downloads, supply a token with access to the target repository rather than relying on the workflow's repository-scoped token.

If another job publishes Allure, add that job to `triage.needs` so publication finishes first, and set `ALLURE_REPORT_URL` from its report-URL output. If metadata is uploaded as a separate artifact, expand the artifact-name filter to include it.

### Local Regression Checks

```bash
bash -n generate_github_actions_triage_report.sh
javac -cp 'lib/*' -d target/triage-test-classes src/main/java/*.java src/test/java/GitHubActionsTriageTest.java
java -cp 'target/triage-test-classes:lib/*' GitHubActionsTriageTest
```

These checks cover JUnit failures/errors/skips, first-frame stacktrace selection, decoded failure-message entities, XML entity rejection, grouping and historical comparisons, browser identities, HTML escaping, missing reports, Allure test links and ambiguity fallback, Jira matching and pagination through a local HTTP fixture, and the downloader with a mocked `gh` command. They do not require GitHub or Jira credentials.

## IDE:
Easy to integrate the project with Intellij, JDK 8.

Import the project in IntelliJ:
1. Create project from existing sources and select the project folder.
2. Go from command line to project folder.
3. Call "git reset --hard" to reset the project settings to the ones saved in git.
4. You are asked to reload the project in IDE and reload it.

Now, you should be able to build the project.
To build the jar with your updates:
1. Go to Build -> Build Artifacts -> Rebuild.

In case you need another library dependency:
1. Module Settings -> Libraries  - Add mvn library
2. Module Settings -> Artifacts -> Output Layout - Select from "Available Elements" the new library and "Extract Into Output Root" (need to make sure there aren't libraries versions conflicts)
3. Apply.

## Please, give a star to this project if it helps you.

## Contact
[Profile](http://nacuteodor.wix.com/profile)

## Related projects:
https://github.com/nacuteodor/ProcessTestSummaries
