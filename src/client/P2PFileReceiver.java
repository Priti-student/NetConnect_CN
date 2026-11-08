
package client;

import common.FileUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class P2PFileReceiver implements Runnable {

    private final int port;
    private final String username;

    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private volatile IOException startupError;

    private final CountDownLatch startupLatch =
            new CountDownLatch(1);

    private static final long MAX_FILE_SIZE =
            100L * 1024 * 1024;

    private static final int READ_TIMEOUT = 60_000;

    public P2PFileReceiver(int port, String username) {
        this.port = port;
        this.username = username;
    }

    // Start the receiver and wait until its port is listening.
    public void start() throws IOException {

        Thread thread = new Thread(this, "P2P-Receiver");
        thread.start();

        try {

            boolean started = startupLatch.await(
                    5, TimeUnit.SECONDS
            );

            if (!started) {
                stop();
                throw new IOException(
                        "P2P receiver startup timed out."
                );
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            stop();

            throw new IOException(
                    "Interrupted while starting P2P receiver.", e
            );
        }

        if (startupError != null) {
            throw startupError;
        }
    }

    @Override
    public void run() {

        try {

            serverSocket = new ServerSocket(port);

            startupLatch.countDown();

            System.out.println(
                    "P2P Receiver listening on port " + port
            );

            while (running) {

                Socket socket = serverSocket.accept();

                Thread handler = new Thread(
                        () -> handleConnection(socket),
                        "P2P-Transfer"
                );

                handler.start();
            }

        } catch (IOException e) {

            if (running) {
                startupError = e;

                System.out.println(
                        "P2P Receiver error: " + e.getMessage()
                );
            }

        } finally {

            startupLatch.countDown();
        }
    }

    // Stop the receiver and unblock accept().
    public void stop() {

        running = false;

        if (serverSocket != null) {

            try {
                serverSocket.close();
            } catch (IOException e) {
                System.out.println(
                        "Error closing P2P receiver: "
                        + e.getMessage()
                );
            }
        }
    }

    private void handleConnection(Socket socket) {

        Path tempFile = null;

        try (socket) {

            socket.setSoTimeout(READ_TIMEOUT);

            DataInputStream in =
                    new DataInputStream(socket.getInputStream());

            DataOutputStream out =
                    new DataOutputStream(socket.getOutputStream());

            System.out.println(
                    "Incoming P2P connection from: "
                    + socket.getInetAddress().getHostAddress()
            );

            out.writeUTF("P2P_READY");
            out.flush();

            String command = in.readUTF();

            if (!command.equals("P2P_FILE")) {
                out.writeUTF("ERROR: Invalid transfer command");
                out.flush();
                return;
            }

            String transferId = in.readUTF();
            String fileName = in.readUTF();
            long fileSize = in.readLong();
            String expectedHash = in.readUTF();

            // Validate transfer ID.
            if (!transferId.matches("[a-fA-F0-9-]{36}")) {
                out.writeUTF("ERROR: Invalid transfer ID");
                out.flush();
                return;
            }

            // Validate filename.
            if (fileName == null
                    || fileName.isBlank()
                    || fileName.equals(".")
                    || fileName.equals("..")
                    || fileName.contains("/")
                    || fileName.contains("\\")
                    || fileName.contains("\n")
                    || fileName.contains("\r")) {

                out.writeUTF("ERROR: Invalid filename");
                out.flush();
                return;
            }

            if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
                out.writeUTF("ERROR: Invalid file size");
                out.flush();
                return;
            }

            if (!expectedHash.matches("[a-fA-F0-9]{64}")) {
                out.writeUTF("ERROR: Invalid SHA-256 hash");
                out.flush();
                return;
            }

            Path downloadDirectory =
                    Paths.get("downloads", username)
                            .toAbsolutePath()
                            .normalize();

            Files.createDirectories(downloadDirectory);

            out.writeUTF("READY_TO_RECEIVE");
            out.flush();

            tempFile = Files.createTempFile(
                    downloadDirectory,
                    ".p2p-",
                    ".part"
            );

            long received = 0;

            byte[] buffer = new byte[8192];

            try (var fileOut = Files.newOutputStream(tempFile)) {

                while (received < fileSize) {

                    int bytesToRead = (int) Math.min(
                            buffer.length,
                            fileSize - received
                    );

                    int bytesRead = in.read(
                            buffer, 0, bytesToRead
                    );

                    if (bytesRead == -1) {
                        throw new IOException(
                                "Connection closed before transfer completed."
                        );
                    }

                    fileOut.write(buffer, 0, bytesRead);
                    received += bytesRead;
                }
            }

            // Verify SHA-256.
            String actualHash =
                    FileUtils.calculateSHA256(tempFile.toString());

            if (!actualHash.equalsIgnoreCase(expectedHash)) {

                out.writeUTF(
                        "ERROR: SHA-256 verification failed"
                );
                out.flush();

                System.out.println(
                        "File verification failed: " + fileName
                );

                return;
            }

            // Move to a unique destination.
            Path destination = moveToUniqueDestination(
                    tempFile,
                    downloadDirectory,
                    fileName
            );

            tempFile = null;

            out.writeUTF("SUCCESS");
            out.flush();

            System.out.println(
                    "File received successfully: " + destination
            );
            System.out.print(
                    "\nYou:"
            );

        } catch (IOException e) {

            System.out.println(
                    "P2P transfer error: " + e.getMessage()
            );

        } catch (Exception e) {

            System.out.println(
                    "P2P processing error: " + e.getMessage()
            );

        } finally {

            if (tempFile != null) {

                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    System.out.println(
                            "Could not delete temporary file: "
                            + e.getMessage()
                    );
                }
            }
        }
    }

    // Reserve a unique destination before moving the received file.
    private Path moveToUniqueDestination(
            Path tempFile,
            Path directory,
            String fileName) throws IOException {

        int counter = 0;

        while (true) {

            String candidateName;

            if (counter == 0) {

                candidateName = fileName;

            } else {

                int dotIndex = fileName.lastIndexOf('.');

                String baseName;
                String extension;

                if (dotIndex > 0) {
                    baseName = fileName.substring(0, dotIndex);
                    extension = fileName.substring(dotIndex);
                } else {
                    baseName = fileName;
                    extension = "";
                }

                candidateName =
                        baseName + " (" + counter + ")" + extension;
            }

            Path destination =
                    directory.resolve(candidateName).normalize();

            try {

                // Reserve the filename to prevent concurrent collisions.
                Files.createFile(destination);

                try {

                    try {
                        Files.move(
                                tempFile,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING
                        );

                    } catch (AtomicMoveNotSupportedException e) {

                        Files.move(
                                tempFile,
                                destination,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }

                    return destination;

                } catch (IOException e) {

                    Files.deleteIfExists(destination);
                    throw e;
                }

            } catch (FileAlreadyExistsException e) {

                counter++;
            }
        }
    }
}
