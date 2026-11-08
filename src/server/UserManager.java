
package server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserManager {

    private final ConcurrentHashMap<String, ClientHandler> clients =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, FileTransferRequest>
            pendingFileRequests = new ConcurrentHashMap<>();

    private static final long REQUEST_TTL =
            5 * 60 * 1000L;

    private String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public boolean addClient(String username, ClientHandler client) {

        String key = normalize(username);

        ClientHandler existing = clients.putIfAbsent(key, client);

        if (existing == null) {
            System.out.println(
                    "Total connected clients: " + clients.size()
            );
            return true;
        }

        return false;
    }

    public void removeClient(String username, ClientHandler client) {

        if (username == null) {
            return;
        }

        String normalizedUsername = normalize(username);

        boolean removed = clients.remove(normalizedUsername, client);

        if (!removed) {
            return;
        }

        System.out.println(
                "Total connected clients: " + clients.size()
        );

        for (Map.Entry<String, FileTransferRequest> entry
                : pendingFileRequests.entrySet()) {

            FileTransferRequest request = entry.getValue();

            boolean senderDisconnected =
                    request.sender == client;

            boolean recipientDisconnected =
                    normalize(request.recipientUsername)
                            .equals(normalizedUsername);

            if (senderDisconnected || recipientDisconnected) {

                if (pendingFileRequests.remove(
                        entry.getKey(), request)) {

                    if (senderDisconnected) {

                        ClientHandler recipient = clients.get(
                                normalize(request.recipientUsername)
                        );

                        if (recipient != null) {
                            recipient.sendMessage(
                                    "[FILE CANCELLED] "
                                    + request.transferId
                                    + " Sender disconnected."
                            );
                        }

                    } else {

                        request.sender.sendMessage(
                                "[FILE CANCELLED] "
                                + request.transferId
                                + " Recipient disconnected."
                        );
                    }
                }
            }
        }
    }

    public void broadcast(String message, ClientHandler sender) {

        for (ClientHandler client : clients.values()) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }

    public List<String> getOnlineUsers() {

        List<String> usernames = new ArrayList<>();

        for (ClientHandler client : clients.values()) {
            usernames.add(client.getUsername());
        }

        Collections.sort(
                usernames,
                String.CASE_INSENSITIVE_ORDER
        );

        return usernames;
    }

    public boolean sendPrivateMessage(
            String recipientUsername,
            String message) {

        ClientHandler recipient =
                clients.get(normalize(recipientUsername));

        if (recipient == null) {
            return false;
        }

        recipient.sendMessage(message);
        return true;
    }

    // Create a file-transfer request using a unique ID.
    public boolean sendFileRequest(
            String senderUsername,
            String recipientUsername,
            String transferId,
            String fileName,
            ClientHandler sender) {

        removeExpiredRequests();

        ClientHandler recipient =
                clients.get(normalize(recipientUsername));

        if (recipient == null) {
            return false;
        }

        FileTransferRequest request =
                new FileTransferRequest(
                        transferId,
                        fileName,
                        senderUsername,
                        recipientUsername,
                        sender
                );

        if (pendingFileRequests.putIfAbsent(
                transferId, request) != null) {
            return false;
        }

        recipient.sendMessage(
                "[FILE REQUEST] "
                + transferId + " "
                + senderUsername
                + " wants to send you: "
                + fileName
        );

        recipient.sendMessage(
                "Use /acceptfile " + transferId
                + " or /rejectfile " + transferId
        );

        return true;
    }

    public boolean acceptFileRequest(
            String recipientUsername,
            String transferId) {

        removeExpiredRequests();

        ClientHandler recipient =
                clients.get(normalize(recipientUsername));

        if (recipient == null || recipient.getP2PPort() == -1) {
            return false;
        }

        FileTransferRequest request =
                pendingFileRequests.get(transferId);

        if (request == null
                || !normalize(request.recipientUsername)
                        .equals(normalize(recipientUsername))) {
            return false;
        }

        // Ensure the sender is still connected.
        if (clients.get(normalize(request.senderUsername))
                != request.sender) {

            pendingFileRequests.remove(transferId, request);
            return false;
        }

        if (!pendingFileRequests.remove(transferId, request)) {
            return false;
        }

        request.sender.sendMessage(
                "[P2P_ACCEPTED] "
                + transferId + " "
                + recipientUsername + " "
                + recipient.getClientIP() + " "
                + recipient.getP2PPort() + " "
                + request.fileName
        );

        return true;
    }

    public boolean rejectFileRequest(
            String recipientUsername,
            String transferId) {

        removeExpiredRequests();

        FileTransferRequest request =
                pendingFileRequests.get(transferId);

        if (request == null
                || !normalize(request.recipientUsername)
                        .equals(normalize(recipientUsername))) {
            return false;
        }

        if (!pendingFileRequests.remove(transferId, request)) {
            return false;
        }

        request.sender.sendMessage(
                "[FILE REJECTED] "
                + transferId + " "
                + recipientUsername
                + " rejected your file: "
                + request.fileName
        );

        return true;
    }

    // Remove requests that have remained unanswered for five minutes.
    private void removeExpiredRequests() {

        long now = System.currentTimeMillis();

        for (Map.Entry<String, FileTransferRequest> entry
                : pendingFileRequests.entrySet()) {

            FileTransferRequest request = entry.getValue();

            if (now - request.createdAt >= REQUEST_TTL) {

                if (pendingFileRequests.remove(
                        entry.getKey(), request)) {

                    request.sender.sendMessage(
                            "[FILE CANCELLED] "
                            + request.transferId
                            + " File request expired."
                    );

                    ClientHandler recipient = clients.get(
                            normalize(request.recipientUsername)
                    );

                    if (recipient != null) {
                        recipient.sendMessage(
                                "[FILE REQUEST EXPIRED] "
                                + request.transferId
                        );
                    }
                }
            }
        }
    }

    private static class FileTransferRequest {

        private final String transferId;
        private final String fileName;
        private final String senderUsername;
        private final String recipientUsername;
        private final ClientHandler sender;
        private final long createdAt;

        public FileTransferRequest(
                String transferId,
                String fileName,
                String senderUsername,
                String recipientUsername,
                ClientHandler sender) {

            this.transferId = transferId;
            this.fileName = fileName;
            this.senderUsername = senderUsername;
            this.recipientUsername = recipientUsername;
            this.sender = sender;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
