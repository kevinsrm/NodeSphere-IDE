package com.mobile.nodeide;

import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    File project;
    LinearLayout nav;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(com.mobile.nodeide.R.layout.activity_main);
        getFilesDir().mkdirs(); nav=findViewById(R.id.navigation); if (state != null && state.containsKey("project")) project = new File(state.getString("project")); if (state == null) show(new ProjectsFragment()); else { Fragment current = getSupportFragmentManager().findFragmentById(R.id.content); navButton("‹ ›\nProjetos", () -> show(new ProjectsFragment())); navButton("✣\nPlugins", () -> show(new PluginsFragment())); navButton("✦\nAI Agent", () -> show(new AgentFragment())); if (current == null) show(new ProjectsFragment()); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { if(project != null) out.putString("project", project.getAbsolutePath()); super.onSaveInstanceState(out); }
    void show(Fragment f) {
        getSupportFragmentManager().beginTransaction().replace(R.id.content,f).commit();
        nav.removeAllViews();
        navButton("‹ ›\nProjetos", () -> show(new ProjectsFragment()));
        navButton("✣\nPlugins", () -> show(new PluginsFragment()));
        navButton("✦\nAI Agent", () -> show(new AgentFragment()));
    }
    void navButton(String label, Runnable action) {
        TextView t=new TextView(this); t.setText(label); t.setTextColor(Color.rgb(210,190,245)); t.setGravity(17); t.setTextSize(13); t.setOnClickListener(v->action.run()); nav.addView(t,new LinearLayout.LayoutParams(0,-1,1));
    }
    void open(File dir) { project=dir; show(new IdeFragment()); }
    File projects() { File f=new File(getFilesDir(),"projects"); f.mkdirs(); return f; }
    static String read(File file) throws IOException { try (InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); return out.toString("UTF-8"); } }
    static void write(File f,String text) throws IOException { File p=f.getParentFile(); if(p!=null) p.mkdirs(); try(OutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));} }
    void message(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
}
