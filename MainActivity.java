package com.example.runner;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("0000fe20-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID = UUID.fromString("0000fe22-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Captured from the user's nRF Connect tests: 18 bytes each.
    private static final byte[] REAR_SEQUENCE = new byte[] {
        0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] FRONT_SEQUENCE = new byte[] {
        0x41, 0x40, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41
    };

    private TextView status;
    private TextView log;
    private Button scanButton, rearButton, frontButton;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private boolean firstSequenceSent = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        updateButtons();
        requestLocationPermissionIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(0xFFF5F5F5);

        TextView title = new TextView(this);
        title.setText("RUNNER — Commande Bluetooth");
        title.setTextSize(23);
        title.setTextColor(0xFF111111);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("État : déconnecté");
        status.setTextSize(16);
        status.setPadding(0, 22, 0, 18);
        root.addView(status);

        scanButton = new Button(this);
        scanButton.setText("Rechercher et connecter RUNNER");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton);

        rearButton = new Button(this);
        rearButton.setText("Envoyer la séquence 1 (arrière)");
        rearButton.setOnClickListener(v -> confirmAndSend(false));
        root.addView(rearButton);

        frontButton = new Button(this);
        frontButton.setText("Envoyer la séquence 2 (avant)");
        frontButton.setOnClickListener(v -> confirmAndSend(true));
        root.addView(frontButton);

        TextView warning = new TextView(this);
        warning.setText("Attention : la séquence 1 a un comportement dépendant de l’état des verrous. Elle peut aussi agir sur le verrou avant. Teste uniquement lorsque tu peux observer les deux verrous.");
        warning.setTextSize(14);
        warning.setTextColor(0xFF9A3B00);
        warning.setPadding(0, 16, 0, 16);
        root.addView(warning);

        TextView logTitle = new TextView(this);
        logTitle.setText("Journal Bluetooth");
        logTitle.setTextSize(17);
        root.addView(logTitle);

        log = new TextView(this);
        log.setText("En attente…");
        log.setTextIsSelectable(true);
        log.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void requestLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 30 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10);
        }
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT > 30 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Active le Bluetooth du téléphone.");
            return;
        }
        if (!hasScanPermission()) {
            requestLocationPermissionIfNeeded();
            setStatus("Autorise la localisation pour rechercher les appareils Bluetooth.");
            return;
        }
        closeGatt();
        firstSequenceSent = false;
        commandCharacteristic = null;
        updateButtons();
        setStatus("Recherche de RUNNER…");
        addLog("Démarrage du scan BLE (10 secondes).");

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("Scanner Bluetooth indisponible.");
            return;
        }
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            if (gatt == null) setStatus("RUNNER non trouvé. Réessaie près du verrou.");
        }, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if ((name != null && name.equalsIgnoreCase("RUNNER")) ||
                    "D8:A9:8B:CF:0D:0A".equalsIgnoreCase(device.getAddress())) {
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) scanner.stopScan(this);
                addLog("RUNNER trouvé : " + device.getAddress());
                setStatus("Connexion à RUNNER…");
                gatt = device.connectGatt(MainActivity.this, false, gattCallback);
            }
        }
        @Override public void onScanFailed(int errorCode) {
            setStatus("Échec du scan BLE : " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int statusCode, int newState) {
            runOnUiThread(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("Connecté. Recherche des services…");
                    addLog("Connexion BLE établie.");
                    bluetoothGatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    setStatus("Déconnecté.");
                    commandCharacteristic = null;
                    firstSequenceSent = false;
                    updateButtons();
                    addLog("Connexion interrompue (status " + statusCode + ").");
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int statusCode) {
            BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> setStatus("Service FE20 introuvable."));
                return;
            }
            commandCharacteristic = service.getCharacteristic(CHAR_UUID);
            if (commandCharacteristic == null) {
                runOnUiThread(() -> setStatus("Caractéristique FE22 introuvable."));
                return;
            }
            bluetoothGatt.setCharacteristicNotification(commandCharacteristic, true);
            BluetoothGattDescriptor descriptor = commandCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothGatt.writeDescriptor(descriptor);
            }
            runOnUiThread(() -> {
                setStatus("RUNNER connecté — prêt.");
                addLog("Service FE20 et caractéristique FE22 trouvés.");
                updateButtons();
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            runOnUiThread(() -> addLog("Notification : " + toHex(value)));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic, int statusCode) {
            runOnUiThread(() -> addLog(statusCode == BluetoothGatt.GATT_SUCCESS
                    ? "Écriture BLE confirmée." : "Erreur d’écriture BLE : " + statusCode));
        }
    };

    private void confirmAndSend(boolean front) {
        if (commandCharacteristic == null || gatt == null) {
            Toast.makeText(this, "Connecte d’abord RUNNER.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (front && !firstSequenceSent) {
            Toast.makeText(this, "Envoie d’abord la séquence 1.", Toast.LENGTH_LONG).show();
            return;
        }
        String message = front
                ? "Envoyer la séquence 2 au verrou avant ? Vérifie que le verrou est dans le bon état."
                : "Envoyer la séquence 1 ? Cette commande peut modifier l’état d’un verrou. Confirmer uniquement si tu observes le mécanisme.";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmer l’envoi")
                .setMessage(message)
                .setNegativeButton("Annuler", (d, w) -> {})
                .setPositiveButton("Envoyer", (d, w) -> send(front ? FRONT_SEQUENCE : REAR_SEQUENCE, front))
                .show();
    }

    private void send(byte[] payload, boolean front) {
        if (gatt == null || commandCharacteristic == null) return;
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        commandCharacteristic.setValue(payload);
        boolean accepted = gatt.writeCharacteristic(commandCharacteristic);
        if (accepted) {
            if (!front) firstSequenceSent = true;
            addLog("Envoi " + (front ? "séquence avant" : "séquence 1") + " : " + toHex(payload));
            updateButtons();
        } else {
            addLog("Le téléphone a refusé de lancer l’écriture BLE.");
        }
    }

    private void updateButtons() {
        boolean connected = commandCharacteristic != null && gatt != null;
        if (rearButton != null) rearButton.setEnabled(connected);
        if (frontButton != null) frontButton.setEnabled(connected && firstSequenceSent);
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    private void setStatus(String value) {
        runOnUiThread(() -> status.setText("État : " + value));
    }

    private void addLog(String value) {
        runOnUiThread(() -> log.append("\n" + value));
    }

    private String toHex(byte[] bytes) {
        if (bytes == null) return "(vide)";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    @Override protected void onDestroy() {
        closeGatt();
        super.onDestroy();
    }
}
