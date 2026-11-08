package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private Socket clientSocket;
    private UserManager userManager;
    private volatile int p2pPort = -1;

    private BufferedReader in;
    private PrintWriter out;

    private String username;

    private boolean registered = false;

    // Constructor
    public ClientHandler(Socket socket, UserManager userManager) {

        this.clientSocket = socket;
        this.userManager = userManager;
    }

    public void registerP2PPort(int port) {
        if (port < 1024 || port > 65535) {
            sendMessage("Invalid P2P port.");
            return;
        }

        this.p2pPort = port;

        sendMessage("P2P port registered: " + port);
    }

    public int getP2PPort() {
        return p2pPort;
    }

    // Return this client's username
    public String getUsername() {
        return username;
    }

    public String getClientIP() {
        return clientSocket.getInetAddress().getHostAddress();
    }
    // Send a message to this particular client
    public synchronized void sendMessage(String message) {

        if (out != null) {
            out.println(message);
        }
    }

    @Override
    public void run() {

        try {

            in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            out = new PrintWriter(
                    clientSocket.getOutputStream(), true);

            // Ask client for username
            while (true) {

                username = in.readLine();

                if (username == null) {
                    return;
                }

                username = username.trim();

                if (username.isEmpty()) {
                    out.println("USERNAME_INVALID");
                    continue;
                }

                // Try registering the username
                if (userManager.addClient(username, this)) {

                    registered = true;

                    out.println("USERNAME_ACCEPTED");

                    break;

                } else {

                    out.println("USERNAME_TAKEN");
                }
            }

            System.out.println(username + " joined the chat.");

            // Notify other clients
            userManager.broadcast(
                    username + " joined the chat!",
                    this
            );

            // Continuously receive messages
            String message;

            while ((message = in.readLine()) != null) {

                if (message.equalsIgnoreCase("/quit")) {
                    break;
                }

                // Process commands
                if (message.equalsIgnoreCase("/users")) {

                    handleUsersCommand();

                } else if (message.equalsIgnoreCase("/help")) {

                    handleHelpCommand();

                } else if (message.toLowerCase().startsWith("/msg ")) {

                    handlePrivateMessage(message);

                } else if (message.startsWith("/sendfile ")) {

                    String[] parts = message.split("\\s+", 4);

                    if (parts.length < 4
                            || parts[1].isBlank()
                            || parts[2].isBlank()
                            || parts[3].isBlank()) {
                            
                        sendMessage(
                                "Usage: /sendfile <username> <transferId> <filename>"
                        );
                        continue;
                    }
                
                    String recipientUsername = parts[1];
                    String transferId = parts[2];
                    String fileName = parts[3];
                
                    if (recipientUsername.equalsIgnoreCase(username)) {
                        sendMessage("You cannot send a file to yourself.");
                        continue;
                    }
                
                    if (!transferId.matches("[a-fA-F0-9-]{36}")) {
                        sendMessage("Invalid transfer ID.");
                        continue;
                    }
                
                    boolean sent = userManager.sendFileRequest(
                            username,
                            recipientUsername,
                            transferId,
                            fileName,
                            this
                    );
                
                    if (sent) {
                        sendMessage(
                                "File transfer request sent to "
                                + recipientUsername
                        );
                    } else {
                        sendMessage(
                                "Unable to send file request. "
                                + "User may be offline or request ID already exists."
                        );
                    } 
                } else if (message.startsWith("/acceptfile ")) {

                    String[] parts = message.split("\\s+");

                    if (parts.length != 2) {
                        sendMessage("Usage: /acceptfile <transferId>");
                        continue;
                    }
                
                    boolean accepted = userManager.acceptFileRequest(
                            username,
                            parts[1]
                    );
                
                    if (accepted) {
                        sendMessage("File request accepted.");
                    } else {
                        sendMessage(
                                "Unable to accept request. "
                                + "It may have expired or been cancelled."
                        );
                    }
                } else if (message.startsWith("/rejectfile ")) {

                    String[] parts = message.split("\\s+");

                    if (parts.length != 2) {
                        sendMessage("Usage: /rejectfile <transferId>");
                        continue;
                    }
                
                    boolean rejected = userManager.rejectFileRequest(
                            username,
                            parts[1]
                    );
                
                    if (rejected) {
                        sendMessage("File request rejected.");
                    } else {
                        sendMessage("No pending request with that transfer ID.");
                    }
                } else if (message.startsWith("/registerp2p ")) {

                    String[] parts = message.split("\\s+");
                                
                    if (parts.length != 2) {
                        sendMessage("Usage: /registerp2p <port>");
                        continue;
                    }
                
                    try {
                        int port = Integer.parseInt(parts[1]);
                        registerP2PPort(port);
                    
                    } catch (NumberFormatException e) {
                        sendMessage("Invalid port number.");
                    }
                } else if (message.startsWith("/")) {

                    sendMessage("Unknown command. Type /help for commands.");

                } else {

                    // Normal group message
                    String formattedMessage =
                            username + ": " + message;

                    System.out.println(formattedMessage);

                    userManager.broadcast(formattedMessage, this);
                }
            }

        } catch (IOException e) {

            System.out.println(username + " disconnected unexpectedly.");

        } finally {

            // Remove client only if registration succeeded
            if (registered) {

                userManager.removeClient(username, this);

                userManager.broadcast(
                        username + " left the chat!",
                        this
                );
            }

            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("Error closing client socket.");
            }

            System.out.println(username + " connection closed.");
        }
    }

    // Handle /users command
    private void handleUsersCommand() {

        sendMessage("----- ONLINE USERS -----");

        for (String user : userManager.getOnlineUsers()) {
            sendMessage("- " + user);
        }

        sendMessage("------------------------");
    }

    // Handle /help command
    private void handleHelpCommand() {

        sendMessage("----- AVAILABLE COMMANDS -----");
        sendMessage("/users - Show online users");
        sendMessage("/msg username message - Send private message");
        sendMessage("/help - Show available commands");
        sendMessage("/quit - Leave the chat");
        sendMessage("/upload filepath - Upload a file to server");
        sendMessage("/files - List server-stored files");
        sendMessage("/download filename - Download a file");
        sendMessage("/connect IP PORT - Establish a direct P2P connection");
        sendMessage("/sendfile username \"filepath\" - Send a file directly");
        sendMessage("/acceptfile transferId - Accept a file request");
        sendMessage("/rejectfile transferId - Reject a file request");
        sendMessage("------------------------------");
    }

    // Handle /msg command
    private void handlePrivateMessage(String message) {

        // Split into command, recipient and message
        String[] parts = message.split("\\s+", 3);

        if (parts.length < 3 || parts[2].trim().isEmpty()) {

            sendMessage("Usage: /msg username message");

            return;
        }

        String recipient = parts[1];

        String privateMessage = parts[2];

        if (recipient.equalsIgnoreCase(username)) {

            sendMessage("You cannot send a private message to yourself.");

            return;
        }

        String formattedMessage =
                "[Private] " + username + ": " + privateMessage;

        boolean sent = userManager.sendPrivateMessage(
                recipient,
                formattedMessage
        );

        if (sent) {

            sendMessage("[Private message sent to " + recipient + "]");

        } else {

            sendMessage("User '" + recipient + "' is not online.");
        }
    }
}