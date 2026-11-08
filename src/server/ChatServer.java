package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer {

    public static void main(String[] args) {

        int port = 5000;

        // One shared UserManager for all clients
        UserManager userManager = new UserManager();

        // Start the file upload server in a separate thread
        FileUploadServer fileUploadServer = new FileUploadServer();

        Thread uploadServerThread = new Thread(
        () -> fileUploadServer.start()
        );
        
        uploadServerThread.start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("Chat Server started!");
            System.out.println("Waiting for clients...");

            while (true) {

                Socket clientSocket = serverSocket.accept();

                System.out.println(
                        "New connection: "
                        + clientSocket.getRemoteSocketAddress()
                );

                // Create handler for the connected client
                ClientHandler clientHandler =
                        new ClientHandler(clientSocket, userManager);

                // Start a separate thread
                Thread clientThread = new Thread(clientHandler);

                clientThread.start();
            }

        } catch (IOException e) {

            System.out.println("Server error: " + e.getMessage());
        }
    }
}