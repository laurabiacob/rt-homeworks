package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    // momentul ultimei eliberări acceptate; inițial suficient de demult ca
    // prima alarmă să fie mereu acceptată
    private long lastRelease = -MIN_INTERARRIVAL_MS;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        try {
            while (true) {
                AlarmEvent e = alarmEvents.take();   // așteaptă (blocat) următorul eveniment

                // intervalul se măsoară între momentele de sosire ale evenimentelor
                long sinceLast = e.time() - lastRelease;
                if (sinceLast < MIN_INTERARRIVAL_MS) {
                    System.out.printf(Locale.US,
                            "[sporadic]  alarmă RESPINSĂ (%.2f > %.2f): au trecut doar %d ms (interval minim %d ms)%n",
                            e.temperature(), e.threshold(), sinceLast, MIN_INTERARRIVAL_MS);
                    continue;
                }
                lastRelease = e.time();

                // pragul vine în eveniment: cel față de care taskul periodic a decis alarma
                String message = deviceId + ";" + System.currentTimeMillis() + ";"
                        + String.format(Locale.US, "%.2f;%.2f", e.temperature(), e.threshold());
                mqtt.publish(Config.alarmTopic(deviceId), message);

                long responseTime = System.currentTimeMillis() - e.time();
                System.out.printf(Locale.US, "[sporadic]  ALARMĂ publicată: %.2f > %.2f  R = %d ms%n",
                        e.temperature(), e.threshold(), responseTime);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
