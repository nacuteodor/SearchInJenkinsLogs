import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Provides text-file, directory-filtering, and GZIP helpers for local build backups.
 * Compression methods use GZIP streams even when destination names end in {@code .zip}.
 *
 * Created by Teo on 9/3/2016.
 */
public class FileHelper {
    private static final IOFileFilter ALL_FILES_FILTER = new FileHelper().getFilesFilter("");

    /**
    * Recursively GZIP-compresses matching text files into the destination directory.
    * Output names use the source basename plus {@code .zip} and are deleted on JVM exit.
     *
    * @param fromPath source directory; a missing directory produces no output
    * @param toPath destination directory for compressed files
    * @param fileFilter substring required in each source filename
     * @return the number of files zipped.
     * @throws IOException from accessing a file.
     */
    public int zipFilesFromPath(final File fromPath, final File toPath, final String fileFilter) throws IOException {
        System.out.println("Zip files which contain \""
                + fileFilter
                + "\" from path: "
                + fromPath.getAbsolutePath()
                + " to path: "
                + toPath.getAbsolutePath());
        if (!fromPath.exists()) {
            return 0;
        }
        Collection<File> fileList = FileUtils.listFiles(fromPath, getFilesFilter(fileFilter), ALL_FILES_FILTER);
        for (File file : fileList) {
            File zipFile = new File(toPath, file.getName() + ".zip");
            FileUtils.forceDeleteOnExit(zipFile);
            zipFile(file, zipFile);
        }
        return fileList.size();
    }

    /**
     * Get the filenames list from directoryName directory, which contains the filter string
     *
    * @param directoryName directory to list
    * @param filter substring required in each returned entry name
     * @param recursive if it should search recursive in directoryName.
    * @return matching basenames, or an empty array when the directory cannot be listed
     */
    public String[] getDirFilesList(String directoryName, final String filter, boolean recursive) {
        File dir = new File(directoryName);
        FilenameFilter filesFilter = new FilenameFilter() {

            /** Matches child names containing the configured substring. */
            @Override
            public boolean accept(File dir, String name) {
                return name.contains(filter);
            }
        };

        String[] filterList = dir.list(filesFilter);
        // avoid returning null if no files are found
        if (filterList == null) {
            return new String[0];
        }
        if (recursive) {
            String[] fileList = dir.list();
            if (fileList == null) {
                return filterList;
            }
            for (String file : fileList) {
                String fileItem = dir.getPath() + File.separator + file;
                if (new File(fileItem).isDirectory()) {
                    filterList = (String[]) ArrayUtils.addAll(filterList, getDirFilesList(fileItem, filter, true));
                }
            }
        }
        return filterList;
    }

    /**
     * Write data to a file with appending.
     *
    * @param file destination text file, created if absent
    * @param data text to append; nonempty input is followed by a platform newline
     * @throws IOException from writing data to a file.
     */
    public void appendDataToFile(File file, String data) throws IOException {
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(file, true));
            bw.write(data.toString());
            if (!data.isEmpty()) {
                bw.newLine();
            }
        } finally {
            if (bw != null) {
                bw.close();
            }
        }
    }

    /**
    * Appends source text to a destination line by line using the platform charset.
     *
    * @param fromfile source text file
    * @param toFile destination text file; existing contents are retained
     * @throws IOException from reading/writing data to a file.
     */
    public void copyFromFileToFile(File fromfile, File toFile) throws IOException {
        String line = null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(fromfile));
            appendDataToFile(toFile, ""); // create empty file.
            while ((line = br.readLine()) != null) {
                appendDataToFile(toFile, line);
            }
        } finally {
            IOUtils.closeQuietly(br);
        }
    }

    /**
    * Appends a GZIP member containing the source text to the destination file.
    * Text is read and written line by line using the platform default charset.
     *
    * @param file source text file
    * @param toFile destination GZIP stream, despite its possible {@code .zip} extension
    * @throws IOException if the source or destination cannot be accessed
     */
    public void zipFile(File file, File toFile) throws IOException {
        String line = null;
        BufferedReader br = null;
        BufferedWriter bw = null;
        try {
            br = new BufferedReader(new FileReader(file));
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(new FileOutputStream(toFile, true));
            bw = new BufferedWriter(new OutputStreamWriter(gzipOutputStream));
            bw.write(""); // create empty zip file.
            while ((line = br.readLine()) != null) {
                bw.write(line);
                bw.newLine();
            }
        } finally {
            IOUtils.closeQuietly(br);
            IOUtils.closeQuietly(bw);
        }
    }

    /**
    * Creates an empty GZIP stream, replacing any existing destination contents.
     *
    * @param file destination compressed file
    * @throws IOException if the destination cannot be written
     */
    public void newZipEmptyFile(File file) throws IOException {
        BufferedWriter bw = null;
        try {
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(new FileOutputStream(file, false));
            bw = new BufferedWriter(new OutputStreamWriter(gzipOutputStream));
            bw.write(""); // create empty zip file.
        } finally {
            IOUtils.closeQuietly(bw);
        }
    }

    /**
    * Decompresses a GZIP text stream and appends its lines to the destination.
     *
    * @param file source GZIP stream
    * @param toFile destination text file; existing contents are retained
    * @throws IOException if decompression, reading, or writing fails
     */
    public void unzipFile(File file, File toFile) throws IOException {
        String line = null;
        BufferedReader br = null;
        try {
            GZIPInputStream gzipInputStream = new GZIPInputStream(new FileInputStream(file));
            br = new BufferedReader(new InputStreamReader(gzipInputStream));
            while ((line = br.readLine()) != null) {
                appendDataToFile(toFile, line);
            }
        } catch (IOException e) {
            throw new IOException("Exception when unzipping file: " + file.getAbsolutePath() + " to file:  " + toFile.getAbsolutePath(), e);
        } finally {
            IOUtils.closeQuietly(br);
        }
    }

    /**
     * Get a IOFileFilter instance which filter files containing filter value.
     *
    * @param filter substring required by the single-file acceptance method
    * @return a filter for the backup directory traversal
     */
    public IOFileFilter getFilesFilter(final String filter) {
        IOFileFilter fileFilter = new IOFileFilter() {

            /** Matches when the directory's name contains the supplied child name. */
            @Override
            public boolean accept(File dir, String name) {
                return dir.getName().contains(name);
            }

            /** Matches file names containing the configured substring. */
            @Override
            public boolean accept(File file) {
                return file.getName().contains(filter);
            }
        };

        return fileFilter;
    }

    /**
     * Create a File from filePath which should be deleted in application exit.
     *
    * @param filePath directory path to create
    * @return the directory registered for deletion on JVM exit
     * @throws IOException from deleting on exit or creating the directories from filePath.
     */
    public File createTemporaryDirFile(String filePath) throws IOException {
        File file = new File(filePath);
        FileUtils.forceDeleteOnExit(file);
        FileUtils.forceMkdir(file);
        return file;
    }
}