// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

#include <string.h>
#include <stdlib.h>
#include <ctype.h>

// ---------------------------------------------------------------------------
// Starea device-ului, schimbată de taskul aperiodic (SET_INTERVAL/SET_THRESHOLD/
// RESET) și citită de taskul periodic. Fiind partajată între taskuri, e volatile.
// ---------------------------------------------------------------------------
volatile uint32_t g_period_ms = PERIOD_MS_INITIAL;   // perioada T a taskului periodic
volatile float    g_threshold = THRESHOLD_INITIAL;   // pragul de alarmă, în °C

// ---------------------------------------------------------------------------
// Elementele celor două cozi. Se pun în coadă PRIN VALOARE (xQueueSend copiază),
// deci struct-ul trebuie să conțină datele însele, nu un pointer.
// ---------------------------------------------------------------------------

// eveniment de alarmă: taskul periodic îl generează, taskul sporadic îl consumă.
// Poartă pragul folosit de jobul periodic, ca alarma să raporteze exact pragul
// în vigoare atunci (la fel ca opțiunea aleasă în tema 01).
struct AlarmEvent {
    float   temperature;
    float   threshold;
    int64_t timestamp;    // epoch_ms() din momentul generării
};

// comandă primită pe MQTT: conține textul însuși (nu un pointer), pentru că
// `text` din command_received() e valid doar pe durata apelului.
struct Command {
    char text[COMMAND_MAX_LEN];
};

static QueueHandle_t g_alarm_queue;     // AlarmEvent: periodic -> sporadic
static QueueHandle_t g_command_queue;   // Command:    mqtt    -> aperiodic

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

// ===========================================================================
// TODO 1: TASK PERIODIC, cu perioada T (inițial PERIOD_MS_INITIAL).
// La fiecare job: citește senzorul, publică telemetria și, dacă temperatura
// depășește pragul, pune un eveniment în coada de alarme (fără să se blocheze).
// Eliberările respectă grila r_k = r_0 + kT prin AȘTEPTARE ABSOLUTĂ
// (xTaskDelayUntil), nu relativă (delay/vTaskDelay), ca să nu apară derivă.
// ===========================================================================
void periodic_task(void* arg) {
    TickType_t last_wake = xTaskGetTickCount();   // momentul de referință al grilei

    for (;;) {
        float   t  = read_temperature();
        int64_t ts = epoch_ms();

        // telemetry: "deviceId;timestamp;temperatura" (punct la zecimale -> Double.parseDouble)
        char payload[64];
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f", DEVICE_ID, (long long)ts, t);
        mqtt_publish("telemetry", payload);
        Serial.printf("[periodic] telemetry %.2f\n", t);

        // pragul se poate schimba din taskul aperiodic; îl citim o dată pe job
        float threshold = g_threshold;
        if (t > threshold) {
            AlarmEvent ev = { t, threshold, ts };
            // timeout 0: dacă nu intră imediat în coadă, jobul periodic NU se blochează
            if (xQueueSend(g_alarm_queue, &ev, 0) != pdTRUE) {
                Serial.println("[periodic] coada de alarme plină, eveniment pierdut");
            }
        }

        // perioada se poate schimba din taskul aperiodic; o citim la fiecare job.
        // xTaskDelayUntil așteaptă până la last_wake + T și actualizează last_wake,
        // deci eliberările rămân pe grilă chiar dacă jobul a durat ceva timp.
        uint32_t T = g_period_ms;
        xTaskDelayUntil(&last_wake, pdMS_TO_TICKS(T));
    }
}

// ===========================================================================
// TODO 2: TASK SPORADIC, tratează alarmele.
// Preia evenimente din coada de alarme și impune intervalul minim între sosiri
// MIN_INTERARRIVAL_MS: evenimentele care vin mai des sunt respinse. Pentru
// intervale folosim timp MONOTON (millis()), nu epoch_ms().
// ===========================================================================
void sporadic_task(void* arg) {
    uint32_t last_published = 0;      // millis() al ultimei alarme PUBLICATE
    bool     have_published = false;  // ca prima alarmă să nu fie respinsă

    for (;;) {
        AlarmEvent ev;
        // blochează până sosește un eveniment (fără busy-wait, fără CPU)
        xQueueReceive(g_alarm_queue, &ev, portMAX_DELAY);

        uint32_t now = millis();
        if (have_published && (now - last_published) < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] alarmă respinsă (au trecut %lu ms < %lu ms)\n",
                          (unsigned long)(now - last_published),
                          (unsigned long)MIN_INTERARRIVAL_MS);
            continue;   // sub intervalul minim -> respinsă
        }

        // alarm: "deviceId;timestamp;temperatura;prag"
        char payload[64];
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)ev.timestamp, ev.temperature, ev.threshold);
        mqtt_publish("alarm", payload);
        Serial.printf("[sporadic] ALARMĂ %.2f > %.2f\n", ev.temperature, ev.threshold);

        last_published = now;
        have_published = true;
    }
}

