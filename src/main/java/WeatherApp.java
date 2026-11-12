
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WeatherApp {

    // Get-Content .\src\main\java\WeatherApp.java | Select-Object -First 25Fetch JSON response from the API
    static String fetch(String endpoint) throws IOException {

        URL url = URI.create(endpoint).toURL();

        HttpURLConnection conn =
            (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try {
            int status = conn.getResponseCode();

            System.out.println("HTTP status: " + status);

            InputStream stream;

            if (status >= 200 && status < 300) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
            }

            if (stream == null) {
                throw new IOException("No response received.");
            }

            StringBuilder response = new StringBuilder();

            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            if (status < 200 || status >= 300) {
                throw new IOException("API request failed: HTTP " + status);
            }

            return response.toString();

        } finally {
            conn.disconnect();
        }
    }

    public static void main(String[] args) {

        String endpoint =
            "https://api.open-meteo.com/v1/forecast?latitude=7.07&longitude=125.61&current_weather=true";

        try {

            // 1. Fetch the JSON response
            String json = fetch(endpoint);

            // 2. Parse JSON into a JsonObject
            JsonObject data =
                JsonParser.parseString(json).getAsJsonObject();

            // 3. Extract the nested current_weather object
            JsonObject weather =
                data.getAsJsonObject("current_weather");

            // 4. Extract the required weather values
            double temperature =
                weather.get("temperature").getAsDouble();

            double windspeed =
                weather.get("windspeed").getAsDouble();

            double winddirection =
                weather.get("winddirection").getAsDouble();

            int weathercode =
                weather.get("weathercode").getAsInt();

            String time =
                weather.get("time").getAsString();

            // 5. Display formatted weather report
            System.out.println("\n--- Current Weather ---");

            System.out.printf("Location : %.2f, %.2f%n",
                data.get("latitude").getAsDouble(),
                data.get("longitude").getAsDouble());

            System.out.println("Time     : " + time);

            System.out.printf("Temp     : %.1f °C%n",
                temperature);

            System.out.printf("Wind     : %.1f km/h at %.0f deg%n",
                windspeed, winddirection);

            System.out.println("Weather code: " + weathercode);

        } catch (IOException e) {

            System.out.println("Connection error: " + e.getMessage());

        } catch (Exception e) {

            System.out.println("Unable to process weather data: "
                + e.getMessage());
        }
    }
}
