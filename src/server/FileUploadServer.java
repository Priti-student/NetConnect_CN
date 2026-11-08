package server;

import common.FileUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.net.ServerSocket;
import java.net.Socket;

public class FileUploadServer {

    private static final int UPLOAD_PORT = 5001;

    private static final long MAX_FILE_SIZE =
            100L * 1024 * 1024;

    private static final Path UPLOAD_DIRECTORY =
            Paths.get("uploads").toAbsolutePath().normalize();

    public void start() {

        try (ServerSocket serverSocket =
                     new ServerSocket(UPLOAD_PORT)) {

            System.out.println(
                    "File Transfer Server started on port "
                    + UPLOAD_PORT
            );

            while (true) {

                Socket socket = serverSocket.accept();

                Thread transferThread = new Thread(
                        () -> handleConnection(socket)
                );

                transferThread.start();
            }

        } catch (IOException e) {

            System.out.println(
                    "File server error: " + e.getMessage()
            );
        }
    }

    // Handle all file-transfer commands
    private void handleConnection(Socket socket) {

        try (
            Socket clientSocket = socket;

            DataInputStream in = new DataInputStream(
                    clientSocket.getInputStream());

            DataOutputStream out = new DataOutputStream(
                    clientSocket.getOutputStream())
        ) {

            String command = in.readUTF();

            switch (command) {

                case "UPLOAD":
                    handleUpload(in, out);
                    break;

                case "DOWNLOAD":
                    handleDownload(in, out);
                    break;

                case "LIST":
                    handleList(out);
                    break;

                default:
                    out.writeUTF("ERROR: Invalid command.");
            }

        } catch (IOException e) {

            System.out.println(
                    "File transfer error: " + e.getMessage()
            );
        }
    }

    // Handle file upload
    private void handleUpload(
            DataInputStream in,
            DataOutputStream out) throws IOException {

        Path tempFile = null;

        try {

            String fileName = in.readUTF();

            long fileSize = in.readLong();

            String expectedHash = in.readUTF();

            if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
                out.writeUTF("ERROR: Invalid file size.");
                return;
            }

            if (!expectedHash.matches("[a-fA-F0-9]{64}")) {
                out.writeUTF("ERROR: Invalid SHA-256 hash.");
                return;
            }

            String safeFileName = getSafeFileName(fileName);

            if (safeFileName == null) {
                out.writeUTF("ERROR: Invalid filename.");
                return;
            }

            Files.createDirectories(UPLOAD_DIRECTORY);

            tempFile = Files.createTempFile(
                    UPLOAD_DIRECTORY,
                    "upload-",
                    ".tmp"
            );

            try (var fileOut = Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[4096];

                long remaining = fileSize;

                long received = 0;

                while (remaining > 0) {

                    int bytesToRead = (int) Math.min(
                            buffer.length, remaining);

                    int bytesRead = in.read(
                            buffer, 0, bytesToRead);

                    if (bytesRead == -1) {
                        throw new IOException(
                                "File transfer interrupted."
                        );
                    }

                    fileOut.write(buffer, 0, bytesRead);

                    remaining -= bytesRead;
                    received += bytesRead;

                    int progress = fileSize == 0
                            ? 100
                            : (int) (received * 100 / fileSize);

                    System.out.println(
                            "Receiving " + safeFileName
                            + ": " + progress + "%"
                    );
                }
            }

            String actualHash =
                    FileUtils.calculateSHA256(tempFile.toString());

            if (!actualHash.equalsIgnoreCase(expectedHash)) {

                out.writeUTF(
                        "ERROR: SHA-256 verification failed."
                );

                return;
            }

            Path destination =
                    UPLOAD_DIRECTORY.resolve(safeFileName);

            Files.move(
                    tempFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );

            tempFile = null;

            System.out.println(
                    "File uploaded and verified: " + safeFileName
            );

            out.writeUTF(
                    "SUCCESS: File uploaded and verified."
            );

        } catch (Exception e) {

            out.writeUTF("ERROR: " + e.getMessage());

        } finally {

            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    // Handle file download
    private void handleDownload(
            DataInputStream in,
            DataOutputStream out) throws IOException {

        String requestedFile = in.readUTF();

        String safeFileName = getSafeFileName(requestedFile);

        if (safeFileName == null) {
            out.writeUTF("ERROR: Invalid filename.");
            return;
        }

        Path filePath =
                UPLOAD_DIRECTORY.resolve(safeFileName).normalize();

        if (!filePath.startsWith(UPLOAD_DIRECTORY)
                || !Files.isRegularFile(filePath)) {

            out.writeUTF("ERROR: File not found on server.");
            return;
        }

        long fileSize = Files.size(filePath);

        if (fileSize > MAX_FILE_SIZE) {
            out.writeUTF("ERROR: File exceeds download size limit.");
            return;
        }

        try {

            // Calculate hash of the original server file
            String hash =
                    FileUtils.calculateSHA256(filePath.toString());

            // Send metadata
            out.writeUTF("SUCCESS");
            out.writeLong(fileSize);
            out.writeUTF(hash);

            // Send file bytes
            try (var fileIn = Files.newInputStream(filePath)) {

                byte[] buffer = new byte[4096];

                int bytesRead;

                long sent = 0;

                while ((bytesRead = fileIn.read(buffer)) != -1) {

                    out.write(buffer, 0, bytesRead);

                    sent += bytesRead;

                    int progress = fileSize == 0
                            ? 100
                            : (int) (sent * 100 / fileSize);

                    System.out.println(
                            "Sending " + safeFileName
                            + ": " + progress + "%"
                    );
                }

                out.flush();
            }

            System.out.println(
                    "Download completed: " + safeFileName
            );

        } catch (Exception e) {

            System.out.println(
                    "Download failed: " + e.getMessage()
            );
        }
    }

    // Handle /files command
    private void handleList(DataOutputStream out)
            throws IOException {

        Files.createDirectories(UPLOAD_DIRECTORY);

        try (Stream<Path> stream = Files.list(UPLOAD_DIRECTORY)) {

            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(".tmp"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();

            out.writeUTF("SUCCESS");

            out.writeInt(files.size());

            for (String file : files) {
                out.writeUTF(file);
            }

            out.flush();
        }
    }

    // Extract and validate a safe filename
    private String getSafeFileName(String fileName) {

        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        // Handle both Windows and Unix path separators
        String normalized = fileName.replace('\\', '/');

        String safeName = normalized.substring(
                normalized.lastIndexOf('/') + 1
        );

        if (safeName.isBlank()
                || safeName.equals(".")
                || safeName.equals("..")) {

            return null;
        }

        return safeName;
    }
}