// ===========================================================================
// TODO 3: TASK APERIODIC, execută comenzile primite de la business service.
// Preia comenzi din coada de comenzi, le execută și publică o confirmare pe
// topic-ul `status`. O comandă greșită produce "ERROR ..." și NU oprește taskul.
// ===========================================================================
void aperiodic_task(void* arg) {
    for (;;) {
        Command cmd;
        xQueueReceive(g_command_queue, &cmd, portMAX_DELAY);   // un job per comandă

        // împarte comanda pe spații: nume [parametru] [în plus?]
        char buf[COMMAND_MAX_LEN];
        strncpy(buf, cmd.text, sizeof(buf));
        buf[sizeof(buf) - 1] = '\0';

        char* name  = strtok(buf,  " \t\r\n");
        char* param = strtok(NULL, " \t\r\n");
        char* extra = strtok(NULL, " \t\r\n");   // dacă nu e NULL, sunt prea mulți parametri

        // numele comenzii, uppercase (ca în tema 01)
        if (name) for (char* p = name; *p; ++p) *p = toupper((unsigned char)*p);

        char result[80];   // textul confirmării: "OK ..." sau "ERROR ..."

        if (name == NULL) {
            snprintf(result, sizeof(result), "ERROR comandă goală");

        } else if (strcmp(name, "SET_INTERVAL") == 0) {
            if (param == NULL || extra != NULL) {
                snprintf(result, sizeof(result), "ERROR SET_INTERVAL: se așteaptă exact un parametru");
            } else {
                char* end;
                long ms = strtol(param, &end, 10);
                if (*end != '\0')      snprintf(result, sizeof(result), "ERROR parametru invalid: %s", param);
                else if (ms <= 0)      snprintf(result, sizeof(result), "ERROR SET_INTERVAL: perioada trebuie să fie > 0");
                else { g_period_ms = (uint32_t)ms; snprintf(result, sizeof(result), "OK SET_INTERVAL %ld", ms); }
            }

        } else if (strcmp(name, "SET_THRESHOLD") == 0) {
            if (param == NULL || extra != NULL) {
                snprintf(result, sizeof(result), "ERROR SET_THRESHOLD: se așteaptă exact un parametru");
            } else {
                char* end;
                float v = strtof(param, &end);
                if (*end != '\0') snprintf(result, sizeof(result), "ERROR parametru invalid: %s", param);
                else { g_threshold = v; snprintf(result, sizeof(result), "OK SET_THRESHOLD %.2f", v); }
            }

        } else if (strcmp(name, "RESET") == 0) {
            if (param != NULL) {
                snprintf(result, sizeof(result), "ERROR RESET: nu are parametri");
            } else {
                g_period_ms = PERIOD_MS_INITIAL;
                g_threshold = THRESHOLD_INITIAL;
                snprintf(result, sizeof(result), "OK RESET");
            }

        } else if (strcmp(name, "STATUS") == 0) {
            if (param != NULL) {
                snprintf(result, sizeof(result), "ERROR STATUS: nu are parametri");
            } else {
                snprintf(result, sizeof(result), "OK STATUS interval=%lu prag=%.2f",
                         (unsigned long)g_period_ms, g_threshold);
            }

        } else {
            snprintf(result, sizeof(result), "ERROR comandă necunoscută: %s", name);
        }

        // status: "deviceId;timestamp;text"
        char payload[128];
        snprintf(payload, sizeof(payload), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), result);
        mqtt_publish("status", payload);
        Serial.printf("[aperiodic] %s -> %s\n", cmd.text, result);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
// Rulează pe taskul clientului MQTT: doar PUNEM comanda în coadă, nu o executăm
// aici. `text` e valid doar în timpul apelului, de aceea îl copiem în struct.
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    Command cmd;
    strncpy(cmd.text, text, sizeof(cmd.text));
    cmd.text[sizeof(cmd.text) - 1] = '\0';   // trunchiere sigură dacă e prea lungă

    // timeout 0: callback-ul MQTT nu are voie să se blocheze
    if (xQueueSend(g_command_queue, &cmd, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi plină, comandă pierdută");
    }
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): cozile se creează ÎNAINTE de mqtt_start(), pentru că de acolo
    // încolo pot sosi comenzi, iar command_received() le pune în coada de comenzi.
    g_alarm_queue   = xQueueCreate(10, sizeof(AlarmEvent));
    g_command_queue = xQueueCreate(10, sizeof(Command));
    if (g_alarm_queue == NULL || g_command_queue == NULL) {
        Serial.println("EROARE: nu s-au putut crea cozile; repornesc.");
        ESP.restart();
    }

    mqtt_start();

    // TODO 0 (b): taskurile se pornesc DUPĂ mqtt_start(), ca primele mesaje
    // publicate să nu se piardă. Priorități între 2 și 19 (peste loopTask=1,
    // sub sistemul de evenimente/Wi-Fi=20/23). Stivă în octeți, minim 4096.
    //
    // Alegerea priorităților (un singur nucleu, planificare preemptivă):
    //   sporadic (7) > periodic (6) > aperiodic (5)
    // Alarma e cel mai critic eveniment și cel mai rar -> prioritate maximă, ca
    // să iasă imediat; taskul periodic e coloana vertebrală cu termen ferm pe
    // grilă -> la mijloc; comenzile sunt best-effort (soft) -> cea mai mică.
    // Taskurile sporadic/aperiodic stau blocate pe coadă când n-au treabă, deci
    // prioritatea lor mare nu fură CPU de la taskul periodic.
    xTaskCreate(periodic_task,  "periodic",  4096, NULL, 6, NULL);
    xTaskCreate(sporadic_task,  "sporadic",  4096, NULL, 7, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", 4096, NULL, 5, NULL);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}
