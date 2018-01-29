package jtmorris.info.supplycontroller;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;

import com.macroyau.blue2serial.BluetoothDeviceListDialog;
import com.macroyau.blue2serial.BluetoothSerial;
import com.macroyau.blue2serial.BluetoothSerialRawListener;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements BluetoothSerialRawListener, BluetoothDeviceListDialog.OnDeviceSelectedListener {

    private static final int REQUEST_ENABLE_BLUETOOTH = 1;
    //    private BluetoothAdapter BA;
//    private Button bluetooth_on_btn;
//    private Button bluetooth_search_btn;
//    private ListView result_list;
//    private Set<BluetoothDevice>pairedDevices;
    /*	Command Set				Returns
     * 0x01 +4B Set Voltage             0
     * 0x02 +4B Set Current             0
     * 0x03     Get Voltage SP          4B float
     * 0x04     Get Current SP          4B float
     * 0x05     Return State Struct     TBD
     * 0x06     Get Measured Voltage    4B float
     * 0x07     Get Measured Current    4B float
     * 0x08     Get Battery Voltage     4B float
     * All responses followed by new line
     */
    private static final Byte[] CMD_SET_VOLTAGE = {0x01};
    private static final Byte[] CMD_SET_CURRENT = {0x02};
    private static final Byte[] CMD_GET_VOLTAGESP = {0x03};
    private static final Byte[] CMD_GET_CURRENTSP = {0x04};
    private static final Byte[] CMD_RTN_STATE = {0x05};
    private static final Byte[] CMD_GET_VOLTAGE = {0x06};
    private static final Byte[] CMD_GET_CURRENT = {0x07};
    private static final Byte[] CMD_GET_VBATT = {0x08};
    private static final Byte[] CR_LF = {13, 10};

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    public float voltageSP;
    public float currentSP;
    private BluetoothSerial bluetoothSerial;
    private MenuItem actionConnect, actionDisconnect;
    private Handler handler = new Handler();
    private int delay = 1000; //milliseconds
    private int state_expected = 0;
    // 0 - none + CRLF
    // 1 - 4B float + CRLF
    // 2 - State Struct + CRLF
    private boolean data_requested; // prevents multiple stacked requests/commands
    private int poll_number = 0; // decides which data to poll this request.
    private int expected_val = 0;

    private Byte[] cmd_waiting;
    private EditText VSetText = findViewById(R.id.OutVoltageSet);
    private EditText ISetText = findViewById(R.id.OutCurrentSet);
    private TextWatcher tx = new TextWatcher() {
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

            // TODO Auto-generated method stub
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            // TODO Auto-generated method stub
        }

        @Override
        public void afterTextChanged(Editable s) {
            voltageSP = Float.parseFloat(VSetText.getText().toString());
            currentSP = Float.parseFloat(ISetText.getText().toString());
            // TODO : Send commands to controller to update set points.

            // TODO Auto-generated method stub
        }
    };

    /* End of the implementation of listeners */
    // Array concat implementation
    public static <T> T[] concatAll(T[] first, T[]... rest) {
        int totalLength = first.length;
        for (T[] array : rest) {
            totalLength += array.length;
        }
        T[] result = Arrays.copyOf(first, totalLength);
        int offset = first.length;
        for (T[] array : rest) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Check Bluetooth availability on the device and set up the Bluetooth adapter
        bluetoothSerial.setup();

    }

    @Override
    protected void onResume() {
        super.onResume();

        // Open a Bluetooth serial port and get ready to establish a connection
        if (bluetoothSerial.checkBluetooth() && bluetoothSerial.isBluetoothEnabled()) {
            if (!bluetoothSerial.isConnected()) {
                bluetoothSerial.start();

            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Disconnect from the remote device and close the serial port
        bluetoothSerial.stop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ENABLE_BLUETOOTH:
                // Set up Bluetooth serial port when Bluetooth adapter is turned on
                if (resultCode == Activity.RESULT_OK) {
                    bluetoothSerial.setup();
                }
                break;
        }
    }

    private void updateBluetoothState() {
        // Get the current Bluetooth state
        final int state;
        if (bluetoothSerial != null)
            state = bluetoothSerial.getState();
        else
            state = BluetoothSerial.STATE_DISCONNECTED;

        // Display the current state on the app bar as the subtitle
        String subtitle;
        switch (state) {
            case BluetoothSerial.STATE_CONNECTING:
                subtitle = getString(R.string.status_connecting);
                break;
            case BluetoothSerial.STATE_CONNECTED:
                subtitle = getString(R.string.status_connected, bluetoothSerial.getConnectedDeviceName());
                break;
            default:
                subtitle = getString(R.string.status_disconnected);
                break;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(subtitle);
        }
        handler.postDelayed(new Runnable() {
            public void run() {
                if (bluetoothSerial != null
                        && bluetoothSerial.getState() == BluetoothSerial.STATE_CONNECTED
                        && !data_requested)
                // Only request data if BT is connected, and no other states are waiting.
                {

                    //Get State Struct
                    byte[] toSend;
                    switch (poll_number) {
                        case 0:
                            toSend = toPrimitives(concatAll(CMD_RTN_STATE, CR_LF));
                            expected_val = 0;
                            state_expected = 2;
                            break;
                        case 1:
                            toSend = toPrimitives(concatAll(CMD_GET_VOLTAGE, CR_LF));
                            expected_val = (int) CMD_GET_VOLTAGE[0];
                            state_expected = 1;
                            break;
                        case 2:
                            toSend = toPrimitives(concatAll(CMD_GET_CURRENT, CR_LF));
                            expected_val = (int) CMD_GET_CURRENT[0];
                            state_expected = 1;
                            break;
                        case 3:
                            toSend = toPrimitives(concatAll(CMD_GET_VBATT, CR_LF));
                            expected_val = (int) CMD_GET_VBATT[0];
                            state_expected = 1;
                            break;
                        case 4:
                            toSend = toPrimitives(concatAll(CMD_GET_VOLTAGESP, CR_LF));
                            expected_val = (int) CMD_GET_VOLTAGESP[0];
                            state_expected = 1;
                            break;
                        case 5:
                            toSend = toPrimitives(concatAll(CMD_GET_CURRENTSP, CR_LF));
                            expected_val = (int) CMD_GET_CURRENTSP[0];
                            state_expected = 1;
                            break;
                        default:
                            return;
                    }
                    bluetoothSerial.write(toSend);
                    data_requested = true;
                    poll_number++;
                } else {
                    handler.removeCallbacks(this);
                    // stop polling if bluetooth is disconnected or still waiting for response.
                    //@TODO Notify user of error.
                }
                handler.postDelayed(this, delay);
            }
        }, delay);

    }

    private void showDeviceListDialog() {
        // Display dialog for selecting a remote Bluetooth device
        BluetoothDeviceListDialog dialog = new BluetoothDeviceListDialog(this);
        dialog.setOnDeviceSelectedListener(this);
        dialog.setTitle(R.string.paired_devices);
        dialog.setDevices(bluetoothSerial.getPairedDevices());
        dialog.showAddress(true);
        dialog.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Create a new instance of BluetoothSerial
        bluetoothSerial = new BluetoothSerial(this, this);


        VSetText.addTextChangedListener(tx);
        ISetText.addTextChangedListener(tx);

//        bluetooth_on_btn = (Button) findViewById(R.id.Bluetoothon);
//        bluetooth_search_btn = (Button) findViewById(R.id.BluetoothScan);
//        result_list = (ListView) findViewById(R.id.result_list);
//
//        BA = BluetoothAdapter.getDefaultAdapter();
//        if(BA.isEnabled())
//        {
//            bluetooth_on_btn.setEnabled(false);
//            bluetooth_search_btn.setEnabled(true);
//        }
//        else
//        {
//            bluetooth_on_btn.setEnabled(true);
//            bluetooth_search_btn.setEnabled(false);
//        }


//        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
//        fab.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                        .setAction("Action", null).show();
//            }
//        });
//
//        // Example of a call to a native method
//        TextView tv = (TextView) findViewById(R.id.sample_text);
//        tv.setText(stringFromJNI());
    }

//    public void on_Bluetooth_btn_press(View v)
//    {
//        if (!BA.isEnabled()) {
//            Intent turnOn = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//            startActivityForResult(turnOn, 0);
//            Toast.makeText(getApplicationContext(), "Turned on",Toast.LENGTH_LONG).show();
//            bluetooth_on_btn.setEnabled(false);
//            bluetooth_search_btn.setEnabled(true);
//        } else {
//            Toast.makeText(getApplicationContext(), "Already on", Toast.LENGTH_LONG).show();
//        }
//    }
//    public void on_bluetooth_search_btn_press(View v)
//    {
//        pairedDevices = BA.getBondedDevices();
//
//        ArrayList list = new ArrayList();
//
//        for(BluetoothDevice bt : pairedDevices) list.add(bt.getName());
//        Toast.makeText(getApplicationContext(), "Showing Paired Devices",Toast.LENGTH_SHORT).show();
//
//        final ArrayAdapter adapter = new  ArrayAdapter(this,android.R.layout.simple_list_item_1, list);
//
//        result_list.setAdapter(adapter);
//        result_list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
//            public void onItemClick(AdapterView parentView, View childView,int position, long id) {
//                // Connect to that device
//                Object o = result_list.getItemAtPosition(position);
//                Toast.makeText(getApplicationContext(), "Selected device " + o.toString() , Toast.LENGTH_SHORT).show();
//            }
//        });
//    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        actionConnect = menu.findItem(R.id.actionConnect);
        actionDisconnect = menu.findItem(R.id.actionDisconnect);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.actionConnect) {
            showDeviceListDialog();
            return true;
        } else if (id == R.id.actionDisconnect) {
            bluetoothSerial.stop();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void invalidateOptionsMenu() {
        if (bluetoothSerial == null)
            return;

        // Show or hide the "Connect" and "Disconnect" buttons on the app bar
        if (bluetoothSerial.isConnected()) {
            if (actionConnect != null)
                actionConnect.setVisible(false);
            if (actionDisconnect != null)
                actionDisconnect.setVisible(true);
        } else {
            if (actionConnect != null)
                actionConnect.setVisible(true);
            if (actionDisconnect != null)
                actionDisconnect.setVisible(false);
        }
    }

    @Override
    public void onBluetoothNotSupported() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.no_bluetooth)
                .setPositiveButton(R.string.action_quit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onBluetoothDisabled() {
        Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBluetooth, REQUEST_ENABLE_BLUETOOTH);
    }

    @Override
    public void onBluetoothDeviceDisconnected() {
        invalidateOptionsMenu();
        updateBluetoothState();
    }

    @Override
    public void onConnectingBluetoothDevice() {
        updateBluetoothState();
    }

    @Override
    public void onBluetoothDeviceConnected(String name, String address) {
        invalidateOptionsMenu();
        updateBluetoothState();
    }

    @Override
    public void onBluetoothSerialRead(String message) {
        // Print the incoming message on the terminal screen
        // @TODO process incoming data
    }

    public void onBluetoothSerialReadRaw(byte[] data) {
        // Print the incoming message on the terminal screen
        // @TODO process incoming data
        if (!data_requested) //process as a incoming notification not a requested data packet
        {

        } else switch (state_expected) {
            case 0: //CRLF only
                if (data[0] != 13 || data[1] != 10 || data.length != 2) {
                    // Not as expected, throw exception
                    throw new IllegalStateException("Unexpected or corrupted packet");
                } else {
                    data_requested = false; // reset flag to allow more requests.
                }
                break;
            case 1: //4B Float + CRLF
                if (data[4] != 13 || data[5] != 10 || data.length != 6) {
                    // Not as expected, throw exception
                    throw new IllegalStateException("Unexpected or corrupted packet");
                } else {
                    byte[] just_data = new byte[4];
                    for (int i = 1; i < data.length - 2; i++) {
                        just_data[i] = data[i];
                    }
                    ByteBuffer buf = ByteBuffer.wrap(just_data);
                    float res = buf.getFloat();
                    switch (expected_val) {
                        case 0x03: //voltage sp
                            //Do Something
                            voltageSP = res;
                            ((TextView) findViewById(R.id.OutVoltageSet)).setText(String.format("%f.2", res));
                            break;
                        case 0x04: //current sp
                            //Do Something
                            currentSP = res;
                            ((TextView) findViewById(R.id.OutCurrentSet)).setText(String.format("%f.2", res));
                            break;
                        case 0x06: //voltage
                            //Do Something
                            ((TextView) findViewById(R.id.VoltageDisp)).setText(String.format("%f.2 V", res));
                            break;
                        case 0x07: //current
                            //Do Something
                            ((TextView) findViewById(R.id.CurrentDisp)).setText(String.format("%f.2 A", res));
                            break;
                        case 0x08: //vbatt
                            //Do Something
                            ((TextView) findViewById(R.id.VBatt)).setText(String.format("%f.2 V", res));
                            if (res < 4) {
                                ((TextView) findViewById(R.id.VBatt)).setTextColor(Color.RED);
                            } else ((TextView) findViewById(R.id.VBatt)).setTextColor(Color.GREEN);
                            break;
                        default:
                            throw new IllegalStateException("Unexpected or corrupted packet");
                    }
                    data_requested = false;
                }
                break;
            case 2: // State struct 9B + CRLF
                if (data[9] != 13 || data[10] != 10 || data.length != 11) {
                    // Not as expected, throw exception
                    throw new IllegalStateException("Unexpected or corrupted packet");
                } else {

                }
                break;
        }


    }

    @Override
    public void onBluetoothSerialWrite(String message) {
        // Print the outgoing message on the terminal screen
        //@TODO Implement sending commands
    }

    /* Implementation of BluetoothDeviceListDialog.OnDeviceSelectedListener */

    @Override
    public void onBluetoothSerialWriteRaw(byte[] data) {
        // Print the outgoing message on the terminal screen
        //@TODO Implement sending commands
    }

    @Override
    public void onBluetoothDeviceSelected(BluetoothDevice device) {
        // Connect to the selected remote Bluetooth device
        bluetoothSerial.connect(device);
    }


    // Byte to byte
    byte[] toPrimitives(Byte[] oBytes) {

        byte[] bytes = new byte[oBytes.length];
        for (int i = 0; i < oBytes.length; i++) {
            bytes[i] = oBytes[i];
        }
        return bytes;

    }

}
