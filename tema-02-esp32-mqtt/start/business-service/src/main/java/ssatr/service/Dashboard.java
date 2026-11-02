package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat: toate metodele publice sunt synchronized,
 * astfel încât print() vede o stare consistentă a tuturor device-urilor.
 */
public class Dashboard {

    /** Starea unui device. Este modificată doar din metodele synchronized ale Dashboard. */
    private static class DeviceState {
        Double lastTemperature;      // null până la prima telemetrie
        long lastMessageTime;        // ora locală a primirii ultimului mesaj
        int alarms;
        String lastStatus = "-";
    }

    // TreeMap: device-urile se afișează sortate după ID
    private final Map<String, DeviceState> devices = new TreeMap<>();

    public synchronized void updateTelemetry(String deviceId, double temperature) {
        stateOf(deviceId).lastTemperature = temperature;
    }

    public synchronized void updateAlarm(String deviceId) {
        stateOf(deviceId).alarms++;
    }

    public synchronized void updateStatus(String deviceId, String text) {
        stateOf(deviceId).lastStatus = text;
    }

    /** Întoarce starea device-ului (o creează la primul mesaj) și marchează momentul primirii. */
    private DeviceState stateOf(String deviceId) {
        DeviceState state = devices.computeIfAbsent(deviceId, id -> new DeviceState());
        state.lastMessageTime = System.currentTimeMillis();
        return state;
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("(niciun device nu a trimis mesaje încă)");
            return;
        }

        long now = System.currentTimeMillis();
        System.out.printf("%-12s %8s   %-15s %6s   %s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME", "ULTIMA CONFIRMARE");
        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            DeviceState s = entry.getValue();
            String temp = s.lastTemperature == null ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature);
            String age = "acum " + (now - s.lastMessageTime) / 1000 + " s";
            System.out.printf("%-12s %8s   %-15s %6d   %s%n", entry.getKey(), temp, age, s.alarms, s.lastStatus);
        }
    }
}
