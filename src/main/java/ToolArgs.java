import java.io.File;
import java.util.Set;

/**
 * Created by Teo on 9/3/2016.
 */
public class ToolArgs {
    String jobUrl;
    String newUrlPrefix;
    Boolean searchInJUnitReports;
    String threadPoolSizeString;
    Integer threadPoolSize;
    Set<Integer> builds;
    Integer lastBuildsCount;
    String artifactsFilters;
    String searchedText;
    Boolean groupTestsFailures;
    Double diffThreshold;
    Boolean backupJob;
    Boolean useBackup;
    Boolean removeBackup;
    String backupPath;
    File backupJobDirFile;
}