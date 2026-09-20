/*
 * TAU2202 Raw Bridge for ESP32-C3
 *
 * Прозрачный мост между GNSS-модулем (UART1) и USB-CDC.
 * Всё, что приходит от GNSS → в USB. Всё, что вводится в USB → в GNSS.
 *
 * Дополнительно:
 *  - мигает встроенным LED при активности GNSS;
 *  - выводит счётчик PPS раз в секунду;
 *  - отключает WiFi/BT (снижает помехи);
 *  - ждёт открытия USB-порта, чтобы не терять первые строки.
 *
 * Настройки Arduino IDE:
 *   - Board:          ESP32C3 Dev Module
 *   - USB CDC On Boot: Enabled   ← обязательно
 *   - CPU Frequency:  160 MHz (или 80 MHz для меньших помех)
 *   - Flash Size:     4 MB (default)
 */

#include <HardwareSerial.h>
#include <WiFi.h>
#include <esp_bt.h>

// ─── Пины ───
#define GNSS_RX_PIN   20      // ESP32-C3 RX ← TX модуля
#define GNSS_TX_PIN   21      // ESP32-C3 TX → RX модуля
#define PPS_PIN       4       // вход PPS от модуля
#define STATUS_LED    8       // встроенный LED на DevKit

// ─── Настройки ───
#define GNSS_BAUD     115200
#define USB_BAUD      115200
#define USB_WAIT_MS   3000    // сколько ждать подключения хоста к USB

HardwareSerial GNSS(1);

// ─── PPS ───
volatile uint32_t ppsCount = 0;
volatile uint32_t lastPpsMicros = 0;

void IRAM_ATTR onPps() {
    ppsCount++;
    lastPpsMicros = micros();
}

// ─── Индикация активности ───
uint32_t lastGnssByte = 0;

void setup() {
    pinMode(STATUS_LED, OUTPUT);
    digitalWrite(STATUS_LED, LOW);

    // Отключаем WiFi/BT, чтобы не создавать помехи GNSS
    WiFi.mode(WIFI_OFF);
    btStop();

    // USB-CDC
    Serial.begin(USB_BAUD);

    // Ждём, пока хост откроет порт. Если не дождались за USB_WAIT_MS —
    // продолжаем, но первые строки могут быть потеряны.
    uint32_t t0 = millis();
    while (!Serial && (millis() - t0 < USB_WAIT_MS)) {
        delay(10);
    }
    delay(200);   // ещё немного, чтобы хост точно был готов

    // GNSS-UART
    GNSS.begin(GNSS_BAUD, SERIAL_8N1, GNSS_RX_PIN, GNSS_TX_PIN);

    // Сброс «мусора» из UART-буфера
    delay(50);
    while (GNSS.available()) GNSS.read();

    // PPS
    pinMode(PPS_PIN, INPUT);
    attachInterrupt(digitalPinToInterrupt(PPS_PIN), onPps, RISING);

    Serial.println();   // пустая строка для чистоты старта
}

void loop() {
    // 1) GNSS → USB
    while (GNSS.available()) {
        char c = (char)GNSS.read();
        Serial.write(c);
        lastGnssByte = millis();
        digitalWrite(STATUS_LED, HIGH);
    }

    // 2) USB → GNSS
    while (Serial.available()) {
        GNSS.write(Serial.read());
    }

    // 3) Индикация активности: LED гаснет через 20 мс после последнего байта
    if (digitalRead(STATUS_LED) == HIGH && millis() - lastGnssByte > 20) {
        digitalWrite(STATUS_LED, LOW);
    }

    // 4) Раз в секунду отправляем счётчик PPS в USB
    static uint32_t lastPpsReport = 0;
    if (millis() - lastPpsReport >= 1000) {
        lastPpsReport = millis();
        uint32_t count = ppsCount;
        uint32_t age = (micros() - lastPpsMicros) / 1000;  // мс с последнего импульса
        Serial.printf("$PPS,%lu,%lu\r\n", count, age);
    }
}