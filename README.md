# SearchInJenkinsLogs
A tool to be run for a Jenkins job to search a text with regular expressions in build artifacts.

## Benefits:
- searches matching a regular expression with each build artifact file content
- also, with -DsearchInJUnitReports=true you can search only in tests failures and print the failed test reports
- another important feature is that setting -DgroupTestsFailures=true will find the common similar tests failures with a maximum difference threshold set with -DdiffThreshold argument. 
- the job builds where to search can be specified as an enumeration and range using -Dbuilds property, or the last builds using -DlastBuildsCount property
- the artifacts files can be filter using regular expressions and more filters could be applied separated with commas ","
- parallel processing, searches the regular expression for each build node in a separated thread for a better performance
- the thread count can be specified in -DthreadPoolSize property
- has disk backup support for saving artifacts for the Jenkins job (-DbackupJob=true and -DbackupPath=$path). Also, you can search in backup files instead of querying Jenkins API (-DuseBackup=true and -DbackupPath=$path), or remove the backup for specified builds (-DremoveBackup=true and -DbackupPath=$path)
- because it's done in Java it works cross platform for Windows, Unix, OS X Jenkins applications

## Usage e.g:
Change the path to out/artifacts/searchinjenkinslogs_jar/ directory and run:

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/" -DthreadPoolSize=20 -Dbuilds="112,114-116,118" -DlastBuildsCount=2 -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DsearchInJUnitReports=true -DsearchedText=".* textToFind.* " -DgroupTestsFailures=true -DdiffThreshold=10 -DuseBackup=true -DbackupPath=$backupPath -cp searchinjenkinslogs.jar Main

java -DjobUrl="https://jenkins.com/job/JobName/" -DnewUrlPrefix="http://jenkins-proxy.com/job/JobName/" -DartifactsFilters=".* fileFilter1.* ,.* fileFilter2.* " -DbackupJob=true  -DbackupPath=$backupPath -cp searchinjenkinslogs.jar Main

## IDE:
Easy to integrate the project with Intellij, JDK 8

## Please, give a star to this project if it helps you.

## TODO:
Will add support to compare 2 builds tests failures and show the difference.

## Contact
[Profile](http://nacuteodor.wix.com/profile)

## Related projects:
https://github.com/nacuteodor/ProcessTestSummaries
