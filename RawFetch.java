import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

public class RawFetch {
    public static void main(String[] args) {
        String endpoint = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=7.07&longitude=125.61&current_weather=true";
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            System.out.println("HTTP status: " + status);

            InputStream stream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (stream != null) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }

                // Print the reply with line breaks and indentation
                try {
                    System.out.println(new GsonBuilder().setPrettyPrinting().create()
                            .toJson(JsonParser.parseString(body.toString())));
                } catch (RuntimeException e) {
                    // Not valid JSON: show the raw text instead
                    System.out.println(body);
                }
            }
        } catch (IOException e) {
            System.out.println("Request failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}