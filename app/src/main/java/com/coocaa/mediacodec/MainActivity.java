package com.coocaa.mediacodec;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn_camera).setOnClickListener(this);
        findViewById(R.id.btn_screen).setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_camera) {
            startActivity(new Intent(this, MainActivity1.class));
        } else if (id == R.id.btn_screen) {
            startActivity(new Intent(this, MainActivity2.class));
        }
    }
}