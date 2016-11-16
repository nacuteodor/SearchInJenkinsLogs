import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Created by Teo on 9/3/2016.
 */
public class ToolArgs implements Cloneable {
    String jobUrl;
    String jobUrl2;
    String newUrlPrefix;
    String newUrlPrefix2;
    String backupPath;
    String threadPoolSizeString;
    String artifactsFilters;
    String searchedText;
    Integer threadPoolSize;
    Integer lastBuildsCount;
    Integer lastReferenceBuildsCount;
    Double diffThreshold;
    Boolean searchInJUnitReports;
    Boolean groupTestsFailures;
    Boolean showTestsDifferences;
    Boolean backupJob;
    Boolean useBackup;
    Boolean removeBackup;
    Boolean stableReport;
    File backupJobDirFile;
    File htmlReportFile;
    File stabilityListFile;
    Set<Integer> builds;
    Set<Integer> referenceBuilds;
    Map<String, String> buildParamsFilter;
    HtmlGenerator htmlGenerator;
    StabilityListParser stabilityListParser;

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}