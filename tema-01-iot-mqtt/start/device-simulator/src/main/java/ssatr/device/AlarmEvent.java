package ssatr.device;

/**
 * Un eveniment de alarmă, pus de taskul periodic în coada alarmEvents.
 *
 * Pragul este transmis împreună cu temperatura: alarma se raportează față de
 * pragul folosit la decizia de declanșare, chiar dacă între timp o comandă
 * SET_THRESHOLD l-a modificat. Astfel taskul sporadic nu depinde de
 * PeriodicTelemetryTask, ci doar de datele din eveniment.
 *
 * @param temperature temperatura care a depășit pragul
 * @param threshold   pragul în vigoare la momentul citirii
 * @param time        momentul sosirii evenimentului (System.currentTimeMillis())
 */
public record AlarmEvent(double temperature, double threshold, long time) {
}
