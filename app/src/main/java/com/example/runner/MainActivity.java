
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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

    private static final int BLACK = Color.rgb(8, 10, 16);
    private static final int PANEL = Color.rgb(20, 25, 36);
    private static final int BLUE = Color.rgb(0, 145, 255);
    private static final int WHITE = Color.WHITE;
    private static final int GREY = Color.rgb(180, 190, 205);

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000fe20-0000-1000-8000-00805f9b34fb");

    private static final UUID FE21_UUID =
            UUID.fromString("0000fe21-0000-1000-8000-00805f9b34fb");

    private static final UUID CHAR_UUID =
            UUID.fromString("0000fe22-0000-1000-8000-00805f9b34fb");

    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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
    private TextView weightText;

    private Button scanButton;
    private Button rearButton;
    private Button frontButton;
    private Button fe21Button;
    private Button weighButton;
    private Button calibrateButton;

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;

    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic fe21Characteristic;

    private boolean firstSequenceSent = false;
    private boolean weighing = false;

    private String currentStatus = "Déconnecté";
    private String currentWeight = "Poids : -- kg";

    private final StringBuilder logHistory = new StringBuilder("En attente…");

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Handler weighHandler =
            new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        adapter = manager.getAdapter();

        showHome();

        updateButtons();
        requestLocationPermissionIfNeeded();
    }

    // ---------------------------------------------------------
    // INTERFACE
    // ---------------------------------------------------------

    private LinearLayout createRoot() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 22, 22, 22);
        root.setBackgroundColor(BLACK);

        return root;
    }

    private TextView createText(String value, int size, int color) {

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);

        return text;
    }

    private Button createButton(String value) {

        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(
                ColorStateList.valueOf(BLUE)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(0, 9, 0, 9);
        button.setLayoutParams(params);

        return button;
    }

    private TextView createHeader(String value) {

        TextView title = createText(value, 25, BLUE);
        title.setPadding(0, 12, 0, 18);

        return title;
    }

    private void showHome() {

        LinearLayout root = createRoot();

        TextView title = createHeader("PLEVO RUNNER");
        root.addView(title);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(15, 25, 15, 25);

        GradientDrawable heroBackground = new GradientDrawable();
        heroBackground.setColor(PANEL);
        heroBackground.setCornerRadius(24);
        heroBackground.setStroke(2, BLUE);
        hero.setBackground(heroBackground);

        TextView suitcase = createText("🧳", 68, WHITE);
        hero.addView(suitcase);

        TextView subtitle =
                createText("SMART LUGGAGE CONTROL", 14, GREY);
        subtitle.setPadding(0, 8, 0, 0);
        hero.addView(subtitle);

        root.addView(hero);

        status = createText(
                "État : " + currentStatus,
                15,
                GREY
        );
        status.setPadding(0, 18, 0, 12);
        root.addView(status);

        scanButton = createButton("BLUETOOTH — CONNECTER RUNNER");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton);

        Button lockMenu = createButton("VERROUILLAGE");
        lockMenu.setOnClickListener(v -> showLockMenu());
        root.addView(lockMenu);

        Button weightMenu = createButton("PESÉE");
        weightMenu.setOnClickListener(v -> showWeightMenu());
        root.addView(weightMenu);

        fe21Button = createButton("INSECURE");
        fe21Button.setOnClickListener(v -> confirmFe21PowerOff());
        root.addView(fe21Button);

        TextView logTitle =
                createText("JOURNAL BLUETOOTH", 16, BLUE);
        logTitle.setPadding(0, 20, 0, 8);
        root.addView(logTitle);

        log = createText(logHistory.toString(), 12, GREY);
        log.setGravity(Gravity.START);
        log.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
        updateButtons();
    }

    private void showLockMenu() {

        LinearLayout root = createRoot();

        root.addView(createHeader("VERROUILLAGE"));

        TextView description =
                createText("Commandes des verrous de la valise", 15, GREY);
        root.addView(description);

        root.addView(createText("VERROU ARRIÈRE", 18, WHITE));

        rearButton = createButton("ACTIVER — ARRIÈRE");
        rearButton.setOnClickListener(v -> confirmAndSend(false));
        root.addView(rearButton);

        root.addView(createText("VERROU AVANT", 18, WHITE));

        frontButton = createButton("ACTIVER — AVANT");
        frontButton.setOnClickListener(v -> confirmAndSend(true));
        root.addView(frontButton);

        Button back = createButton("RETOUR");
        back.setOnClickListener(v -> showHome());
        root.addView(back);

        setContentView(root);
        updateButtons();
    }

    private void showWeightMenu() {

        LinearLayout root = createRoot();

        root.addView(createHeader("PESÉE"));

        TextView description =
                createText("Mesure du poids de votre valise", 15, GREY);
        root.addView(description);

        weightText = createText(currentWeight, 30, BLUE);
        weightText.setPadding(0, 35, 0, 35);
        root.addView(weightText);

        weighButton = createButton(
                weighing ? "ARRÊTER LA PESÉE" : "DÉMARRER LA PESÉE"
        );
        weighButton.setOnClickListener(v -> startOrStopWeighing());
        root.addView(weighButton);

        calibrateButton = createButton("CALIBRER LA PESÉE");
        calibrateButton.setOnClickListener(v -> calibrateWeight());
        root.addView(calibrateButton);

        TextView warning =
                createText(
                        "Pour calibrer, placez la valise vide sur une surface plane.",
                        13,
                        GREY
                );
        warning.setPadding(0, 20, 0, 20);
        root.addView(warning);

        Button back = createButton("RETOUR");
        back.setOnClickListener(v -> showHome());
        root.addView(back);

        setContentView(root);
        updateButtons();
    }

    // ---------------------------------------------------------
    // AUTORISATIONS ET BLUETOOTH
    // ---------------------------------------------------------

    private void requestLocationPermissionIfNeeded() {

        if (Build.VERSION.SDK_INT >= 23 &&
                Build.VERSION.SDK_INT <= 30 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    10
            );
        }
    }

    private boolean hasScanPermission() {

        return Build.VERSION.SDK_INT > 30 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {

        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Active le Bluetooth du téléphone.");
            return;
        }

        if (!hasScanPermission()) {
            requestLocationPermissionIfNeeded();
            setStatus("Autorise la localisation pour rechercher RUNNER.");
            return;
        }

        stopWeighing();
        closeGatt();

        firstSequenceSent = false;
        commandCharacteristic = null;
        fe21Characteristic = null;

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

            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }

            if (gatt == null) {
                setStatus("RUNNER non trouvé. Réessaie.");
            }

        }, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            BluetoothDevice device = result.getDevice();
            String name = device.getName();

            if ((name != null && name.equalsIgnoreCase("RUNNER")) ||
                    "D8:A9:8B:CF:0D:0A".equalsIgnoreCase(device.getAddress())) {

                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();

                if (scanner != null) {
                    scanner.stopScan(this);
                }

                addLog("RUNNER trouvé : " + device.getAddress());
                setStatus("Connexion à RUNNER…");

                gatt = device.connectGatt(
                        MainActivity.this,
                        false,
                        gattCallback
                );
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            setStatus("Échec du scan BLE : " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(
                BluetoothGatt bluetoothGatt,
                int statusCode,
                int newState) {

            runOnUiThread(() -> {

                if (newState == BluetoothProfile.STATE_CONNECTED) {

                    setStatus("Connecté. Recherche des services…");
                    addLog("Connexion BLE établie.");

                    bluetoothGatt.discoverServices();

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                    stopWeighing();

                    setStatus("Déconnecté.");

                    commandCharacteristic = null;
                    fe21Characteristic = null;
                    firstSequenceSent = false;

                    updateButtons();

                    addLog("Connexion interrompue (status "
                            + statusCode + ").");
                }
            });
        }

        @Override
        public void onServicesDiscovered(
                BluetoothGatt bluetoothGatt,
                int statusCode) {

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() ->
                        setStatus("Erreur de recherche des services."));
                return;
            }

            BluetoothGattService service =
                    bluetoothGatt.getService(SERVICE_UUID);

            if (service == null) {
                runOnUiThread(() ->
                        setStatus("Service FE20 introuvable."));
                return;
            }

            commandCharacteristic =
                    service.getCharacteristic(CHAR_UUID);

            fe21Characteristic =
                    service.getCharacteristic(FE21_UUID);

            if (commandCharacteristic == null) {
                runOnUiThread(() ->
                        setStatus("Caractéristique FE22 introuvable."));
                return;
            }

            bluetoothGatt.setCharacteristicNotification(
                    commandCharacteristic,
                    true
            );

            if (fe21Characteristic != null) {
                bluetoothGatt.setCharacteristicNotification(
                        fe21Characteristic,
                        true
                );
            }

            BluetoothGattDescriptor descriptor =
                    commandCharacteristic.getDescriptor(CCCD_UUID);

            if (descriptor != null) {

                descriptor.setValue(
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                );

                bluetoothGatt.writeDescriptor(descriptor);
            }

            runOnUiThread(() -> {

                setStatus("RUNNER connecté — prêt.");

                addLog("Service FE20 et caractéristique FE22 trouvés.");

                if (fe21Characteristic != null) {
                    addLog("Caractéristique FE21 trouvée.");
                    addLog("Propriétés FE21 : "
                            + fe21Characteristic.getProperties());
                } else {
                    addLog("FE21 introuvable.");
                }

                updateButtons();
            });
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt bluetoothGatt,
                BluetoothGattDescriptor descriptor,
                int status) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() ->
                        addLog("Erreur activation notifications : " + status));
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    descriptor.getCharacteristic();

            if (CHAR_UUID.equals(characteristic.getUuid())) {

                if (fe21Characteristic != null) {

                    BluetoothGattDescriptor fe21Descriptor =
                            fe21Characteristic.getDescriptor(CCCD_UUID);

                    if (fe21Descriptor != null) {

                        fe21Descriptor.setValue(
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        );

                        boolean started =
                                bluetoothGatt.writeDescriptor(fe21Descriptor);

                        runOnUiThread(() -> addLog(started
                                ? "Activation notifications FE21 demandée."
                                : "Impossible d'activer les notifications FE21."));
                    }
                }

            } else if (FE21_UUID.equals(characteristic.getUuid())) {

                runOnUiThread(() ->
                        addLog("Notifications FE21 activées."));
            }
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic) {

            byte[] value = characteristic.getValue();

            if (value == null) {
                return;
            }

            if (CHAR_UUID.equals(characteristic.getUuid())) {

                byte[] decoded = decodePlevoPacket(value);

                runOnUiThread(() -> {

                    addLog("FE22 reçu : " + toHex(value));

                    if (decoded == null) {
                        addLog("Paquet FE22 non validé.");
                        return;
                    }

                    addLog("FE22 décodé : " + toHex(decoded));

                    if (decoded.length >= 4) {

                        int rawWeight =
                                ((decoded[2] & 0xFF) << 8)
                                | (decoded[3] & 0xFF);

                        float kg = rawWeight / 1000.0f;

                        currentWeight = String.format(
                                java.util.Locale.US,
                                "Poids : %.3f kg",
                                kg
                        );

                        if (weightText != null) {
                            weightText.setText(currentWeight);
                        }
                    }
                });

            } else {

                runOnUiThread(() ->
                        addLog("Notification "
                                + characteristic.getUuid()
                                + " : " + toHex(value)));
            }
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic,
                int statusCode) {

            runOnUiThread(() ->
                    addLog(statusCode == BluetoothGatt.GATT_SUCCESS
                            ? "Écriture BLE confirmée."
                            : "Erreur d’écriture BLE : " + statusCode));
        }
    };

    // ---------------------------------------------------------
    // VERROUS
    // ---------------------------------------------------------

    private void confirmAndSend(boolean front) {

        if (commandCharacteristic == null || gatt == null) {
            Toast.makeText(this,
                    "Connecte d’abord RUNNER.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (front && !firstSequenceSent) {
            Toast.makeText(this,
                    "Envoie d’abord la séquence 1.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String message = front
                ? "Envoyer la séquence 2 au verrou avant ?"
                : "Envoyer la séquence 1 ?";

        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmer l’envoi")
                .setMessage(message)
                .setNegativeButton("Annuler", (d, w) -> {
                })
                .setPositiveButton("Envoyer",
                        (d, w) -> send(
                                front ? FRONT_SEQUENCE : REAR_SEQUENCE,
                                front
                        ))
                .show();
    }

    private void send(byte[] payload, boolean front) {

        if (gatt == null || commandCharacteristic == null) {
            return;
        }

        commandCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        );

        commandCharacteristic.setValue(payload);

        boolean accepted =
                gatt.writeCharacteristic(commandCharacteristic);

        if (accepted) {

            if (!front) {
                firstSequenceSent = true;
            }

            addLog("Envoi "
                    + (front ? "séquence avant" : "séquence arrière")
                    + " : " + toHex(payload));

            updateButtons();

        } else {
            addLog("Le téléphone a refusé l’écriture BLE.");
        }
    }

    // ---------------------------------------------------------
    // FE21 — INSECURE
    // ---------------------------------------------------------

    private void confirmFe21PowerOff() {

        if (gatt == null || fe21Characteristic == null) {
            Toast.makeText(this,
                    "FE21 indisponible. Connecte d’abord RUNNER.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Commande FE21")
                .setMessage(
                        "Envoyer la commande PLEVO de désactivation " +
                        "de l’anti-perte ?"
                )
                .setNegativeButton("Annuler", (d, w) -> {
                })
                .setPositiveButton("Envoyer",
                        (d, w) -> sendFe21PowerOff())
                .show();
    }

    private void sendFe21PowerOff() {

        if (gatt == null || fe21Characteristic == null) {
            return;
        }

        byte[] plainCommand = new byte[] {
                0x00, 0x00, 0x00, 0x00
        };

        byte[] encodedCommand =
                encodePlevoPacket(plainCommand);

        fe21Characteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        );

        fe21Characteristic.setValue(encodedCommand);

        boolean accepted =
                gatt.writeCharacteristic(fe21Characteristic);

        if (accepted) {
            addLog("FE21 — commande PLEVO : "
                    + toHex(encodedCommand));
        } else {
            addLog("Le téléphone a refusé l’écriture sur FE21.");
        }
    }

    // ---------------------------------------------------------
    // ENCODAGE ET DÉCODAGE PLEVO
    // ---------------------------------------------------------

    private byte[] encodePlevoPacket(byte[] input) {

        byte[] key = new byte[] {
                'A', 'n', 't', 'i', 'l', 'o', 's', 't'
        };

        int checksum = 0;

        for (byte value : input) {

            int current = value & 0xFF;

            for (int bit = 0; bit < 8; bit++) {

                if ((current & 1) != 0) {
                    checksum ^= key[bit] & 0xFF;
                }

                current >>>= 1;
            }
        }

        byte[] output = new byte[input.length + 1];

        output[0] = (byte) checksum;

        for (int i = 0; i < input.length; i++) {
            output[i + 1] =
                    (byte) (checksum ^ (input[i] & 0xFF));
        }

        return output;
    }

    private byte[] prepareWeightCommand(byte[] command) {

        byte[] padded = new byte[17];

        System.arraycopy(
                command,
                0,
                padded,
                0,
                Math.min(command.length, padded.length)
        );

        return encodePlevoPacket(padded);
    }

    private byte[] decodePlevoPacket(byte[] packet) {

        if (packet == null || packet.length < 2) {
            return null;
        }

        int checksum = packet[0] & 0xFF;

        byte[] decoded = new byte[packet.length - 1];

        for (int i = 1; i < packet.length; i++) {
            decoded[i - 1] =
                    (byte) ((packet[i] & 0xFF) ^ checksum);
        }

        byte[] key = new byte[] {
                'A', 'n', 't', 'i', 'l', 'o', 's', 't'
        };

        int calculated = 0;

        for (byte value : decoded) {

            int current = value & 0xFF;

            for (int bit = 0; bit < 8; bit++) {

                if ((current & 1) != 0) {
                    calculated ^= key[bit] & 0xFF;
                }

                current >>>= 1;
            }
        }

        if (calculated != checksum) {
            return null;
        }

        return decoded;
    }

    // ---------------------------------------------------------
    // PESÉE ET CALIBRAGE
    // ---------------------------------------------------------

    private void startOrStopWeighing() {

        if (gatt == null || commandCharacteristic == null) {
            Toast.makeText(this,
                    "Connecte d'abord RUNNER.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (weighing) {
            stopWeighing();
            return;
        }

        weighing = true;

        if (weighButton != null) {
            weighButton.setText("ARRÊTER LA PESÉE");
        }

        sendWeightCommand(new byte[] {0x02, 0x00});

        addLog("Démarrage du cycle de pesée.");

        weighHandler.postDelayed(weightRunnable, 1000);
    }

    private final Runnable weightRunnable = new Runnable() {

        @Override
        public void run() {

            if (!weighing ||
                    gatt == null ||
                    commandCharacteristic == null) {
                return;
            }

            sendWeightCommand(new byte[] {0x02, 0x01});

            weighHandler.postDelayed(this, 1000);
        }
    };

    private void stopWeighing() {

        weighing = false;

        weighHandler.removeCallbacks(weightRunnable);

        if (weighButton != null) {
            weighButton.setText("DÉMARRER LA PESÉE");
        }
    }

    private void sendWeightCommand(byte[] command) {

        if (gatt == null || commandCharacteristic == null) {
            return;
        }

        byte[] packet = prepareWeightCommand(command);

        commandCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        );

        commandCharacteristic.setValue(packet);

        boolean accepted =
                gatt.writeCharacteristic(commandCharacteristic);

        addLog(accepted
                ? "Commande pesée FE22 : " + toHex(packet)
                : "Échec de l'envoi de la commande de pesée.");
    }

    private void calibrateWeight() {

        if (!weighing ||
                gatt == null ||
                commandCharacteristic == null) {

            Toast.makeText(this,
                    "Démarre d'abord la pesée.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Calibrage")
                .setMessage(
                        "Place la valise vide sur une surface plane " +
                        "avant de calibrer. Continuer ?"
                )
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer", (d, w) -> {

                    sendWeightCommand(new byte[] {0x02, 0x02});

                    addLog("Commande de calibrage envoyée.");
                })
                .show();
    }

    // ---------------------------------------------------------
    // ÉTAT DES BOUTONS ET JOURNAL
    // ---------------------------------------------------------

    private void updateButtons() {

        boolean connected =
                commandCharacteristic != null && gatt != null;

        if (rearButton != null) {
            rearButton.setEnabled(connected);
        }

        if (frontButton != null) {
            frontButton.setEnabled(
                    connected && firstSequenceSent
            );
        }

        if (fe21Button != null) {
            fe21Button.setEnabled(
                    fe21Characteristic != null && gatt != null
            );
        }

        if (weighButton != null) {
            weighButton.setEnabled(connected);
        }

        if (calibrateButton != null) {
            calibrateButton.setEnabled(connected);
        }
    }

    private void closeGatt() {

        if (gatt != null) {

            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }

            gatt = null;
        }
    }

    private void setStatus(String value) {

        currentStatus = value;

        runOnUiThread(() -> {
            if (status != null) {
                status.setText("État : " + currentStatus);
            }
        });
    }

    private void addLog(String value) {

        runOnUiThread(() -> {

            logHistory.append("\n").append(value);

            if (log != null) {
                log.setText(logHistory.toString());
            }
        });
    }

    private String toHex(byte[] bytes) {

        if (bytes == null) {
            return "(vide)";
        }

        StringBuilder sb = new StringBuilder();

        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }

        return sb.toString().trim();
    }

    @Override
    protected void onDestroy() {

        stopWeighing();

        handler.removeCallbacksAndMessages(null);

        closeGatt();

        super.onDestroy();
    }
}
