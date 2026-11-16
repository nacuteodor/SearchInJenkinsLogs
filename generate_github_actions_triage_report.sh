#!/usr/bin/env bash
# Generate an HTML failure-triage report from GitHub Actions JUnit artifacts.
# The report groups common failures and identifies new or changed failures relative
# to earlier completed runs of the same workflow on the selected reference branch.
# Run after the test job has uploaded its JUnit XML artifacts.
#
# Prerequisites:
#   gh, jq, and a JDK with java/javac (Java 8 or newer).
#   Authenticate gh locally or provide GH_TOKEN/GITHUB_TOKEN with Actions read access.
#   The bundled lib/ directory supplies the Java dependencies; no prebuilt JAR is needed.
#
# Required environment:
#   GITHUB_REPOSITORY     Target repository in owner/repository format.
#   RUN_ID               Workflow run ID from its URL, not the displayed run number.
#                        Defaults to GITHUB_RUN_ID when running in GitHub Actions.
#
# Optional environment:
#   ARTIFACT_NAME_FILTER jq regular expression for artifact names (default: .*).
#   JUNIT_FILES_FILTER   Java regular expression matching the entire relative XML path
#                        inside each artifact (default: .*\.xml).
#   REFERENCE_BRANCH     History branch (default: the current run's head branch).
#   REFERENCE_HOURS      Lookback from current run creation, in hours (default: 120).
#   MAX_REFERENCE_RUNS   Maximum historical runs to inspect (default: 20; 0 disables history).
#   DIFF_THRESHOLD       Failure similarity percentage, finite and within 0..100 (default: 10).
#   OUTPUT_FILE          HTML destination (default: $PWD/ResultsReport.html).
#   ALLURE_REPORT_URL    Optional published Allure 2 report URL for the current run.
#                        Matching requires generated data/test-cases/*.json in the artifacts.
#   ALLURE_LINKS_OUTPUT_DIR  Optional directory receiving Allure test-case files with Jira issue links.
#   CONFIG_FILE          Optional JSON configuration, including Jira integration settings.
#
# Example, from the tool repository:
#   GITHUB_REPOSITORY=owner/repository RUN_ID=123456789 \
#     ARTIFACT_NAME_FILTER='^junit-tests-' bash generate_github_actions_triage_report.sh
#
# Outputs and failures:
#   Successful generation overwrites OUTPUT_FILE and prints triage details to stdout.
#   Temporary artifacts, manifests, and compiled classes are removed on exit.
#   Missing tools, API/download errors, expired/missing current artifacts, or invalid
#   JUnit input stop generation with a nonzero exit status. Expired reference artifacts
#   are skipped; missing reference XML is explicitly noted in the generated report.
set -euo pipefail

# Locate sources and libraries relative to the script, independent of the caller's directory.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for tool in gh jq java javac; do
    command -v "$tool" >/dev/null || { printf 'Required command not found: %s\n' "$tool" >&2; exit 1; }
done

# Resolve environment defaults and validate integer inputs before contacting GitHub.
: "${GITHUB_REPOSITORY:?Set GITHUB_REPOSITORY to owner/repository}"
: "${RUN_ID:=${GITHUB_RUN_ID:-}}"
: "${RUN_ID:?Set RUN_ID to the workflow run ID (not run number)}"
REFERENCE_HOURS="${REFERENCE_HOURS:-120}"
MAX_REFERENCE_RUNS="${MAX_REFERENCE_RUNS:-20}"
ARTIFACT_NAME_FILTER="${ARTIFACT_NAME_FILTER:-.*}"
JUNIT_FILES_FILTER="${JUNIT_FILES_FILTER:-.*\.xml}"
DIFF_THRESHOLD="${DIFF_THRESHOLD:-10}"
OUTPUT_FILE="${OUTPUT_FILE:-$PWD/ResultsReport.html}"
ALLURE_REPORT_URL="${ALLURE_REPORT_URL:-}"
[[ "$RUN_ID" =~ ^[0-9]+$ && "$REFERENCE_HOURS" =~ ^[0-9]+$ && "$MAX_REFERENCE_RUNS" =~ ^[0-9]+$ ]] || {
    printf 'RUN_ID, REFERENCE_HOURS, and MAX_REFERENCE_RUNS must be nonnegative integers\n' >&2
    exit 1
}

# Keep downloads and build products isolated; EXIT also cleans up after command failures.
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# The target run determines the workflow, default history branch, and report URL.
repo_api="repos/$GITHUB_REPOSITORY"
current_run="$(gh api "$repo_api/actions/runs/$RUN_ID")"
workflow_id="$(jq -r '.workflow_id' <<< "$current_run")"
REFERENCE_BRANCH="${REFERENCE_BRANCH:-$(jq -r '.head_branch' <<< "$current_run")}"
run_url="$(jq -r '.html_url' <<< "$current_run")"

