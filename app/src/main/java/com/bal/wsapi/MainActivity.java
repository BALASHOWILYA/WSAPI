package com.bal.wsapi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebSocket";
    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@trade";
    private static final int THRESHOLD = 95; // Порог объема сделки
    private NotificationManager notificationManager;

    private OkHttpClient client;
    private WebSocket webSocket;

    private final LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long MESSAGE_INTERVAL = 1000;

    private LineChart lineChart;
    private List<Entry> priceEntries = new ArrayList<>();
    private int timeIndex = 0;
    private TextView currentPriceTextView;
    private TextView priceChangeTextView;

    private float previousPrice = 0f;
    private float priceChangePercentage = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                "trades_channel",
                "Trades Notifications",
                NotificationManager.IMPORTANCE_HIGH
        );
        notificationManager.createNotificationChannel(channel);

        lineChart = findViewById(R.id.lineChart);
        currentPriceTextView = findViewById(R.id.currentPrice);
        priceChangeTextView = findViewById(R.id.priceChange);

        setupLineChart();

        setupWebSocket();

        handler.postDelayed(processMessagesRunnable, MESSAGE_INTERVAL);
    }

    private void sendNotification(String title, String message) {
        Notification notification = new NotificationCompat.Builder(this, "trades_channel")
                .setSmallIcon(R.drawable.ic_notification) // Иконка уведомления
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        notificationManager.notify((int) System.currentTimeMillis(), notification);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webSocket != null) {
            webSocket.close(1000, "App closed");
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
        handler.removeCallbacks(processMessagesRunnable);
    }

    private void setupWebSocket() {
        client = new OkHttpClient();
        Request request = new Request.Builder().url(BINANCE_WS_URL).build();
        webSocket = client.newWebSocket(request, new BinanceWebSocketListener());
    }

    private final Runnable processMessagesRunnable = new Runnable() {
        @Override
        public void run() {
            String message = messageQueue.poll();
            if (message != null) {
                Log.d(TAG, "Processing message: " + message);
                processWebSocketMessage(message);
            }
            handler.postDelayed(this, MESSAGE_INTERVAL);
        }
    };

    private void setupLineChart() {
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);

        YAxis rightAxis = lineChart.getAxisRight();
        rightAxis.setEnabled(false);

        lineChart.setData(new LineData());
        lineChart.getDescription().setEnabled(false);
    }

    private void updateChart(float currentPrice) {
        priceEntries.add(new Entry(timeIndex++, currentPrice));

        LineDataSet dataSet = new LineDataSet(priceEntries, "BTC/USDT Price");
        dataSet.setColor(getColor(R.color.black));
        dataSet.setValueTextColor(getColor(R.color.black));
        dataSet.setLineWidth(2f);

        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);
        lineChart.invalidate(); // Обновление графика

        runOnUiThread(() -> updatePriceInfo(currentPrice));
    }
    private void updatePriceInfo(float currentPrice) {
        // Обновляем текущую цену
        currentPriceTextView.setText(String.format("Current Price: $%.2f", currentPrice));

        // Рассчитываем изменение цены
        if (previousPrice != 0f) {
            priceChangePercentage = ((currentPrice - previousPrice) / previousPrice) * 100;
        }
        previousPrice = currentPrice;

        // Обновляем текстовое поле изменения цены
        priceChangeTextView.setText(String.format("24h Change: %.2f%%", priceChangePercentage));
    }

    private void processWebSocketMessage(String message) {
        try {
            // Разбор JSON-ответа
            float price = parsePriceFromMessage(message);
            runOnUiThread(() -> updateChart(price));
        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
        }
    }

    private float parsePriceFromMessage(String message) throws Exception {
        // Парсим JSON, чтобы извлечь цену
        org.json.JSONObject json = new org.json.JSONObject(message);
        return (float) json.getDouble("p"); // "p" — цена в Binance API
    }

    private class BinanceWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "WebSocket Opened");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (!messageQueue.offer(text)) {
                Log.w(TAG, "Queue is full, dropping message");
            }
            try {
                // Парсинг JSON ответа
                JSONObject trade = new JSONObject(text);
                double tradeVolume = trade.getDouble("q"); // Объем сделки (количество)

                Log.d(TAG, "Получено сообщение: " + text);
                Log.d(TAG, "Объем сделки: " + tradeVolume);

                if (tradeVolume > THRESHOLD) {
                    sendNotification("Крупная сделка!", "Объем сделки: " + tradeVolume);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Ошибка парсинга JSON", e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "WebSocket Error", t);
        }
    }
}
