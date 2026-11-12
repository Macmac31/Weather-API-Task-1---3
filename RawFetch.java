
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;

public class RawFetch {

    public static void main(String[] args) {

        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=7.07&longitude=125.61&current_weather=true";

        HttpURLConnection conn = null;

        try {

            URL url = URI.create(endpoint).toURL();

            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");

            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();

            System.out.println("HTTP status: " + status);

            InputStream stream;

            if (status >= 200 && status < 300) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
            }

            if (stream != null) {

                try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
            }

        } catch (IOException e) {

            System.out.println("Error: " + e.getMessage());

        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
