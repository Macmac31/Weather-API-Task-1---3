// WeatherLookup.java - Tasks 3 and 4: interactive weather lookup by place and date
// Services: Open-Meteo geocoding, forecast, and archive APIs (no key needed).
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Scanner;

public class WeatherLookup {

    static class WeatherException extends Exception {
        WeatherException(String message) { super(message); }
    }

    record Place(String name, String country, double lat, double lon) { }

    record DayWeather(LocalDate date, double tempMax, double tempMin,
                      double windMax, int code) { }

    static final int MAX_DAYS_AHEAD = 16;
    static final int ARCHIVE_AFTER_DAYS = 7;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Appendix A: weather code to words
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

    // Sends a GET request; returns the body only if the status is 200.
    static String get(String url) throws WeatherException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String msg = "The service answered with HTTP " + response.statusCode()
                        + reasonOf(response.body()) + ".";
                if (response.statusCode() == 429) {
                    msg += " Too many requests: wait a minute and try again.";
                }
                throw new WeatherException(msg);
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new WeatherException("The weather service did not answer in time. Please try again.");
        } catch (IOException e) {
            throw new WeatherException(
                    "Could not reach the weather service. Check your internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("The request was interrupted. Please try again.");
        }
    }

    // Reads the API's "reason" text from an error reply, if there is one.
    static String reasonOf(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("reason") && !o.get("reason").isJsonNull()) {
                return " (" + o.get("reason").getAsString() + ")";
            }
        } catch (RuntimeException ignored) {
            // body was not JSON; no reason available
        }
        return "";
    }

    // Parses text into a JSON object or reports a friendly problem.
    static JsonObject parseObject(String body) throws WeatherException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new WeatherException("The service sent a reply that could not be read.");
        }
    }

    static String text(JsonObject o, String key, String fallback) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : fallback;
    }

    static Place findPlace(String input) throws WeatherException {
        String name = input.trim();
        String country = null;
        int comma = name.indexOf(',');
        if (comma >= 0) {
            country = name.substring(comma + 1).trim();
            name = name.substring(0, comma).trim();
            if (country.isEmpty()) {
                country = null;
            }
        }
        if (name.isEmpty()) {
            throw new WeatherException("Please type a place name.");
        }

        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&count=10&language=en&format=json";
        JsonObject root = parseObject(get(url));

        if (!root.has("results") || !root.get("results").isJsonArray()
                || root.getAsJsonArray("results").isEmpty()) {
            throw new WeatherException("No place found matching \u201c" + name + "\u201d. Check the spelling.");
        }

        for (JsonElement el : root.getAsJsonArray("results")) {
            JsonObject r = el.getAsJsonObject();
            if (!r.has("latitude") || !r.has("longitude")) {
                continue;
            }
            String cName = text(r, "name", name);
            String cCountry = text(r, "country", "");
            String cCode = text(r, "country_code", "");
            boolean ok = country == null
                    || cCountry.equalsIgnoreCase(country)
                    || cCode.equalsIgnoreCase(country);
            if (ok) {
                return new Place(cName, cCountry,
                        r.get("latitude").getAsDouble(), r.get("longitude").getAsDouble());
            }
        }
        throw new WeatherException("Found places named \u201c" + name + "\u201d, but none in \u201c"
                + country + "\u201d.");
    }

    // Reads element 0 of a daily array; reports a friendly problem if absent or null.
    static JsonElement firstValue(JsonObject daily, String key) throws WeatherException {
        if (!daily.has(key) || !daily.get(key).isJsonArray()
                || daily.getAsJsonArray(key).isEmpty()
                || daily.getAsJsonArray(key).get(0).isJsonNull()) {
            throw new WeatherException("Weather data (" + key + ") is not available for that date.");
        }
        return daily.getAsJsonArray(key).get(0);
    }

    static DayWeather getDayWeather(Place p, LocalDate date) throws WeatherException {
        long daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now());
        String base = (daysAgo > ARCHIVE_AFTER_DAYS)
                ? "https://archive-api.open-meteo.com/v1/archive"
                : "https://api.open-meteo.com/v1/forecast";
        String url = base
                + String.format(Locale.US, "?latitude=%.4f&longitude=%.4f", p.lat(), p.lon())
                + "&start_date=" + date + "&end_date=" + date
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max"
                + "&timezone=auto";
        JsonObject root = parseObject(get(url));

        if (!root.has("daily") || !root.get("daily").isJsonObject()) {
            throw new WeatherException("The weather reply had no daily section for that date.");
        }
        JsonObject daily = root.getAsJsonObject("daily");
        try {
            return new DayWeather(date,
                    firstValue(daily, "temperature_2m_max").getAsDouble(),
                    firstValue(daily, "temperature_2m_min").getAsDouble(),
                    firstValue(daily, "wind_speed_10m_max").getAsDouble(),
                    firstValue(daily, "weather_code").getAsInt());
        } catch (ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new WeatherException("The weather reply contained values in an unexpected form.");
        }
    }

    static void printReport(Place p, DayWeather w) {
        String where = p.country().isEmpty() ? p.name() : p.name() + ", " + p.country();
        System.out.println();
        System.out.println("=== Weather Report ===");
        System.out.println(String.format(Locale.US, "Place        : %s (%.2f, %.2f)", where, p.lat(), p.lon()));
        System.out.println("Date         : " + w.date());
        System.out.println(String.format(Locale.US, "Temperature  : %.1f to %.1f deg C", w.tempMin(), w.tempMax()));
        System.out.println(String.format(Locale.US, "Wind         : up to %.1f km/h", w.windMax()));
        System.out.println("Conditions   : " + describe(w.code()));
        System.out.println();
    }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.println("=== Weather Lookup (Open-Meteo) ===");

        while (true) {
            System.out.print("Enter city or country (or quit): ");
            if (!in.hasNextLine()) break;
            String placeText = in.nextLine().trim();
            if (placeText.equalsIgnoreCase("quit")) break;
            if (placeText.isEmpty()) {
                System.out.println("Error: Please type a place name.");
                continue;
            }

            System.out.print("Enter date (YYYY-MM-DD): ");
            if (!in.hasNextLine()) break;
            String dateText = in.nextLine().trim();
            if (dateText.equalsIgnoreCase("quit")) break;

            try {
                LocalDate date = LocalDate.parse(dateText);
                LocalDate last = LocalDate.now().plusDays(MAX_DAYS_AHEAD);
                if (date.isAfter(last)) {
                    System.out.println("That date is too far ahead. Forecasts are available up to "
                            + MAX_DAYS_AHEAD + " days from today (until " + last + ").");
                    continue;
                }
                Place place = findPlace(placeText);
                DayWeather weather = getDayWeather(place, date);
                printReport(place, weather);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date. Use the format YYYY-MM-DD with a real calendar date "
                        + "(for example " + LocalDate.now() + ").");
            } catch (WeatherException e) {
                System.out.println("Error: " + e.getMessage());
            } catch (JsonSyntaxException e) {
                System.out.println("Error: The service sent a reply that could not be read.");
            }
        }
        System.out.println("Goodbye!");
    }
}
