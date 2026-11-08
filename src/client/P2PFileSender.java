
package client;

import common.FileUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public class P2PFileSender {

    private static final long MAX_FILE_SIZE =
            100L * 1024 * 1024;

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 60_000;

    public static void sendFile(
            String ipAddress,
            int port,
            String transferId,
            Path path) {

        try {

            if (!Files.isRegularFile(path)
                    || !Files.isReadable(path)) {
                throw new IOException("File is unavailable.");
            }

            long fileSize = Files.size(path);

            if (fileSize > MAX_FILE_SIZE) {
                throw new IOException(
                        "File exceeds the 100 MB limit."
                );
            }

            String fileName = path.getFileName().toString();

            String fileHash =
                    FileUtils.calculateSHA256(path.toString());

            try (Socket socket = new Socket()) {

                socket.connect(
                        new InetSocketAddress(ipAddress, port),
                        CONNECT_TIMEOUT
                );

                socket.setSoTimeout(READ_TIMEOUT);

                try (
                    DataInputStream in =
                            new DataInputStream(
                                    socket.getInputStream());

                    DataOutputStream out =
                            new DataOutputStream(
                                    socket.getOutputStream())
                ) {

                    System.out.println(
                            "Connected to recipient."
                    );

                    String response = in.readUTF();

                    if (!response.equals("P2P_READY")) {
                        throw new IOException(
                                "Recipient is not ready: " + response
                        );
                    }

                    // Send transfer metadata.
                    out.writeUTF("P2P_FILE");
                    out.writeUTF(transferId);
                    out.writeUTF(fileName);
                    out.writeLong(fileSize);
                    out.writeUTF(fileHash);
                    out.flush();

                    response = in.readUTF();

                    if (!response.equals("READY_TO_RECEIVE")) {
                        throw new IOException(
                                "Receiver rejected transfer: " + response
                        );
                    }

                    System.out.println(
                            "Sending: " + fileName
                    );

                    byte[] buffer = new byte[8192];

                    long sent = 0;
                    long remaining = fileSize;

                    int lastProgress = -1;

                    try (InputStream fileIn =
                            Files.newInputStream(path)) {

                        while (remaining > 0) {

                            int bytesToRead = (int) Math.min(
                                    buffer.length,
                                    remaining
                            );

                            int bytesRead = fileIn.read(
                                    buffer, 0, bytesToRead
                            );

                            if (bytesRead == -1) {
                                throw new IOException(
                                        "File changed or ended unexpectedly."
                                );
                            }

                            out.write(buffer, 0, bytesRead);

                            sent += bytesRead;
                            remaining -= bytesRead;

                            int progress = (int) (
                                    sent * 100 / fileSize
                            );

                            if (progress != lastProgress) {
                                System.out.print(
                                        "\rTransfer progress: "
                                        + progress + "%"
                                );

                                lastProgress = progress;
                            }
                        }
                    }

                    out.flush();

                    System.out.println();

                    // Receive final status.
                    response = in.readUTF();

                    if (response.equals("SUCCESS")) {
                        System.out.println(
                                "File transferred successfully!"
                        );
                        System.out.println(
                                "SHA-256 verification completed by receiver."
                        );
                        System.out.print(
                                "\nYou:"
                        );
                    } else {
                        throw new IOException(
                                "Transfer failed: " + response
                        );
                    }
                }
            }

        } catch (Exception e) {

            System.out.println(
                    "P2P sending error: " + e.getMessage()
            );
        }
    }
}
