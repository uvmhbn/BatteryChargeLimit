package com.slash.batterychargelimit;

import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Menu;
import android.view.View;
import android.widget.*;
import eu.chainfire.libsuperuser.Shell;

import static com.slash.batterychargelimit.Constants.*;
import static com.slash.batterychargelimit.SharedMethods.CHARGE_ON;

public class MainActivity extends AppCompatActivity {
    private SeekBar rangeBar;
    private TextView rangeText;
    private TextView status_TextView;
    private EditText limit_TextView;
    private SharedPreferences settings;
    private RadioGroup batteryFile_RadioGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Exit immediately if no root support
        if (!Shell.SU.available()) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(R.string.root_denied)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            finish();
                        }
                    }).create().show();
            return;
        }

        settings = getSharedPreferences(SETTINGS, 0);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        boolean previouslyStarted = prefs.getBoolean(getString(R.string.previously_started), false);
        if (!previouslyStarted) {
            settings.edit()
                    .putInt(LIMIT, 80)
                    .putBoolean(LIMIT_REACHED, false)
                    .putBoolean(ENABLE, false).apply();
            // whitelist App for Doze Mode
            Shell.SU.run("dumpsys deviceidle whitelist +com.slash.batterychargelimit");
            prefs.edit().putBoolean(getString(R.string.previously_started), true).apply();
        }

        int settingsVersion = prefs.getInt(SETTINGS_VERSION, 0);
        int versionCode = 0;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace(); // should never happen
        }
        if (settingsVersion < versionCode) {
            switch(settingsVersion) {
                case 0:
                    SparseArray<ControlFile> modes = getCtrlFiles();
                    boolean found = false;
                    for (int i = 0; i < modes.size() && !found; i++) {
                        ControlFile cf = modes.valueAt(i);
                        if (cf.valid) {
                            setCtrlFile(cf);
                            found = true;
                        }
                    }
                    if (!found) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage(R.string.device_not_supported)
                                .setCancelable(false)
                                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        finish();
                                    }
                                }).create().show();
                        return;
                    }
                case 4:
                    if (settings.contains(LIMIT_REACHED)) {
                        settings.edit().remove(LIMIT_REACHED).apply();
                    }
                case 5:
                    // settings upgrade for future version(s)
            }
            // update the settings version
            prefs.edit().putInt(SETTINGS_VERSION, versionCode).apply();
        }

        int limit_percentage = settings.getInt(LIMIT, 80);
        boolean is_enabled = settings.getBoolean(ENABLE, false);

        if (is_enabled && SharedMethods.isPhonePluggedIn(this)) {
            this.startService(new Intent(this, ForegroundService.class));
        }//notif is 1!

        batteryFile_RadioGroup = (RadioGroup) findViewById(R.id.rgOpinion);
        final Switch enable_Switch = (Switch) findViewById(R.id.button1);
        limit_TextView = (EditText) findViewById(R.id.limit_EditText);
        status_TextView = (TextView) findViewById(R.id.status);
        final Button resetBatteryStats_Button = (Button) findViewById(R.id.reset_battery_stats);
        rangeBar = (SeekBar) findViewById(R.id.range_bar);
        rangeText = (TextView) findViewById(R.id.range_text);
        final Switch autoResetSwitch = (Switch) findViewById(R.id.auto_stats_reset);

        limit_TextView.setText(String.valueOf(limit_percentage));
        int rechargeDiff = settings.getInt(RECHARGE_DIFF, 2);
        rangeBar.setProgress(rechargeDiff);
        rangeText.setText(getString(R.string.recharge_below, limit_percentage - rechargeDiff));
        enable_Switch.setChecked(is_enabled);
        updateRadioButtons(true);
        autoResetSwitch.setChecked(settings.getBoolean(AUTO_RESET_STATS, false));

        // if limit is enabled, disable all editable settings
        if (is_enabled) {
            limit_TextView.setEnabled(false);
            rangeBar.setEnabled(false);
        }

        enable_Switch.setOnCheckedChangeListener(switchListener);
        limit_TextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                int t = 0;
                try {
                    t = Integer.parseInt(limit_TextView.getText().toString());
                } catch (NumberFormatException e) {
                    //ignore this exception
                }
                if (t >= 40 && t <= 99) {
                    settings.edit().putInt(LIMIT, t).apply();
                    rangeText.setText(getString(R.string.recharge_below,
                            t - settings.getInt(RECHARGE_DIFF, 2)));
                }
            }
        });
        rangeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int diff, boolean fromUser) {
                settings.edit().putInt(RECHARGE_DIFF, diff).apply();
                rangeText.setText(getString(R.string.recharge_below,
                        settings.getInt(LIMIT, 80) - diff));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        resetBatteryStats_Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedMethods.resetBatteryStats(MainActivity.this);
            }
        });
        autoResetSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.edit().putBoolean(AUTO_RESET_STATS, isChecked).apply();
            }
        });
    }

    //oncheckedchangelistener for switches
    private CompoundButton.OnCheckedChangeListener switchListener = new CompoundButton.OnCheckedChangeListener() {
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                // reset TextView content to valid number
                limit_TextView.setText(String.valueOf(settings.getInt(LIMIT, 80)));

                if (SharedMethods.isPhonePluggedIn(MainActivity.this)) {
                    MainActivity.this.startService(new Intent(MainActivity.this, ForegroundService.class));
                }
            } else {
                ForegroundService.ignoreAutoReset();
                MainActivity.this.stopService(new Intent(MainActivity.this, ForegroundService.class));
                SharedMethods.changeState(MainActivity.this, null, CHARGE_ON);
            }

            settings.edit().putBoolean(ENABLE, isChecked).apply();
            limit_TextView.setEnabled(!isChecked);
            updateRadioButtons(false);
            rangeBar.setEnabled(!isChecked);
        }
    };

    /**
     * This listener is bound via XML!
     *
     * @param view The view
     */
    public void onRadioButtonClicked(View view) {
        if (((RadioButton) view).isChecked()) {
            setCtrlFile(getCtrlFiles().get(view.getId()));
        }
    }

    private SparseIntArray getCtrlFileMapping() {
        SparseIntArray m = new SparseIntArray();
        m.put(R.id.batt_slate_mode, R.array.batt_slate_mode);
        m.put(R.id.store_mode, R.array.store_mode);
        m.put(R.id.battery_charging_enabled, R.array.battery_charging_enabled);
        m.put(R.id.charging_enabled, R.array.charging_enabled);
        return m;
    }

    private SparseArray<ControlFile> ctrlFiles = null;
    private SparseArray<ControlFile> getCtrlFiles() {
        if (ctrlFiles == null) {
            Resources res = getResources();
            SparseIntArray map = getCtrlFileMapping();
            SparseArray<ControlFile> cfMap = new SparseArray<>();
            for (int i = 0; i < map.size(); i++) {
                String[] mode = res.getStringArray(map.valueAt(i));
                cfMap.put(map.keyAt(i), new ControlFile(mode));
            }
            ctrlFiles = cfMap;
        }
        return ctrlFiles;
    }

    private void updateRadioButtons(boolean init) {
        SparseArray<ControlFile> ctrlFiles = getCtrlFiles();
        String currentCtrlFile = settings.getString(FILE_KEY, "");
        boolean isEnabled = settings.getBoolean(ENABLE, false);
        for (int i = 0; i < batteryFile_RadioGroup.getChildCount(); i++) {
            RadioButton b = (RadioButton) batteryFile_RadioGroup.getChildAt(i);
            ControlFile cf = ctrlFiles.get(b.getId());
            if (init) {
                b.setText(cf.label);
                b.setChecked(currentCtrlFile.equals(cf.file));
            }
            // if service is not active, enable control file radio button if the control file is valid
            b.setEnabled(!isEnabled && cf.valid);
        }
    }

    private void setCtrlFile(ControlFile cf) {
        getSharedPreferences(SETTINGS, 0)
                .edit().putString(FILE_KEY, cf.file)
                .putString(CHARGE_ON_KEY, cf.chargeOn)
                .putString(CHARGE_OFF_KEY, cf.chargeOff).apply();
    }

    //to update battery status on UI
    private BroadcastReceiver charging = new BroadcastReceiver() {
        private int previousStatus = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            int currentStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (currentStatus != previousStatus && status_TextView != null) {
                previousStatus = currentStatus;
                if (currentStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
                    status_TextView.setText(R.string.charging);
                    status_TextView.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.darkGreen));
                } else if (currentStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                    status_TextView.setText(R.string.discharging);
                    status_TextView.setTextColor(Color.DKGRAY);
                } else if (currentStatus == BatteryManager.BATTERY_STATUS_FULL) {
                    status_TextView.setText(R.string.full);
                } else if (currentStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
                    status_TextView.setText(R.string.not_charging);
                } else if (currentStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
                    status_TextView.setText(R.string.unknown);
                }
            }
        }
    };
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public void onStop() {
        unregisterReceiver(charging);
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceiver(charging, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
