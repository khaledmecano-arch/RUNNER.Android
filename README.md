# RUNNER BLE Controller

A native Kotlin Android Studio project for Android 10 (API 29) and newer.

## Features
- BLE scanning and device selection
- GATT connection and service discovery
- Selectable writable characteristic for OPEN, UNLOCK and custom UTF-8 commands
- Notification/indication setup using the Client Characteristic Configuration Descriptor (0x2902)
- Timestamped TX/RX, notification, discovery and error logs
- Chooses write-with-response when supported; otherwise write-without-response

## Build
1. Open this folder in Android Studio (Giraffe or newer; recent version recommended).
2. Allow Gradle sync and install the Android SDK Platform 35 if prompted.
3. Connect an Android phone, enable Bluetooth, then Run.
4. Grant Bluetooth permissions. On Android 10/11, grant Location permission and enable device Location for BLE scanning.

## Use
1. Tap **Scan for devices**, select RUNNER, then tap **Connect to selected device**.
2. Wait for service discovery.
3. Select a writable characteristic in **Command characteristic**.
4. Tap **Send OPEN** or **Send UNLOCK**, or enter a custom UTF-8 command.
5. Select a notify/indicate characteristic and enable notifications to inspect device responses.

## UUIDs seen in the supplied nRF Connect log
Service: `0000fe20-0000-1000-8000-00805f9b34fb`

- FE21: Read, Write, Write Without Response
- FE22: Notify, Write, Write Without Response
- FE23: Notify, Write, Write Without Response
- FE25: Notify, Read
- FE27: Notify, Read, Write
- FE28: Notify, Read, Write
- FE29: Read

The app discovers characteristics dynamically and enables controls based on their actual GATT properties, rather than assuming that every device revision has identical properties.

## Important protocol notes
- The log proves that GATT writes to several FE20 characteristics were accepted, but does **not** prove that OPEN/UNLOCK performed an action.
- The correct characteristic and command payload are not yet confirmed. Use the selectable characteristic to test only on a device you own or are authorized to operate.
- Do not repeatedly send OPEN/UNLOCK blindly. Observe notifications and device behavior; some devices may disconnect after a valid command.
- Android BLE operations are asynchronous. The log records Android's immediate acceptance and later GATT callbacks separately.
- No PIN, pairing bypass, encryption bypass, or undocumented exploit is implemented.

## Compatibility
- minSdk 29 (Android 10)
- targetSdk 35
- Kotlin, no third-party runtime dependencies
