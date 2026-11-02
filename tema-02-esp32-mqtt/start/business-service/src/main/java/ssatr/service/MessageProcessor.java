package ssatr.service;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String message = inbox.take();

                // mesajul se salvează exact cum a sosit, chiar dacă e greșit
                storage.append(kind, message);

                try {
                    process(message);
                } catch (RuntimeException e) {
                    // un mesaj greșit nu oprește thread-ul
                    System.out.println("Mesaj " + kind + " invalid (" + e.getMessage() + "): " + message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(String message) {
        // status: textul poate conține orice, deci împărțim în cel mult 3 câmpuri
        String[] fields = message.split(";", kind.equals("status") ? 3 : -1);

        switch (kind) {
            case "telemetry": {
                expectFields(fields, 3);
                dashboard.updateTelemetry(fields[0], Double.parseDouble(fields[2]));
                break;
            }
            case "alarm": {
                expectFields(fields, 4);
                double temperature = Double.parseDouble(fields[2]);
                double threshold = Double.parseDouble(fields[3]);
                dashboard.updateAlarm(fields[0]);
                System.out.printf(Locale.US, "%n!!! ALARMĂ %s: temperatura %.2f depășește pragul %.2f%n",
                        fields[0], temperature, threshold);
                break;
            }
            case "status": {
                expectFields(fields, 3);
                dashboard.updateStatus(fields[0], fields[2]);
                System.out.printf("%n<<< %s: %s%n", fields[0], fields[2]);
                break;
            }
            default:
                throw new IllegalStateException("tip de mesaj necunoscut: " + kind);
        }
    }

    private static void expectFields(String[] fields, int count) {
        if (fields.length != count) {
            throw new IllegalArgumentException("se așteaptă " + count + " câmpuri, sunt " + fields.length);
        }
    }
}
