// WeatherApp.java - Task 2: parse JSON with Gson
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class WeatherApp {

    static String fetch(String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP status " + status);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    public static void main(String[] args) {
        String endpoint = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=7.07&longitude=125.61&current_weather=true";
        try {
            JsonObject root = JsonParser.parseString(fetch(endpoint)).getAsJsonObject();
            JsonObject cw = root.getAsJsonObject("current_weather");
            JsonObject units = root.getAsJsonObject("current_weather_units");

            double lat = root.get("latitude").getAsDouble();
            double lon = root.get("longitude").getAsDouble();
            String time = cw.get("time").getAsString();
            double temp = cw.get("temperature").getAsDouble();
            double wind = cw.get("windspeed").getAsDouble();
            int dir = cw.get("winddirection").getAsInt();
            String tUnit = units.get("temperature").getAsString();
            String wUnit = units.get("windspeed").getAsString();

            System.out.println("=== Current Weather ===");
            System.out.println(String.format(Locale.US, "Location : %.2f, %.2f", lat, lon));
            System.out.println("Time     : " + time);
            System.out.println(String.format(Locale.US, "Temp     : %.1f %s", temp, tUnit));
            System.out.println(String.format(Locale.US, "Wind     : %.1f %s at %d deg", wind, wUnit, dir));
            // weathercode is also available: cw.get("weathercode").getAsInt()
        } catch (IOException e) {
            System.out.println("Network problem: " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("Unexpected JSON structure: " + e.getMessage());
        }
    }
}
