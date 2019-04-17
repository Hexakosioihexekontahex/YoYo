package com.mdgd.yoyo;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import eu.faircode.netguard.ActivityMain;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private SharedPreferences prefs;
    private SwitchCompat swEnabled;
    private boolean running = false;
    private AlertDialog dialogVpn = null;
    private AlertDialog dialogDoze = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        running = true;
        setContentView(R.layout.activity_main);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final ComponentName component = getComponent(this);
        getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        swEnabled = findViewById(R.id.lol);
        swEnabled.setOnCheckedChangeListener(this);
    }

    private ComponentName getComponent(Context ctx) {
        return new ComponentName(ctx, ActivityMain.class);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        prefs.edit().putBoolean("enabled", isChecked).apply();
        if (isChecked) startVpn();
        else stopVpn();
    }

    private void stopVpn() {
        ServiceSinkhole.stop("switch off", MainActivity.this, false);
    }

    private void startVpn() {
        String alwaysOn = Settings.Secure.getString(getContentResolver(), "always_on_vpn_app");
        if (!TextUtils.isEmpty(alwaysOn))
            if (getPackageName().equals(alwaysOn)) {
                if (prefs.getBoolean("filter", false)) {
                    int lockdown = Settings.Secure.getInt(getContentResolver(), "always_on_vpn_lockdown", 0);
                    if (lockdown != 0) {
                        swEnabled.setChecked(false);
                        Toast.makeText(MainActivity.this, eu.faircode.netguard.R.string.msg_always_on_lockdown, Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            } else {
                swEnabled.setChecked(false);
                Toast.makeText(MainActivity.this, eu.faircode.netguard.R.string.msg_always_on, Toast.LENGTH_LONG).show();
                return;
            }

        String dns_mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
        if (dns_mode == null)
            dns_mode = "off";
        if (!"off".equals(dns_mode)) {
            swEnabled.setChecked(false);
            Toast.makeText(MainActivity.this, eu.faircode.netguard.R.string.msg_private_dns, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            final Intent prepare = VpnService.prepare(MainActivity.this);
            if (prepare == null) {
                onActivityResult(ActivityMain.REQUEST_VPN, RESULT_OK, null);
            } else {
                // Show dialog
                LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                View view = inflater.inflate(eu.faircode.netguard.R.layout.vpn, null, false);
                dialogVpn = new AlertDialog.Builder(MainActivity.this)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (running) {
                                    try {
                                        // com.android.vpndialogs.ConfirmDialog required
                                        startActivityForResult(prepare, ActivityMain.REQUEST_VPN);
                                    } catch (Throwable ex) {
                                        onActivityResult(ActivityMain.REQUEST_VPN, RESULT_CANCELED, null);
                                        prefs.edit().putBoolean("enabled", false).apply();
                                    }
                                }
                            }
                        })
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialogInterface) {
                                dialogVpn = null;
                            }
                        })
                        .create();
                dialogVpn.show();
            }
        } catch (Throwable ex) {
            // Prepare failed
            prefs.edit().putBoolean("enabled", false).apply();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Util.logExtras(data);

        if (requestCode == ActivityMain.REQUEST_VPN) {
            // Handle VPN approval
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", resultCode == RESULT_OK).apply();
            if (resultCode == RESULT_OK) {
                ServiceSinkhole.start("prepared", this);

                Toast on = Toast.makeText(MainActivity.this, eu.faircode.netguard.R.string.msg_on, Toast.LENGTH_LONG);
                on.setGravity(Gravity.CENTER, 0, 0);
                on.show();

                checkDoze();
            } else if (resultCode == RESULT_CANCELED)
                Toast.makeText(this, eu.faircode.netguard.R.string.msg_vpn_cancelled, Toast.LENGTH_LONG).show();

        }  else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void checkDoze() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final Intent doze = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            if (Util.batteryOptimizing(this) && getPackageManager().resolveActivity(doze, 0) != null) {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (!prefs.getBoolean("nodoze", false)) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(eu.faircode.netguard.R.layout.doze, null, false);
                    final CheckBox cbDontAsk = view.findViewById(eu.faircode.netguard.R.id.cbDontAsk);
                    dialogDoze = new AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                    startActivity(doze);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialogInterface) {
                                    dialogDoze = null;
                                    checkDataSaving();
                                }
                            })
                            .create();
                    dialogDoze.show();
                } else
                    checkDataSaving();
            } else
                checkDataSaving();
        }
    }

    private void checkDataSaving() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final Intent settings = new Intent(
                    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            if (Util.dataSaving(this) && getPackageManager().resolveActivity(settings, 0) != null) {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (!prefs.getBoolean("nodata", false)) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(eu.faircode.netguard.R.layout.datasaving, null, false);
                    final CheckBox cbDontAsk = view.findViewById(eu.faircode.netguard.R.id.cbDontAsk);
                    dialogDoze = new AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodata", cbDontAsk.isChecked()).apply();
                                    startActivity(settings);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodata", cbDontAsk.isChecked()).apply();
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialogInterface) {
                                    dialogDoze = null;
                                }
                            })
                            .create();
                    dialogDoze.show();
                }
            }
        }
    }
}
