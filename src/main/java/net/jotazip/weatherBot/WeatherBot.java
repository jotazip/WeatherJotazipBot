package net.jotazip.weatherBot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
@Component
public class WeatherBot extends TelegramLongPollingBot {

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;
    
    // Conjunto para almacenar los chat IDs
    private Set<Long> chatIds = new HashSet<>();

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            // Guarda el chatId cada vez que un usuario interactúa con el bot
            saveChatId(chatId);
            // Maneja los comandos
            handleCommand(messageText, chatId);
        }
    }
    
    public void sendMsg(String chatIdStr, String text) {
        // Convertir chatId de String a long
        long chatId = Long.parseLong(chatIdStr);
        // Guardar el chatId antes de enviar el mensaje
        saveChatId(chatId);
        // Crear y enviar el mensaje
        SendMessage message = new SendMessage();
        message.setChatId(chatIdStr); // Usar el String original para enviar el mensaje
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleCommand(String command, long chatId) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0];
        String argument = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/start":
                sendMessage(chatId, "¡Hola! ¿Cómo puedo ayudarte hoy?");
                break;
            case "/help":
                sendMessage(chatId, "Aquí están los comandos disponibles:\n/start - Inicia el bot\n/help - Muestra esta ayuda");
                break;
            case "/weather":
            	// Guarda el chatId solo para los usuarios que soliciten el clima
                saveChatId(chatId);
                if (!argument.isEmpty()) {
                    try {
                        double[] coordinates = getCoordinates(argument);
                        String weatherData = getWeatherData(coordinates[0], coordinates[1], argument);
                        sendMessage(chatId, weatherData);
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendMessage(chatId, "Error al obtener los datos del clima. Inténtalo de nuevo más tarde.");
                    }
                } else {
                    sendMessage(chatId, "Por favor, proporciona una ciudad. Ejemplo: /weather Madrid");
                }
                break;
            default:
                sendMessage(chatId, "Comando desconocido. Usa /help para ver los comandos disponibles.");
                break;
        }
    }
    
    public void setBotCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("/start", "Inicia el bot"));
        commands.add(new BotCommand("/help", "Descripción del comando"));

        try {
            SetMyCommands setMyCommands = new SetMyCommands();
            setMyCommands.setCommands(commands);
            execute(setMyCommands);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String getWeatherData(double latitude, double longitude, String cityName) throws Exception {
        // Validar el rango de coordenadas
        if (latitude < -90 || latitude > 90 || longitude > 180) {
            throw new IllegalArgumentException("Latitud o longitud fuera de rango.");
        }

        // Coordenadas
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude +
                "&longitude=" + longitude + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation_probability";        
        System.out.println("URL de la API de Open-Meteo: " + url);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            HttpResponse response = httpClient.execute(request);
            String json = EntityUtils.toString(response.getEntity());

            // Imprimir el JSON para depuración
            System.out.println("Respuesta de la API de Open-Meteo: " + json);

            // Procesar JSON para obtener los datos del clima
            JSONObject jsonObject = new JSONObject(json);
            JSONObject currentWeatherJson = (JSONObject) jsonObject.get("current");
            double temperature = currentWeatherJson.getDouble("temperature_2m");
            double precipitationProbability = currentWeatherJson.getDouble("precipitation_probability");
            double humidity = currentWeatherJson.getDouble("relative_humidity_2m");
            double windSpeed = currentWeatherJson.getDouble("wind_speed_10m");

            // Capitalizar el nombre de la ciudad
            cityName = cityName.substring(0, 1).toUpperCase() + cityName.substring(1).toLowerCase();

            // Añadir emojis según el caso climatológico
            String weatherEmoji;
            if (precipitationProbability > 50) {
                weatherEmoji = "🌧️";
            } else if (temperature > 30) {
                weatherEmoji = "☀️";
            } else if (temperature < 0) {
                weatherEmoji = "❄️";
            } else {
                weatherEmoji = "🌤️";
            }

            // Añadir emojis para humedad, precipitación y viento
            String humidityEmoji = humidity > 70 ? "💧" : "💦";
            String precipitationEmoji = precipitationProbability > 50 ? "☔" : "🌂";
            String windEmoji = windSpeed > 20 ? "💨" : "🍃";

            return String.format("El tiempo en %s es:\n\n%s Temperatura: %.1f°C\n%s Precipitación: %.1f%%\n%s Humedad: %.1f%%\n%s Velocidad del viento: %.1f km/h",
                    cityName, weatherEmoji, temperature, precipitationEmoji, precipitationProbability, humidityEmoji, humidity, windEmoji, windSpeed);
        }
    }
    
    private double[] getCoordinates(String city) throws Exception {
        String apiKey = "04f5c63f3573456fa839fbc93ef1a31c"; // Reemplaza con tu clave API
        String geocodeUrl = String.format("https://api.opencagedata.com/geocode/v1/json?q=%s&key=%s", city, apiKey);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(geocodeUrl);
            HttpResponse response = httpClient.execute(request);
            String json = EntityUtils.toString(response.getEntity());

            // Imprime el JSON para verificar la respuesta
            System.out.println("Respuesta de la API de geocodificación: " + json);

            // Procesar JSON para obtener coordenadas
            JSONObject jsonObject = new JSONObject(json);
            JSONObject result = jsonObject.getJSONArray("results").getJSONObject(0);
            double lat = result.getJSONObject("geometry").getDouble("lat");
            double lng = result.getJSONObject("geometry").getDouble("lng");
            System.out.println("Coordenadas obtenidas: Latitud = " + lat + ", Longitud = " + lng);

            return new double[]{lat, lng};
        }
    }

    private void sendMessage(long chatId, String text) {
        // Guardar el chatId antes de enviar el mensaje
        saveChatId(chatId);
        // Crear y enviar el mensaje
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId); // Usar el ID del chat como long
        sendMessage.setText(text);
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
    
    // Método programado para enviar datos meteorológicos cada hora
    @Scheduled(fixedRate = 3600000) // 3600000 milisegundos = 1 hora
    public void sendWeatherUpdates() {
        for (long chatId : chatIds) {
            String cityName = "Alicante"; // Ciudad de ejemplo

            try {
                double[] coordinates = getCoordinates(cityName);
                String weatherData = getWeatherData(coordinates[0], coordinates[1], cityName);
                sendMessage(chatId, weatherData);
            } catch (Exception e) {
                e.printStackTrace();
                sendMessage(chatId, "Error al obtener los datos del clima. Inténtalo de nuevo más tarde.");
            }
        }
    }
    
    private void saveChatId(long chatId) {
        chatIds.add(chatId);
    }
}