#!/bin/bash -ex
# Generate a Jenkins JUnit failure-triage report with three tables:
#   1. Current-build failures grouped by similar failure text and frequency.
#   2. Newly failing tests compared with reference builds from the preceding five days.
#   3. Previously failing tests whose failure text differs from the reference history.
# Matching Jira issues are included using the configuration generated below.
#
# Setup:
#   Use as a Bash step in a Jenkins freestyle reporting job, after test artifacts exist.
#   Start in the tool workspace containing out/artifacts/searchinjenkinslogs_jar/.
#   That directory must contain a prebuilt searchinjenkinslogs.jar with its dependencies.
#   A Java 8-or-newer runtime and tee must be available on PATH.
#   The test job must archive JUnit XML files; this script reads those artifacts rather
#   than searching the Jenkins test-report endpoint directly.
#   The reporting node must reach Jenkins and Jira. The default localhost API URL
#   assumes execution on the Jenkins controller; change it for agents or proxies.
#
# Required Jenkins environment/job parameters:
#   WORKSPACE            Nonempty, writable reporting workspace path.
#   JENKINS_URL          Browser-facing Jenkins base URL, including its trailing slash.
#   TESTS_JOB            Job path after /job/, e.g. tests or folder/job/tests.
#   TESTS_BUILD_NUMBER   Current test-job build number, not the reporting job's build.
#   REFERENCE_BRANCH     Exact BRANCH build-parameter value used to select history.
#                        The test job must publish a parameter named BRANCH.
#
# Optional Jenkins job parameters:
#   NODE_URL_FILTER      Java regex matching an entire matrix node URL; empty selects all.
#   DIFF_THRESHOLD       Maximum failure-text difference percentage; 10 is typical.
#                        Empty uses the Java tool's default threshold.
#
# Settings to configure in this script:
#   JENKINS_API_URL       Reachable Jenkins API base URL; preferably without a trailing slash.
#   JENKINS_USERNAME / JENKINS_API_TOKEN  Credentials permitted to read builds and artifacts.
#   JIRA_URL              Browser-facing Jira base URL for issue links.
#   JIRA_API_URL           API base URL used by the Java tool's Jira search implementation.
#   JIRA_USERNAME / JIRA_API_TOKEN       Credentials permitted to run the configured JQL.
#   buildsFromLastXHours   Reference lookback in hours (120 by default).
#
# Outputs:
#   $WORKSPACE/ResultsReport.html  Styled report suitable for Jenkins HTML publication.
#   out/artifacts/searchinjenkinslogs_jar/ResultsReport.html  Combined raw HTML tables.
#   out/artifacts/searchinjenkinslogs_jar/output.txt          Both Java invocations' logs.
#   out/artifacts/searchinjenkinslogs_jar/Config.json         Jira config, including credentials.
#
# Safety and invocation:
#   The cleanup below deletes output.txt and ALL *.html files in both the JAR directory
#   and WORKSPACE. Use a dedicated reporting workspace and set WORKSPACE correctly.
#   Existing unquoted expansions require values and paths without whitespace or wildcards.
#   Credentials are interpolated directly into JSON; embedded quotes/backslashes need
#   appropriate handling before use. Restrict access to Config.json and do not publish it.
#   Direct execution enables -e (abort on errors) and -x (command tracing). Tracing can
#   expose credentials, so disable it or apply Jenkins secret masking before use.
#   To invoke with error checking and no tracing: bash -e generate_failures_triage_report.sh
#   Each Java/tee pipeline uses pipefail so Java failures are not hidden by tee.

# Configure service endpoints and credentials before integrating this job.
JIRA_USERNAME=""
JIRA_API_TOKEN=""
JIRA_URL="https://test.atlassian.net"
JIRA_API_URL="https://test.atlassian.net/rest/api/2"
JENKINS_URL="${JENKINS_URL}"
JENKINS_API_URL="http://localhost:8080/"
JENKINS_USERNAME=""
JENKINS_API_TOKEN=""

