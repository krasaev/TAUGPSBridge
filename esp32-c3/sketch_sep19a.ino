/*
 * TAU2202 Raw Bridge for ESP32-C3
 * 
 * Просто передаёт всё, что приходит с GNSS-модуля, в USB-порт
 * и обратно: всё, что вводится в монитор порта, уходит в модуль.
 * 
 * Настройки Arduino IDE:
 *   - USB CDC On Boot: Enabled
 *   - Board: ESP32C3 Dev Module
 */

#include <HardwareSerial.h>

#define GNSS_RX_PIN 20
#define GNSS_TX_PIN 21
#define GNSS_BAUD   115200
#define USB_BAUD    115200

HardwareSerial GNSS(1);

void setup() {
  Serial.begin(USB_BAUD);
  GNSS.begin(GNSS_BAUD, SERIAL_8N1, GNSS_RX_PIN, GNSS_TX_PIN);
}

void loop() {
  // GNSS -> USB
  while (GNSS.available()) {
    Serial.write(GNSS.read());
  }

  // USB -> GNSS
  while (Serial.available()) {
    GNSS.write(Serial.read());
  }
}