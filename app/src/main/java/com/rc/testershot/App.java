package com.rc.testershot;

import android.app.Application;

import com.rc.testershot.service.ShotService;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ShotService.createChannel(this);
    }
}