# These values are supplied by the reporting job, not inferred from the current Jenkins build.
TESTS_JOB="${TESTS_JOB}" # The tests job used for getting the tests results.
TESTS_BUILD_NUMBER="${TESTS_BUILD_NUMBER}" # The build number of the TESTS_JOB job from which to get the results.
# Filter the node urls from matrix for which to get the tests results using a regular expression.
# For e.g.: ".*Browser=Chrome.*Env=test.*" for filtering only certain tests results from a tests matrix job.
NODE_URL_FILTER="${NODE_URL_FILTER}"
DIFF_THRESHOLD="${DIFF_THRESHOLD}" # The maximum failures difference threshold used for finding the common similar tests failures.
REFERENCE_BRANCH="${REFERENCE_BRANCH}" # The BRANCH job parameter value for the tests job used for filtering the tests builds that are used as reference for results comparison with the previous results.

# Publishable output stays at the workspace root while Java writes its raw fragment in the JAR directory.
resultsHtmlReport=${WORKSPACE}/ResultsReport.html

cd out/artifacts/searchinjenkinslogs_jar/
# Remove previous report data so the Java HTML builder starts fresh on the first invocation.
# WARNING: the workspace glob removes unrelated HTML files too; WORKSPACE must be nonempty.
rm -rf output.txt
rm -rf *.html
rm -rf ${WORKSPACE}/*.html

# Both Java invocations load this Jira configuration and match issues against failed test names.
# The JQL excludes closed/done issues and restricts matching to the automatedTestsAffected label.
configFile=Config.json
echo '{"jira":{"username":"'${JIRA_USERNAME}'","password":"'${JIRA_API_TOKEN}'","url":"'${JIRA_URL}'","apiUrl":"'${JIRA_API_URL}'","jql":"labels = automatedTestsAffected AND status != Closed AND statusCategory != Done"}}' > $configFile

threadPoolSize=0 # it will use a number of parallel threads based on the available processors
buildParams= # No additional parameter filter is applied to the explicitly selected current build.

# Request prior test results within five days of the selected build for the comparison pass.
buildsFromLastXHours=120

# Filter history by the test job's BRANCH parameter, and select archived XML artifact paths.
referenceBuildParams="BRANCH=$REFERENCE_BRANCH"
artifactsFilter=".*xml"

# Pass 1: create the common-failures table for TESTS_BUILD_NUMBER and the selected matrix nodes.
# Empty searchedText means no text restriction. JUnit parsing is activated by groupTestsFailures.
# tee overwrites output.txt; the initial cleanup ensures ResultsReport.html is newly created.
set -o pipefail && java -DjobUrl="${JENKINS_URL}job/$TESTS_JOB/" \
        -DnewUrlPrefix="${JENKINS_API_URL}/job/$TESTS_JOB/" \
        -Dusername=${JENKINS_USERNAME} \
        -Dpassword=${JENKINS_API_TOKEN} \
        -DthreadPoolSize=${threadPoolSize} \
        -Dbuilds=$TESTS_BUILD_NUMBER \
        -DlastBuildsCount= \
        -DbuildParamsFilter=$buildParams \
        -DnodeUrlFilter="$NODE_URL_FILTER" \
        -DartifactsFilters="$artifactsFilter" \
        -DconfigFile=$configFile \
        -DsearchInJUnitReports=false \
        -DsearchedText="" \
        -DgroupTestsFailures=true \
        -DdiffThreshold=$DIFF_THRESHOLD -cp searchinjenkinslogs.jar Main 2>&1 | tee output.txt

# Pass 2: append new-failure and changed-failure tables to the existing raw HTML fragment.
# Compare against earlier builds of the same job, within the requested window and BRANCH filter.
# Exclude the current build from its own baseline; tee -a retains logs from both passes.
set -o pipefail && java -DjobUrl="${JENKINS_URL}job/$TESTS_JOB/" \
        -DnewUrlPrefix="${JENKINS_API_URL}/job/$TESTS_JOB/" \
        -Dusername=${JENKINS_USERNAME} \
        -Dpassword=${JENKINS_API_TOKEN} \
        -DthreadPoolSize=${threadPoolSize} \
        -Dbuilds=$TESTS_BUILD_NUMBER \
        -DlastBuildsCount= \
        -DbuildParamsFilter=$buildParams \
        -DnodeUrlFilter="$NODE_URL_FILTER" \
        -DjobUrl2="${JENKINS_URL}job/$TESTS_JOB/" \
        -DnewUrlPrefix2="${JENKINS_API_URL}/job/$TESTS_JOB/" \
        -DreferenceBuilds= \
        -DreferenceBuildsFromLastXHours=$buildsFromLastXHours \
        -DpreviousBuildsOnly=true \
        -DlastReferenceBuildsCount= \
        -DreferenceBuildParamsFilter="${referenceBuildParams}" \
        -DexcludeSelfBuildsFromReferenceJob=true \
        -DartifactsFilters="$artifactsFilter" \
        -DconfigFile=$configFile \
        -DsearchInJUnitReports=false \
        -DsearchedText="" \
        -DshowTestsDifferences=true \
        -DgroupTestsFailures=false \
        -DdiffThreshold=$DIFF_THRESHOLD -cp searchinjenkinslogs.jar Main 2>&1 | tee -a output.txt

# Style the combined fragment: fixed table widths on desktop and stacked cells on smaller screens.
# Column widths account for the common-failure frequency/build columns and optional Jira links.
styleContent="<style> \
body {font-family: \"Lato\", sans-serif; margin: 0; padding: 0; overflow-x: hidden;} \
table { border-collapse: collapse; width: 100%; box-sizing: border-box; table-layout: fixed; } \
table, td, th { border: 1px solid #ccc; } \
td, th { height: 15px; padding: 5px; word-wrap: break-word; overflow-wrap: break-word; } \
table:first-of-type td:nth-child(2), table:first-of-type th:nth-child(2) { \
    width: 3em; min-width: 2em; max-width: 4em; text-align: center; \
} \
table:first-of-type td:nth-child(4), table:first-of-type th:nth-child(4) { \
    width: 3em; min-width: 2em; max-width: 4em; text-align: center; \
} \
table:first-of-type td:nth-child(5), table:first-of-type th:nth-child(5) { \
    width: 7em; min-width: 2em; max-width: 7em; text-align: center; \
} \
table:not(:first-of-type) td:nth-child(2), table:not(:first-of-type) th:nth-child(2) { \
    width: auto; \
} \
table:not(:first-of-type) td:nth-child(3), table:not(:first-of-type) th:nth-child(3) { \
    width: 7em; min-width: 2em; max-width: 7em; text-align: center; \
} \
tr:nth-of-type(odd) { background-color:#f2f2f2; } \
tr:nth-of-type(even) { background-color:#fff; } \
@media (max-width: 900px) { \
    html, body { \
        margin: 0; \
        padding: 0; \
        overflow-x: hidden; \
    } \
    table { \
        width: 100%; \
        table-layout: fixed; \
    } \
    thead { \
        display: none; \
    } \
    tr { \
        display: block; \
        margin-bottom: 1em; \
        background-color: #f2f2f2; \
        border-radius: 10px; \
        padding: 0.5em 0.3em; \
        box-shadow: none; \
        width: 100%; \
    } \
    td { \
        display: block; \
        border: none; \
        position: relative; \
        min-height: 2em; \
        font-size: 1em; \
        width: 100%; \
        background-color: #fff; \
        padding: 0.5em 0.3em; \
    } \
    td a, td a:visited { \
        font-size: 0.98em; \
        word-break: break-all; \
        padding: 0.1em 0; \
    } \
} \
 </style>"
# Wrap the raw tables in a standalone HTML document, opening report links in a new tab.
# This overwrites the workspace report; the raw fragment, log, and credential config remain on disk.
htmlHeader="<html><head><base target=\"_blank\">${styleContent}</head><body><h2> Tests Failures Triage Report </h2>"
htmlFooter="</body></html>"
echo $htmlHeader > $resultsHtmlReport
cat ResultsReport.html  >> $resultsHtmlReport
echo $htmlFooter >> $resultsHtmlReport
