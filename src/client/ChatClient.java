package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import common.FileUtils;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatClient {

    private static final ConcurrentHashMap<String, Path>
        PENDING_FILE_PATHS = new ConcurrentHashMap<>();

    private static final long MAX_FILE_SIZE =
        100L * 1024 * 1024;
    public static void main(String[] args) {

        int p2pPort = args.length > 0
        ? Integer.parseInt(args[0])
        : 6001;

        
        String host = "127.0.0.1";
        int port = 5000;
        P2PFileReceiver p2pReceiver = null;

        try (
            Socket socket = new Socket(host, port);

            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));

            PrintWriter out = new PrintWriter(
                socket.getOutputStream(), true);
            
            Scanner scanner = new Scanner(System.in)
        ){
            
            System.out.println("Connected to NetConnect Server!");

            // Username registration
            String username;

            while (true) {

                System.out.print("Enter your username: ");

                username = scanner.nextLine().trim();

                if (username.isEmpty()) {
                    System.out.println("Username cannot be empty.");
                    continue;
                }

                // Send username to server
                out.println(username);

                // Receive server's registration response
                String response = in.readLine();

                if (response == null) {
                    System.out.println("Server disconnected.");
                    return;
                }

                if (response.equals("USERNAME_ACCEPTED")) {

                    System.out.println("Username accepted!");

                    break;

                } else if (response.equals("USERNAME_TAKEN")) {

                    System.out.println(
                        "This username is already taken. Try another."
                    );

                } else if (response.equals("USERNAME_INVALID")) {

                    System.out.println("Invalid username. Try again.");
                }
            }

            p2pReceiver = new P2PFileReceiver(p2pPort, username);
            p2pReceiver.start();

            out.println("/registerp2p " + p2pPort);

            System.out.println("\nWelcome to NetConnect, " + username + "!");
            System.out.println("Type /help to see available commands.\n");

            // Separate thread for receiving messages
            Thread receiverThread = new Thread(() -> {

                try {

                    String message;

                    while ((message = in.readLine()) != null) {

                        handleServerMessage(message);
                        System.out.print("\nYou: ");
                    }

                    System.out.println("\nServer disconnected.");

                } catch (IOException e) {

                    System.out.println("\nConnection lost.");
                }
            });

            receiverThread.setDaemon(true);
            receiverThread.start();

            // Main thread handles user input
            while (true) {

                System.out.print("You: ");
                        
                String message = scanner.nextLine();
                        
                if (message.equalsIgnoreCase("/quit")) {
                
                    out.println(message);
                
                    System.out.println("Leaving NetConnect...");
                
                    break;
                
                } else if (message.equalsIgnoreCase("/files")) {
                
                    listFiles();
                
                } else if (message.toLowerCase().startsWith("/download ")) {
                
                    String fileName = message.substring(10).trim();
                
                    downloadFile(fileName);
                
                } else if (message.toLowerCase().startsWith("/upload ")) {
                
                    String filePath = message.substring(8).trim();
                
                    uploadFile(filePath);
                
                }  else if (message.toLowerCase().startsWith("/sendfile ")) {

                    handleSendFileCommand(message, out);

                }else if (message.startsWith("/connect ")) {

                    String[] parts = message.split("\\s+");

                    if (parts.length != 3) {
                        System.out.println("Usage: /connect <IP_ADDRESS> <PORT>");
                        continue;
                    }
                
                    try {
                        String ipAddress = parts[1];
                        int peerPort = Integer.parseInt(parts[2]);
                    
                        connectToPeer(ipAddress, peerPort);
                    
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid port number.");
                    }
                } else if (message.toLowerCase().startsWith("/sendfile ")) {

                    String[] parts = message.split("\\s+", 3);

                    if (parts.length < 3 || parts[2].isBlank()) {
                        System.out.println(
                            "Usage: /sendfile <username> <filename>"
                        );
                        continue;
                    }
                
                    String fileName = parts[2];
                
                    // Prevent directory traversal and path separators.
                    if (fileName.contains("/") || fileName.contains("\\")) {
                        System.out.println("Please provide only the filename.");
                        continue;
                    }
                
                    Path uploadsDirectory = Paths.get("uploads")
                            .toAbsolutePath()
                            .normalize();
                
                    Path filePath = uploadsDirectory.resolve(fileName).normalize();
                
                    if (!filePath.startsWith(uploadsDirectory)
                            || !Files.isRegularFile(filePath)) {
                            
                        System.out.println("File not found: " + fileName);
                        continue;
                    }
                
                    // File exists. Forward the original command to the server.
                    out.println(message);
                
                } else {

                    out.println(message);
                }
            }

        } catch (IOException e) {

            System.out.println("Client error: " + e.getMessage());
        } finally {

            if (p2pReceiver != null) {
                p2pReceiver.stop();
            }
        }
    }

    
    private static void handleSendFileCommand(
            String message,
            PrintWriter out) {

        try {

            String arguments =
                    message.substring("/sendfile".length()).trim();

            int separator = arguments.indexOf(' ');

            if (separator == -1) {
                System.out.println(
                        "Usage: /sendfile username \"full file path\""
                );
                return;
            }

            String recipient = arguments.substring(0, separator).trim();

            String pathText =
                    arguments.substring(separator).trim();

            if (recipient.isBlank() || pathText.isBlank()) {
                System.out.println(
                        "Usage: /sendfile username \"full file path\""
                );
                return;
            }

            // Remove surrounding double quotes, if supplied.
            if (pathText.startsWith("\"") && pathText.endsWith("\"")
                    && pathText.length() >= 2) {

                pathText = pathText.substring(
                        1, pathText.length() - 1
                );

            } else if (pathText.startsWith("\"")
                    || pathText.endsWith("\"")) {

                System.out.println("Invalid quotation marks in file path.");
                return;
            }

            Path path = Paths.get(pathText)
                    .toAbsolutePath()
                    .normalize();

            if (!Files.isRegularFile(path)
                    || !Files.isReadable(path)) {

                System.out.println("File not found or not readable: " + path);
                return;
            }

            long fileSize = Files.size(path);

            if (fileSize > MAX_FILE_SIZE) {
                System.out.println("Maximum file size is 100 MB.");
                return;
            }

            String fileName = path.getFileName().toString();

            if (fileName.contains("\n") || fileName.contains("\r")) {
                System.out.println("Invalid filename.");
                return;
            }

            // Generate a unique ID for this transfer.
            String transferId = UUID.randomUUID().toString();

            // Save the actual local path.
            PENDING_FILE_PATHS.put(transferId, path);

            // Send only the ID and filename to the server.
            out.println(
                    "/sendfile "
                    + recipient + " "
                    + transferId + " "
                    + fileName
            );

            if (out.checkError()) {
                PENDING_FILE_PATHS.remove(transferId);
                System.out.println("Unable to send request to server.");
                return;
            }

            System.out.println(
                    "File validated: " + fileName
            );

        } catch (Exception e) {

            System.out.println(
                    "Unable to prepare file transfer: " + e.getMessage()
            );
        }
    }


    
    private static void handleServerMessage(String message) {
    
        // A recipient accepted the request.
        if (message.startsWith("[P2P_ACCEPTED] ")) {
        
            String[] parts = message.split("\\s+", 6);
        
            if (parts.length != 6) {
                System.out.println("Invalid P2P acceptance message.");
                return;
            }
        
            String transferId = parts[1];
            String recipient = parts[2];
            String ipAddress = parts[3];
            String fileName = parts[5];
        
            int peerPort;
        
            try {
                peerPort = Integer.parseInt(parts[4]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid recipient port.");
                PENDING_FILE_PATHS.remove(transferId);
                return;
            }
        
            Path filePath = PENDING_FILE_PATHS.get(transferId);
        
            if (filePath == null) {
                System.out.println(
                        "Original local file path not found for transfer "
                        + transferId
                );
                return;
            }
        
            System.out.println(
                    recipient + " accepted your request for " + fileName
            );
        
            Thread senderThread = new Thread(() -> {
            
                try {
                
                    P2PFileSender.sendFile(
                            ipAddress,
                            peerPort,
                            transferId,
                            filePath
                    );
                
                } finally {
                
                    PENDING_FILE_PATHS.remove(
                            transferId, filePath
                    );
                }
            
            }, "P2P-Sender-" + transferId);
        
            senderThread.start();
        
            return;
        }
    
        // Remove local path mappings after rejection or cancellation.
        if (message.startsWith("[FILE REJECTED] ")
                || message.startsWith("[FILE CANCELLED] ")) {
                
            String[] parts = message.split("\\s+", 3);
                
            if (parts.length >= 2) {
                PENDING_FILE_PATHS.remove(parts[1]);
            }
        
            System.out.println(message);
            return;
        }
    
        System.out.println(message);
    }

    private static void uploadFile(String filePath) {

        Path path = Paths.get(filePath);

        if (!Files.exists(path) || !Files.isRegularFile(path)) {

            System.out.println("File not found: " + filePath);
            return;
        }

        try {

            String fileName = path.getFileName().toString();

            long fileSize = Files.size(path);

            // Calculate original file hash
            String hash = FileUtils.calculateSHA256(filePath);

            try (
                Socket socket = new Socket("127.0.0.1", 5001);

                DataOutputStream out = new DataOutputStream(
                        socket.getOutputStream());

                DataInputStream in = new DataInputStream(
                        socket.getInputStream());

                var fileIn = Files.newInputStream(path)
            ) {

                out.writeUTF("UPLOAD");
                out.writeUTF(fileName);
                out.writeLong(fileSize);

                // Send original SHA-256 hash
                out.writeUTF(hash);

                byte[] buffer = new byte[4096];

                int bytesRead;

                long sent = 0;

                int lastProgress = -1;

                System.out.println("\nUploading: " + fileName);

                while ((bytesRead = fileIn.read(buffer)) != -1) {

                    out.write(buffer, 0, bytesRead);

                    sent += bytesRead;

                    int progress = fileSize == 0
                            ? 100
                            : (int) (sent * 100 / fileSize);

                    if (progress != lastProgress) {

                        System.out.print(
                                "\rProgress: " + progress + "%"
                        );

                        lastProgress = progress;
                    }
                }

                out.flush();

                System.out.println();

                String response = in.readUTF();

                System.out.println("Server: " + response);

                if (response.startsWith("SUCCESS")) {

                    System.out.println(
                            "Original SHA-256: " + hash
                    );

                    System.out.println(
                            "File integrity verified!"
                    );
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "File upload failed: " + e.getMessage()
            );
        }
    }
    private static void listFiles() {

        try (
            Socket socket = new Socket("127.0.0.1", 5001);
        
            DataOutputStream out = new DataOutputStream(
                    socket.getOutputStream());
            
            DataInputStream in = new DataInputStream(
                    socket.getInputStream())
        ) {
        
            // Request file list
            out.writeUTF("LIST");
        
            String response = in.readUTF();
        
            if (!response.equals("SUCCESS")) {
                System.out.println(response);
                return;
            }
        
            int count = in.readInt();
        
            System.out.println("\n----- SERVER FILES -----");
        
            if (count == 0) {
            
                System.out.println("No files available.");
            
            } else {
            
                for (int i = 0; i < count; i++) {
                
                    System.out.println((i + 1) + ". " + in.readUTF());
                }
            }
        
            System.out.println("------------------------");
        
        } catch (IOException e) {
        
            System.out.println(
                    "Unable to retrieve file list: " + e.getMessage()
            );
        }
    }

    private static void downloadFile(String fileName) {

        if (fileName.isBlank()) {
            System.out.println("Usage: /download filename");
            return;
        }

        Path tempFile = null;

        try (
            Socket socket = new Socket("127.0.0.1", 5001);

            DataOutputStream out = new DataOutputStream(
                    socket.getOutputStream());

            DataInputStream in = new DataInputStream(
                    socket.getInputStream())
        ) {

            // Request download
            out.writeUTF("DOWNLOAD");
            out.writeUTF(fileName);
            out.flush();

            // Receive server response
            String response = in.readUTF();

            if (!response.equals("SUCCESS")) {
                System.out.println("Server: " + response);
                return;
            }

            // Receive metadata
            long fileSize = in.readLong();

            String expectedHash = in.readUTF();

            if (fileSize < 0 || fileSize > 100L * 1024 * 1024) {
                System.out.println("Invalid file size.");
                return;
            }

            if (!expectedHash.matches("[a-fA-F0-9]{64}")) {
                System.out.println("Invalid SHA-256 hash.");
                return;
            }

            // Extract safe filename
            String safeFileName = fileName.replace('\\', '/');

            safeFileName = safeFileName.substring(
                    safeFileName.lastIndexOf('/') + 1
            );

            if (safeFileName.isBlank()
                    || safeFileName.equals(".")
                    || safeFileName.equals("..")) {

                System.out.println("Invalid filename.");
                return;
            }

            // Create downloads directory
            Path downloadDirectory = Paths.get("downloads")
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(downloadDirectory);

            Path destination =
                    downloadDirectory.resolve(safeFileName).normalize();

            if (!destination.startsWith(downloadDirectory)) {
                System.out.println("Invalid download path.");
                return;
            }

            // Create temporary file
            tempFile = Files.createTempFile(
                    downloadDirectory,
                    "download-",
                    ".tmp"
            );

            System.out.println("\nDownloading: " + safeFileName);

            // Receive file bytes
            try (var fileOut = Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[4096];

                long received = 0;

                while (received < fileSize) {

                    int bytesToRead = (int) Math.min(
                            buffer.length,
                            fileSize - received
                    );

                    int bytesRead = in.read(
                            buffer, 0, bytesToRead);

                    if (bytesRead == -1) {
                        throw new IOException(
                                "Download interrupted."
                        );
                    }

                    fileOut.write(buffer, 0, bytesRead);

                    received += bytesRead;

                    int progress = fileSize == 0
                            ? 100
                            : (int) (received * 100 / fileSize);

                    System.out.print(
                            "\rProgress: " + progress + "%"
                    );
                }
            }

            System.out.println();

            // Verify downloaded file
            String actualHash =
                    FileUtils.calculateSHA256(tempFile.toString());

            if (!actualHash.equalsIgnoreCase(expectedHash)) {

                throw new IOException(
                        "SHA-256 verification failed!"
                );
            }

            // Save only after successful verification
            Files.move(
                    tempFile,
                    destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            tempFile = null;

            System.out.println("SHA-256 verification successful!");

            System.out.println(
                    "File downloaded successfully to: " + destination
            );

        } catch (Exception e) {

            System.out.println(
                    "Download failed: " + e.getMessage()
            );

        } finally {

            if (tempFile != null) {

                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    System.out.println(
                            "Could not delete temporary file."
                    );
                }
            }
        }
    }

    private static void connectToPeer(String ipAddress, int port) {

        try (Socket socket = new Socket(ipAddress, port)) {

            System.out.println("Connected to peer!");

            DataInputStream in =
                    new DataInputStream(socket.getInputStream());

            String response = in.readUTF();

            if (response.equals("P2P_READY")) {
                System.out.println("Peer is ready for file transfer.");
            }

        } catch (IOException e) {
            System.out.println("P2P connection failed: " + e.getMessage());
        }
    }
}

