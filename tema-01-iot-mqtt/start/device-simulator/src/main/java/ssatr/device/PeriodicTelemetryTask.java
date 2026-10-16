package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        // r_0: prima eliberare este chiar acum; grila folosește un timp monoton
        long release = System.nanoTime();
        int k = 0;

        try {
            while (true) {
                // întârzierea jobului față de eliberarea lui de pe grilă: L_k = s_k - r_k
                double latenessMs = (System.nanoTime() - release) / 1_000_000.0;

                // calculul jobului: citire senzor + publicare
                double t = sensor.read();
                double currentThreshold = threshold;   // o singură citire a câmpului volatile
                String message = deviceId + ";" + System.currentTimeMillis() + ";"
                        + String.format(Locale.US, "%.2f", t);
                mqtt.publish(Config.telemetryTopic(deviceId), message);

                System.out.printf(Locale.US, "[periodic]  job #%d  L = %.3f ms  T = %d ms  temp = %.2f%n",
                        k, latenessMs, periodMs, t);

                if (t > currentThreshold) {
                    // doar semnalăm evenimentul; alarma o tratează taskul sporadic
                    alarmEvents.put(new AlarmEvent(t, currentThreshold, System.currentTimeMillis()));
                }

                // eliberarea următoare: r_{k+1} = r_k + T (periodMs citit la fiecare job,
                // pentru că SET_INTERVAL îl poate schimba)
                release += periodMs * 1_000_000;
                k++;

                long remainingNs = release - System.nanoTime();
                if (remainingNs > 0) {
                    // așteptare absolută: dormim doar cât a mai rămas până la eliberare
                    Thread.sleep(remainingNs / 1_000_000, (int) (remainingNs % 1_000_000));
                } else if (-remainingNs > periodMs * 1_000_000) {
                    // depășire de peste o perioadă: nu recuperăm joburile pierdute
                    // în rafală, ci repornim grila de acum
                    System.out.printf("[periodic]  depășire de %d ms, grila se resincronizează%n",
                            -remainingNs / 1_000_000);
                    release = System.nanoTime();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
