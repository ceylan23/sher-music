package com.sher.music;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        setTitle("服务器设置");

        SharedPreferences prefs = getSharedPreferences("sher_prefs", MODE_PRIVATE);
        EditText urlInput = findViewById(R.id.urlInput);
        Button saveBtn = findViewById(R.id.saveBtn);
        Button defaultBtn = findViewById(R.id.defaultBtn);

        urlInput.setText(prefs.getString("server_url", ""));

        saveBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty() && !url.startsWith("http")) {
                url = "http://" + url;
            }
            prefs.edit().putString("server_url", url).apply();
            Toast.makeText(this, "已保存，返回首页生效", Toast.LENGTH_SHORT).show();
            finish();
        });

        defaultBtn.setOnClickListener(v -> {
            urlInput.setText("");
            prefs.edit().remove("server_url").apply();
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
        });
    }
}
