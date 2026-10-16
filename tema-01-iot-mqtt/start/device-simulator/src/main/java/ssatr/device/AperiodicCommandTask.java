package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String command = commands.take().trim();   // fiecare comandă eliberează un job

                String result;
                try {
                    result = execute(command);
                } catch (IllegalArgumentException e) {
                    // o comandă greșită nu oprește taskul, doar produce un mesaj de eroare
                    result = "ERROR " + e.getMessage();
                }

                System.out.println("[aperiodic] " + command + " -> " + result);
                mqtt.publish(Config.statusTopic(deviceId), deviceId + ";" + System.currentTimeMillis() + ";" + result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Execută o comandă și întoarce textul confirmării; aruncă IllegalArgumentException pentru comenzi greșite. */
    private String execute(String command) {
        String[] parts = command.split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);

        switch (name) {
            case "SET_INTERVAL": {
                long ms = parseLong(parameter(parts));
                if (ms <= 0) {
                    throw new IllegalArgumentException("SET_INTERVAL: perioada trebuie să fie > 0");
                }
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                double value = parseDouble(parameter(parts));
                periodic.setThreshold(value);
                return "OK SET_THRESHOLD " + String.format(Locale.US, "%.2f", value);
            }
            case "RESET":
                noParameter(parts);
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                noParameter(parts);
                return String.format(Locale.US, "OK STATUS interval=%d prag=%.2f",
                        periodic.getPeriodMs(), periodic.getThreshold());
            default:
                throw new IllegalArgumentException("comandă necunoscută: " + command);
        }
    }

    private static String parameter(String[] parts) {
        if (parts.length != 2) {
            throw new IllegalArgumentException(parts[0] + ": se așteaptă exact un parametru");
        }
        return parts[1];
    }

    private static void noParameter(String[] parts) {
        if (parts.length != 1) {
            throw new IllegalArgumentException(parts[0] + ": nu are parametri");
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parametru invalid: " + s);
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parametru invalid: " + s);
        }
    }
}