# Download selected artifacts for one run and append their metadata to a JSON manifest.
# Arguments:
#   $1  JSON workflow-run object containing id, run_number, run_attempt, and html_url.
#   $2  Existing JSON-array manifest to update in place.
#   $3  true for mandatory current artifacts; false for optional reference artifacts.
# Entries contain directory, artifactUrl, and runNumber, as consumed by GitHubActionsReport.
# Missing/expired mandatory artifacts terminate the script; reference expiry is tolerated.
download_reports() {
    local run="$1" manifest="$2" required="$3"
    local id number url attempt artifacts count=0 artifact artifact_id name directory entry
    id="$(jq -r '.id' <<< "$run")"
    number="$(jq -r '.run_number' <<< "$run")"
    url="$(jq -r '.html_url' <<< "$run")"
    attempt="$(jq -r '.run_attempt' <<< "$run")"

    # Merge paginated results and retain only the latest attempt for names ending in
    # -<run_id>-<attempt>. Other naming schemes must be restricted by the caller's filter.
    artifacts="$(gh api --paginate "$repo_api/actions/runs/$id/artifacts?per_page=100" |
        jq -s --arg filter "$ARTIFACT_NAME_FILTER" --arg id "$id" --arg attempt "$attempt" \
            '[.[].artifacts[] | select(.name | test($filter)) |
            select((.name | test("-" + $id + "-[0-9]+$") | not)
                or (.name | endswith("-" + $id + "-" + $attempt)))]')"
    if [[ "$required" == true ]] && jq -e 'any(.[]; .expired)' <<< "$artifacts" >/dev/null; then
        printf 'A matching current artifact has expired; cannot generate a complete report\n' >&2
        exit 1
    fi
    while IFS= read -r artifact; do
        artifact_id="$(jq -r '.id' <<< "$artifact")"
        name="$(jq -r '.name' <<< "$artifact")"
        directory="$work_dir/$id/$artifact_id"
        gh run download "$id" --repo "$GITHUB_REPOSITORY" --name "$name" --dir "$directory"

        # Use JSON serialization so paths and URLs remain valid even with special characters.
        entry="$(jq -n --arg directory "$directory" --arg artifactUrl "$url/artifacts/$artifact_id" \
            --argjson runNumber "$number" '{directory:$directory,artifactUrl:$artifactUrl,runNumber:$runNumber}')"
        jq --argjson entry "$entry" '. + [$entry]' "$manifest" > "$work_dir/manifest.tmp"
        mv "$work_dir/manifest.tmp" "$manifest"
        count=$((count + 1))
    done < <(jq -c '.[] | select(.expired | not)' <<< "$artifacts")
    if [[ "$required" == true && "$count" == 0 ]]; then
        printf 'No matching artifacts found for current run %s\n' "$id" >&2
        exit 1
    fi
}

# Current artifacts are mandatory; an empty reference manifest is a valid no-history baseline.
jq -n '[]' > "$work_dir/current.json"
jq -n '[]' > "$work_dir/reference.json"
download_reports "$current_run" "$work_dir/current.json" true
if [[ "$MAX_REFERENCE_RUNS" != 0 ]]; then
    # Anchor the window to run creation rather than the time this script is invoked.
    current_time="$(jq -r '.created_at' <<< "$current_run")"
    cutoff="$(jq -nr --arg timestamp "$current_time" --argjson hours "$REFERENCE_HOURS" \
        '$timestamp | fromdateiso8601 - ($hours * 3600) | todateiso8601')"
    branch="$(jq -nr --arg branch "$REFERENCE_BRANCH" '$branch | @uri')"
    created="$(jq -nr --arg range "$cutoff..$current_time" '$range | @uri')"

    # Consider both successful and failed completed runs, newest first. Exclude the
    # target run, newer run numbers, and older runs updated after the target was created.
    gh api --paginate "$repo_api/actions/workflows/$workflow_id/runs?per_page=100&status=completed&branch=$branch&created=$created" |
        jq -s --argjson current "$current_run" --argjson max "$MAX_REFERENCE_RUNS" \
            '[.[].workflow_runs[] | select(.id != $current.id and .run_number < $current.run_number
            and .created_at < $current.created_at and .updated_at <= $current.created_at)]
            | sort_by(.created_at) | reverse | .[:$max][]' -c > "$work_dir/runs.jsonl"
    while IFS= read -r run; do
        download_reports "$run" "$work_dir/reference.json" false
    done < "$work_dir/runs.jsonl"
fi

# Compile into temporary storage, then reuse the Java grouping/comparison implementation.
# The optional config argument is passed only when CONFIG_FILE is nonempty.
mkdir -p "$work_dir/classes"
javac -encoding UTF-8 -cp "$script_dir/lib/*" -d "$work_dir/classes" "$script_dir"/src/main/java/*.java
java "-DjobUrl=$run_url" "-DreportsManifest=$work_dir/current.json" \
    "-DreferenceReportsManifest=$work_dir/reference.json" "-DjunitFilesFilter=$JUNIT_FILES_FILTER" \
    "-DallureReportUrl=$ALLURE_REPORT_URL" "-DallureLinksOutputDir=${ALLURE_LINKS_OUTPUT_DIR:-}" \
    "-DdiffThreshold=$DIFF_THRESHOLD" "-DhtmlReportFile=$OUTPUT_FILE" \
    ${CONFIG_FILE:+"-DconfigFile=$CONFIG_FILE"} -cp "$work_dir/classes:$script_dir/lib/*" GitHubActionsReport