package org.example;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Clase mejorada del sistema de sincronización con verificación de propiedad, tiempo de vida y copias de seguridad.
 */
public class SincronizadorFTP {

    private File syncedDir;
    private FTPClient ftpClient;
    ScheduledExecutorService service;

    private static final String METADATA_FILE = "metadata.txt";
    private static final String LIFETIME_METADATA_FILE = "lifetime_metadata.txt";
    private static final String BACKUP_DIR_NAME = "D:\\FTP2";

    private Map<String, String> fileOwners = new HashMap<>();
    private Map<String, Long> fileLifetimes = new HashMap<>();

    /**
     *
     * @throws IOException Si el directorio no es válido o no es posible realizar conexión
     */
    public SincronizadorFTP(File syncedDir, String ftpServer, int ftpPort, String ftpUser, String ftpPassword) throws IOException {
        if (syncedDir == null || !syncedDir.exists() || !syncedDir.isDirectory()) {
            throw new IOException("Invalid directory name, could not sync");
        }
        this.syncedDir = syncedDir;
        fptConnect(ftpServer, ftpPort, ftpUser, ftpPassword);
        loadMetadata();
        loadLifetimeMetadata();
    }

    private void fptConnect(String server, int port, String user, String password) throws IOException {
        ftpClient = new FTPClient();
        ftpClient.connect(server, port);
        int replyCode = ftpClient.getReplyCode();

        if (!FTPReply.isPositiveCompletion(replyCode)) {
            ftpClient.disconnect();
            throw new IOException("Unable to establish connection");
        }
        if (!ftpClient.login(user, password)) {
            throw new IOException("FTP login failed");
        }

        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
    }

    public void startSync(int interval) {
        Logger.logMessage("Connection established");
        service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(() -> mainLoop(), interval, interval, TimeUnit.SECONDS);
    }

    public void stopSync(){
        service.shutdown();
    }

    private void mainLoop() {
        List<String> localFiles = new ArrayList<>();
        localFiles.clear();
        analyzeLocalDir(syncedDir, localFiles);
        cleanRemoteDir("/", localFiles);

        try {
            if (!ftpClient.sendNoOp()) {
                stopSync();
                Logger.logError("Connection lost");
                System.err.println("Connection lost");
            }
        } catch (IOException e) {
            stopSync();
            Logger.logError("Connection lost (" + e.getMessage() + ")");
            System.err.println("Connection lost");
        }
    }

    private void analyzeLocalDir(File dir, List<String> localFiles) {
        File[] children = dir.listFiles();
        for (File child : children) {
            localFiles.add(toFtpPath(child));
            if (child.isDirectory()) {
                analyzeLocalDir(child, localFiles);
            } else {
                try {
                    if (!existsOnFtp(child)) {
                        upload(child, "user", 0);
                    }
                } catch (IOException e) {
                    Logger.logError("Unable to upload " + child + "(" + e.getMessage() + ")");
                }
            }
        }
    }

    private void cleanRemoteDir(String parent, List<String> localFiles) {
        try {
            ftpClient.changeWorkingDirectory(parent);
            FTPFile[] ftpFiles = ftpClient.listFiles();
            if (parent.equals("/")) {
                parent = "";
            }
            long currentTime = System.currentTimeMillis();
            for (FTPFile ftpFile : ftpFiles) {
                String ftpFilePath = parent + "/" + ftpFile.getName();
                boolean isDir = ftpFile.isDirectory();
                if (isDir) {
                    ftpFilePath += "/";
                }
                if (fileLifetimes.containsKey(ftpFilePath) && fileLifetimes.get(ftpFilePath) < currentTime) {
                    ftpClient.changeWorkingDirectory("/");
                    if (!isDir && ftpClient.deleteFile(ftpFilePath)) {
                        Logger.logMessage("Expired file " + ftpFilePath + " deleted");
                        fileLifetimes.remove(ftpFilePath);
                        saveLifetimeMetadata();
                    }
                    if (isDir) {
                        cleanRemoteDir(parent + "/" + ftpFile.getName(), localFiles);
                        ftpClient.changeWorkingDirectory("/");
                        ftpClient.removeDirectory(ftpFilePath);
                    }
                } else if (!localFiles.contains(ftpFilePath) && !fileOwners.getOrDefault(ftpFilePath, "").equals("user")) {
                    ftpClient.changeWorkingDirectory("/");
                    if (!isDir && ftpClient.deleteFile(ftpFilePath)) {
                        Logger.logMessage("Remote file " + ftpFilePath + " deleted");
                    }
                    if (isDir) {
                        cleanRemoteDir(parent + "/" + ftpFile.getName(), localFiles);
                        ftpClient.changeWorkingDirectory("/");
                        ftpClient.removeDirectory(ftpFilePath);
                    }
                } else if (isDir) {
                    cleanRemoteDir(parent + "/" + ftpFile.getName(), localFiles);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            Logger.logError("Unable to clean remote directory (" + e.getMessage() + ")");
        }
    }

    private boolean existsOnFtp(File file) throws IOException {
        String remotePath = toFtpPath(file);
        ftpClient.changeWorkingDirectory("/");
        FTPFile[] ftpFiles = ftpClient.listFiles(remotePath);
        if (ftpFiles.length == 0)
            return false;
        assert ftpFiles.length == 1;

        String localLastModified = timeStampToString(file.lastModified());
        String serverLastModified = ftpClient.getModificationTime(remotePath).substring(0, 14);
        return localLastModified.equals(serverLastModified);
    }

    private String timeStampToString(long timestamp) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date(timestamp));
    }

