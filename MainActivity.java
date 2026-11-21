package com.telelibre.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PantallaPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
