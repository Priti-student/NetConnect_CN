package common;

import java.io.FileInputStream;
import java.security.MessageDigest;

public class FileUtils {

    public static String calculateSHA256(String filePath)
            throws Exception {

        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        try (FileInputStream fis = new FileInputStream(filePath)) {

            byte[] buffer = new byte[4096];

            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {

                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = digest.digest();

        StringBuilder hexString = new StringBuilder();

        for (byte b : hashBytes) {

            hexString.append(
                    String.format("%02x", b & 0xff)
            );
        }

        return hexString.toString();
    }
}