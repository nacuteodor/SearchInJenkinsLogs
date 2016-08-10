# SearchInJenkinsLogs
A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.

## Benefits:
- searches matching a regular expression with each build artifact file content
- the job builds where to search can be specified as an enumeration and range using -Dbuilds property, or the last builds using -DlastBuildsCount property
- the artifacts files can be filter using regular expressions and more filters could be applied separated with commas ","
- parallel processing, searches the regular expression for each build node in a separated thread for a better performance
- the thread count can be specified in -DthreadPoolSize property
- because it's done in Java it works cross platform for Windows, Unix, OS X Jenkins applications

## Usage e.g:
Change the path to out/artifacts/searchinjenkinslogs_jar/ directory and run:

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DsearchedText=".* textToFind.* " -cp searchinjenkinslogs.jar Main

## IDE:
Easy to integrate the project with Intellij, JDK 8

## Please, give a star to this project if it helps you.

## Contact
[Profile](http://nacuteodor.wix.com/profile)

## Related projects:
https://github.com/nacuteodor/ProcessTestSummaries
