# SearchInJenkinsLogs
A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.

## Benefits:
- searches matching a regular expression with each build artifact file content
- the job builds where to search can be specified as an enumaration and range using Dbuilds property, or the last builds using -DlastBuildsCount property
- the artifacts files can be filter using regular expressions and more filters could be applied separated with commas ","
- processes each build node in a separated thread for a better performance.
- the thread count can be specified in -DthreadsCount property

## Usage e.g:
Change the path to out/artifacts/searchinjenkinslogs_jar/ directory and run:

java -DjobUrl="$jobUrl" -DthreadsCount=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DsearchedText=".* textToFind.* " -cp searchinjenkinslogs.jar Main
