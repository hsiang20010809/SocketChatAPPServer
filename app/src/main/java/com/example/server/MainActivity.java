package com.example.server;

import android.os.Bundle;
import android.os.StrictMode;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ServerSocket serverSocket;
    private Set<PrintWriter> clientWriters = new HashSet<>();
    private Map<PrintWriter, String> clientNames = new HashMap<>();
    private boolean isServerRunning = false;
    private String serverIp;

    private EditText etServerName, etMessage;
    private Button btnConnect, btnDisconnect, btnSend;
    private TextView tvChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Allow network operations on the main thread for simplicity
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        etServerName = findViewById(R.id.etServerName);
        etMessage = findViewById(R.id.etMessage);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnSend = findViewById(R.id.btnSend);
        tvChat = findViewById(R.id.tvChat);

        btnConnect.setOnClickListener(v -> startServer());
        btnDisconnect.setOnClickListener(v -> disconnectServer());
        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void startServer() {
        if (isServerRunning) return; // Server is already running

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(7100); // Port number
                serverIp = getLocalIpAddress(); // Get server IP address
                isServerRunning = true;
                String serverStartMessage = "Server started at IP: " + serverIp + " on port 7100\n";

                runOnUiThread(() -> {
                    tvChat.append(serverStartMessage);
                    btnSend.setEnabled(true);
                    btnDisconnect.setEnabled(true);
                });

                while (isServerRunning) {
                    try {
                        Socket socket = serverSocket.accept();
                        new ClientHandler(socket).start();
                    } catch (IOException e) {
                        if (isServerRunning) { // Log only if server is running
                            e.printStackTrace();
                            runOnUiThread(() -> tvChat.append("Error accepting client connection\n"));
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> tvChat.append("Error starting server\n"));
            }
        }).start();
    }

    private void disconnectServer() {
        if (!isServerRunning) return; // Server is not running

        new Thread(() -> {
            try {
                isServerRunning = false;
                if (serverSocket != null) {
                    serverSocket.close();
                }
                synchronized (clientWriters) {
                    for (PrintWriter writer : clientWriters) {
                        writer.println("Server is shutting down...");
                        writer.println("Server shut down.");
                        writer.flush(); // Ensure the message is sent
                    }
                }
                runOnUiThread(() -> {
                    tvChat.append("Server disconnected.\n");
                    btnSend.setEnabled(false);
                    btnDisconnect.setEnabled(false);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }



    private void sendMessage() {
        String message = etMessage.getText().toString();
        String serverName = etServerName.getText().toString();
        String fullMessage = serverName + " (" + serverIp + "): " + message;

        synchronized (clientWriters) {
            Iterator<PrintWriter> iterator = clientWriters.iterator();
            while (iterator.hasNext()) {
                PrintWriter writer = iterator.next();
                if (writer.checkError()) {
                    iterator.remove(); // Remove if the writer has an error
                } else {
                    writer.println(fullMessage);
                    writer.flush();
                }
            }
        }
        tvChat.append(fullMessage + "\n");
        etMessage.setText("");
    }
    private static final long HEARTBEAT_INTERVAL = 5000; // 5 seconds
    private static final long HEARTBEAT_TIMEOUT = 10000; // 10 seconds

    private void startHeartbeat() {
        new Thread(() -> {
            while (isServerRunning) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    synchronized (clientWriters) {
                        Iterator<PrintWriter> iterator = clientWriters.iterator();
                        while (iterator.hasNext()) {
                            PrintWriter writer = iterator.next();
                            if (writer.checkError()) {
                                iterator.remove();
                                continue;
                            }
                            writer.println("HEARTBEAT");
                            writer.flush();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }
    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return "Unavailable";
    }

    private class ClientHandler extends Thread {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String clientName;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                // Ask the client for their name
                out.println("Please enter your name:");
                clientName = in.readLine();
                if (clientName == null || clientName.trim().isEmpty()) {
                    clientName = "Anonymous";
                }

                synchronized (clientWriters) {
                    clientWriters.add(out);
                    clientNames.put(out, clientName);
                }

                // Log client join
                System.out.println(clientName + " has joined the chat.");
                runOnUiThread(() -> tvChat.append(clientName + " has joined the chat.\n"));

                // Notify all clients
                sendToAllClients(clientName + " has joined the chat.");

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.startsWith("DISCONNECT")) {
                        // Client requested to disconnect
                        handleDisconnect(message);
                        break;
                    }
                    final String msg = clientName + ": " + message;
                    runOnUiThread(() -> tvChat.append(msg + "\n"));
                    sendToAllClients(msg);
                }
            } catch (IOException e) {
                // Handle exceptions, which could occur if client disconnects abruptly
                if (isServerRunning) {
                    runOnUiThread(() -> tvChat.append("Client disconnected: " + (clientName != null ? clientName : "Unknown") + "\n"));
                }
                System.out.println("Client disconnected: " + (clientName != null ? clientName : "Unknown"));
            } finally {
                // Clean up resources and notify remaining clients
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                synchronized (clientWriters) {
                    clientWriters.remove(out);
                    clientNames.remove(out);
                }

                // Log client leave and notify all clients
                if (clientName != null) {
                    System.out.println(clientName + " has left the chat.");
                    sendToAllClients(clientName + " has left the chat.");
                }
            }
        }

        private void handleDisconnect(String message) {
            // Extract client name from the message
            String disconnectClientName = message.substring("DISCONNECT ".length());
            System.out.println(disconnectClientName + " has left the chat.");
            runOnUiThread(() -> tvChat.append(disconnectClientName + " has left the chat.\n"));

            // Notify all clients
            sendToAllClients(disconnectClientName + " has left the chat.");
        }
    }




    private void sendToAllClients(String message) {
        synchronized (clientWriters) {
            Iterator<PrintWriter> iterator = clientWriters.iterator();
            while (iterator.hasNext()) {
                PrintWriter writer = iterator.next();
                if (writer.checkError()) {
                    iterator.remove(); // Remove if the writer has an error
                } else {
                    writer.println(message);
                    writer.flush();
                }
            }
        }
    }
}