    public void upload(File localFile, String owner, long lifetime) throws IOException {
        Logger.logMessage("Uploading " + localFile);
        String ftpPath = toFtpPath(localFile);

        backupFile(localFile);

        String ftpPathParents = ftpPath.substring(0, ftpPath.lastIndexOf('/'));
        ftpCreateDirectoryTree(ftpPathParents);

        ftpClient.changeWorkingDirectory("/");
        InputStream is = new FileInputStream(localFile);
        ftpClient.storeFile(ftpPath, is);
        is.close();

        String ftpDate = timeStampToString(localFile.lastModified());
        ftpClient.setModificationTime(ftpPath, ftpDate);

        fileOwners.put(ftpPath, owner);
        fileLifetimes.put(ftpPath, System.currentTimeMillis() + lifetime);
        saveMetadata();
        saveLifetimeMetadata();
    }

    private String toFtpPath(File localFile) {
        return "/" + syncedDir
                .toURI()
                .relativize(localFile.toURI())
                .getPath()
                .replace('\\', '/');
    }

    private void ftpCreateDirectoryTree(String dirTree) throws IOException {
        boolean dirExists = true;
        String[] directories = dirTree.split("/");
        for (String dir : directories) {
            if (!dir.isEmpty()) {
                if (dirExists) {
                    dirExists = ftpClient.changeWorkingDirectory(dir);
                }
                if (!dirExists) {
                    if (!ftpClient.makeDirectory(dir)) {
                        throw new IOException("Unable to create remote directory '" + dir + "'.  error='" + ftpClient.getReplyString() + "'");
                    }
                    if (!ftpClient.changeWorkingDirectory(dir)) {
                        throw new IOException("Unable to change into newly created remote directory '" + dir + "'.  error='" + ftpClient.getReplyString() + "'");
                    }
                }
            }
        }
    }

    private void loadMetadata() throws IOException {
        File metadataFile = new File(syncedDir, METADATA_FILE);
        if (metadataFile.exists()) {
            List<String> lines = Files.readAllLines(metadataFile.toPath());
            for (String line : lines) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    fileOwners.put(parts[0], parts[1]);
                }
            }
        }
    }

    private void saveMetadata() throws IOException {
        File metadataFile = new File(syncedDir, METADATA_FILE);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : fileOwners.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        Files.write(metadataFile.toPath(), lines);
    }

    private void loadLifetimeMetadata() throws IOException {
        File metadataFile = new File(syncedDir, LIFETIME_METADATA_FILE);
        if (metadataFile.exists()) {
            List<String> lines = Files.readAllLines(metadataFile.toPath());
            for (String line : lines) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    fileLifetimes.put(parts[0], Long.parseLong(parts[1]));
                }
            }
        }
    }

    private void saveLifetimeMetadata() throws IOException {
        File metadataFile = new File(syncedDir, LIFETIME_METADATA_FILE);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> entry : fileLifetimes.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        Files.write(metadataFile.toPath(), lines);
    }

    private void backupFile(File localFile) throws IOException {
        File backupDir = new File(BACKUP_DIR_NAME);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        File backupFile = new File(backupDir, localFile.getName());
        Files.copy(localFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    public void deleteFile(String filePath, boolean isImage) throws IOException {
        // Eliminar archivo localmente
        File localFile = new File(syncedDir.getPath() + File.separator + filePath);
        if (localFile.exists()) {
            Files.delete(localFile.toPath());
            Logger.logMessage("Local " + (isImage ? "image" : "file") + " " + filePath + " deleted");
        } else {
            Logger.logError("Local " + (isImage ? "image" : "file") + " " + filePath + " not found");
        }

        // Eliminar archivo en el servidor FTP
        if (ftpClient.deleteFile(filePath)) {
            Logger.logMessage("Remote " + (isImage ? "image" : "file") + " " + filePath + " deleted");
            fileOwners.remove(filePath);
            fileLifetimes.remove(filePath);
            saveMetadata();
            saveLifetimeMetadata();
        } else {
            Logger.logError("Unable to delete remote " + (isImage ? "image" : "file") + " " + filePath);
        }
    }

}
