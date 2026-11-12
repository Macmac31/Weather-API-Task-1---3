import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BonusLookup {

    static class WeatherException extends Exception {
        WeatherException(String message) {
            super(message);
        }
    }

    record Place(String name, String region, String country, double lat, double lon) {
        String label() {
            StringBuilder sb = new StringBuilder(name);
            if (!region.isEmpty()) sb.append(", ").append(region);
            if (!country.isEmpty()) sb.append(", ").append(country);
            return sb.toString();
        }
    }

    static final String ARCHIVE = "https://archive-api.open-meteo.com/v1/archive";
    static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    static final int MAX_DAYS_AHEAD = 16;
    static final int MAX_RANGE_DAYS = 31;
    static final int MAX_CHOICES = 5;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Convert weather codes into readable descriptions.
    static String describe(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 71, 73, 75 -> "Snow";
            case 80, 81, 82 -> "Rain showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Weather code " + code;
        };
    }

    // Send an HTTP GET request and return the body if the status is 200.
    static String get(String url) throws WeatherException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new WeatherException(
                        "API request failed. HTTP status: " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("Request was interrupted.");
        } catch (WeatherException e) {
            throw e;
        } catch (Exception e) {
            throw new WeatherException("Unable to contact the weather service.");
        }
    }

    static String text(JsonObject o, String key, String fallback) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : fallback;
    }

    // Challenge 1 (part A): return every match (up to 5), filtered by country if one was typed.
    static List<Place> findPlaces(String input) throws WeatherException {
        String[] parts = input.split(",", 2);
        String placeName = parts[0].trim();
        String requestedCountry = parts.length > 1 ? parts[1].trim() : "";

        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(placeName, StandardCharsets.UTF_8)
                + "&count=10&language=en&format=json";

        JsonObject root;
        try {
            root = JsonParser.parseString(get(url)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new WeatherException("Could not read the place search reply.");
        }
        if (!root.has("results")) {
            throw new WeatherException("Place not found: " + placeName);
        }

        List<Place> matches = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("results")) {
            JsonObject r = element.getAsJsonObject();
            if (!r.has("latitude") || !r.has("longitude")) continue;
            String country = text(r, "country", "");
            String code = text(r, "country_code", "");
            if (!requestedCountry.isEmpty()
                    && !country.equalsIgnoreCase(requestedCountry)
                    && !code.equalsIgnoreCase(requestedCountry)) {
                continue;
            }
            matches.add(new Place(text(r, "name", placeName), text(r, "admin1", ""), country,
                    r.get("latitude").getAsDouble(), r.get("longitude").getAsDouble()));
            if (matches.size() == MAX_CHOICES) break;
        }
        if (matches.isEmpty()) {
            throw new WeatherException("Place not found in country: " + requestedCountry);
        }
        return matches;
    }

    // Challenge 1 (part B): let the user pick when there is more than one match.
    static Place choosePlace(List<Place> matches, Scanner in) {
        if (matches.size() == 1) {
            return matches.get(0);
        }
        System.out.println("\nSeveral places match. Choose one:");
        for (int i = 0; i < matches.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + matches.get(i).label());
        }
        while (true) {
            System.out.print("Enter number (1-" + matches.size() + ", or 0 to cancel): ");
            if (!in.hasNextLine()) return null;
            String answer = in.nextLine().trim();
            try {
                int n = Integer.parseInt(answer);
                if (n == 0) return null;
                if (n >= 1 && n <= matches.size()) return matches.get(n - 1);
            } catch (NumberFormatException e) {
                // fall through to the message below
            }
            System.out.println("Please type a number from the list.");
        }
    }

    static void validateRange(LocalDate start, LocalDate end) throws WeatherException {
        if (end.isBefore(start)) {
            throw new WeatherException("The end date must not be before the start date.");
        }
        if (end.isAfter(LocalDate.now().plusDays(MAX_DAYS_AHEAD))) {
            throw new WeatherException("That date is too far ahead. Forecasts are available up to "
                    + MAX_DAYS_AHEAD + " days from today (until "
                    + LocalDate.now().plusDays(MAX_DAYS_AHEAD) + ").");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new WeatherException("Please choose at most " + MAX_RANGE_DAYS + " days at a time.");
        }
    }

    static Double number(JsonObject daily, String key, int i) {
        if (!daily.has(key) || !daily.get(key).isJsonArray()) return null;
        JsonArray a = daily.getAsJsonArray(key);
        if (i >= a.size() || a.get(i).isJsonNull()) return null;
        return a.get(i).getAsDouble();
    }

    // Fetch one block of days from one service and add one line per day to 'lines'.
    static void fetchDays(Place place, LocalDate from, LocalDate to, String base, List<String> lines)
            throws WeatherException {
        String url = base
                + String.format(Locale.US, "?latitude=%.4f&longitude=%.4f", place.lat(), place.lon())
                + "&start_date=" + from + "&end_date=" + to
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max"
                + "&timezone=auto";
        JsonObject root;
        try {
            root = JsonParser.parseString(get(url)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new WeatherException("Could not read the weather reply.");
        }
        if (!root.has("daily") || !root.get("daily").isJsonObject()) {
            throw new WeatherException("Weather data is unavailable for those dates.");
        }
        JsonObject daily = root.getAsJsonObject("daily");
        if (!daily.has("time") || !daily.get("time").isJsonArray()) {
            throw new WeatherException("Weather data is unavailable for those dates.");
        }
        JsonArray times = daily.getAsJsonArray("time");
        for (int i = 0; i < times.size(); i++) {
            String day = times.get(i).getAsString();
            Double max = number(daily, "temperature_2m_max", i);
            Double min = number(daily, "temperature_2m_min", i);
            Double wind = number(daily, "wind_speed_10m_max", i);
            Double code = number(daily, "weather_code", i);
            if (max == null || min == null || wind == null || code == null) {
                lines.add(day + " | data not available yet");
            } else {
                lines.add(String.format(Locale.US, "%s | %5.1f to %5.1f deg C | wind up to %5.1f km/h | %s",
                        day, min, max, wind, describe(code.intValue())));
            }
        }
    }

    // Challenge 2: one line per day. Days older than 7 days use the history service,
    // the other days use the forecast service (a range may need both).
    static void printRange(Place place, LocalDate start, LocalDate end) throws WeatherException {
        LocalDate today = LocalDate.now();
        LocalDate archiveLast = today.minusDays(8);      // newest date that counts as "older than 7 days"
        LocalDate forecastFirst = today.minusDays(7);    // oldest date the forecast service is used for

        List<String> lines = new ArrayList<>();
        if (start.isBefore(forecastFirst)) {
            LocalDate to = end.isBefore(archiveLast) ? end : archiveLast;
            fetchDays(place, start, to, ARCHIVE, lines);
        }
        if (!end.isBefore(forecastFirst)) {
            LocalDate from = start.isAfter(forecastFirst) ? start : forecastFirst;
            fetchDays(place, from, end, FORECAST, lines);
        }

        System.out.println("\n=== Weather Report ===");
        System.out.println(String.format(Locale.US, "Place : %s (%.2f, %.2f)",
                place.label(), place.lat(), place.lon()));
        System.out.println("Dates : " + start + (end.equals(start) ? "" : " to " + end));
        System.out.println("-----------------------------------------------------------------------");
        for (String line : lines) {
            System.out.println(line);
        }
    }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.println("=== Weather Lookup: choose place + date range ===");

        while (true) {
            System.out.print("\nEnter city or country (or quit): ");
            if (!in.hasNextLine()) break;
            String input = in.nextLine().trim();
            if (input.equalsIgnoreCase("quit")) break;
            if (input.isEmpty()) {
                System.out.println("Please type a place name.");
                continue;
            }

            System.out.print("Enter start date (YYYY-MM-DD): ");
            if (!in.hasNextLine()) break;
            String startText = in.nextLine().trim();
            if (startText.equalsIgnoreCase("quit")) break;

            System.out.print("Enter end date (YYYY-MM-DD, or press Enter for the same day): ");
            if (!in.hasNextLine()) break;
            String endText = in.nextLine().trim();

            try {
                LocalDate start = LocalDate.parse(startText);
                LocalDate end = endText.isEmpty() ? start : LocalDate.parse(endText);
                validateRange(start, end);

                List<Place> matches = findPlaces(input);
                Place place = choosePlace(matches, in);
                if (place == null) {
                    System.out.println("Cancelled.");
                    continue;
                }
                printRange(place, start, end);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date. Please use YYYY-MM-DD.");
            } catch (WeatherException e) {
                System.out.println("Weather lookup error: " + e.getMessage());
            }
        }
        System.out.println("Weather lookup ended.");
    }
}