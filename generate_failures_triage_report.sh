#!/bin/bash -ex
# This script generates a html triage report (ResultsReport.html) with 3 tables:
# grouping common failures - the 1st table;
# comparision of the current build with the runs from the last 5 days:
# new failed tests - the 2nd table,
# failed tests with different failure message - the 3-rd table.
# This script can be integrated as a bash script step in a freestyle Jenkins project.
# the job should run on the Jenkins master machine to be able to access the Jenkins API.
# the following variables need to be updated:

JIRA_USERNAME=""
JIRA_API_TOKEN=""
JIRA_URL="https://test.atlassian.net"
JIRA_API_URL="https://test.atlassian.net/rest/api/2"
JENKINS_URL="${JENKINS_URL}"
JENKINS_API_URL="http://localhost:8080/"
JENKINS_USERNAME=""
JENKINS_API_TOKEN=""

# these can be declared as Jenkins job parameters:
TESTS_JOB="${TESTS_JOB}" # The tests job used for getting the tests results.
TESTS_BUILD_NUMBER="${TESTS_BUILD_NUMBER}" # The build number of the TESTS_JOB job from which to get the results.
# Filter the node urls from matrix for which to get the tests results using a regular expression.
# For e.g.: ".*Browser=Chrome.*Env=test.*" for filtering only certain tests results from a tests matrix job.
NODE_URL_FILTER="${NODE_URL_FILTER}"
DIFF_THRESHOLD="${DIFF_THRESHOLD}" # The maximum failures difference threshold used for finding the common similar tests failures.
REFERENCE_BRANCH="${REFERENCE_BRANCH}" # The BRANCH job parameter value for the tests job used for filtering the tests builds that are used as reference for results comparison with the previous results.

resultsHtmlReport=${WORKSPACE}/ResultsReport.html

cd out/artifacts/searchinjenkinslogs_jar/
# remove the previous logs and html reports
rm -rf output.txt
rm -rf *.html
rm -rf ${WORKSPACE}/*.html

configFile=Config.json
echo '{"jira":{"username":"'${JIRA_USERNAME}'","password":"'${JIRA_API_TOKEN}'","url":"'${JIRA_URL}'","apiUrl":"'${JIRA_API_URL}'","jql":"labels = automatedTestsAffected AND status != Closed AND statusCategory != Done"}}' > $configFile

threadPoolSize=0 # it will use a number of parallel threads based on the available processors
buildParams=

# look for the tests results data for the runs from the last 5 days
buildsFromLastXHours=120

referenceBuildParams="BRANCH=$REFERENCE_BRANCH"
artifactsFilter=".*xml"

# return all the failed tests for the current tests run grouped by common failures.
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

# compare the current tests run results with the results from last days and return the new failures.
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

# enhance the html report styling
styleContent="<style> \
  body {font-family: \"Lato\", sans-serif;} \
  table { \
      border-collapse: collapse; \
      width: 100%; \
  } \
  table, td { \
      border: 1px solid #ccc; \
  } \
  td { \
      height: 15px; \
      padding: 5px; \
      word-wrap: break-word; \
  } \
  // td:first-child { width: 15%; } \
  td:first-child { width: 48em; min-width: 48em; max-width: 48em; } \
  td:nth-child(3) { width: 48em; min-width: 48em; max-width: 48em; } \
  // td:nth-child(3){ width: 15%; } \
  // td:last-child { width: 6%; } \
  td:last-child { width: 8em; min-width: 8em; max-width: 8em; } \
  tr:nth-of-type(odd) { \
    background-color:#f2f2f2; \
  } \
  tr:nth-of-type(even) { \
    background-color:#fff; \
  } \
</style>"
htmlHeader="<html><head><base target=\"_blank\">${styleContent}</head><body><h2> Tests Failures Triage Report </h2>"
htmlFooter="</body></html>"
echo $htmlHeader > $resultsHtmlReport
cat ResultsReport.html  >> $resultsHtmlReport
echo $htmlFooter >> $resultsHtmlReport
